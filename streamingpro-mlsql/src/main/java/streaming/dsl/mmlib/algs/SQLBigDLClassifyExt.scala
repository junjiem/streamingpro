package streaming.dsl.mmlib.algs

import java.util.UUID

import com.intel.analytics.bigdl.Module
import com.intel.analytics.bigdl.dlframes.{DLClassifier, DLModel}
import com.intel.analytics.bigdl.models.lenet.LeNet5
import com.intel.analytics.bigdl.models.utils.ModelBroadcast
import com.intel.analytics.bigdl.nn.{ClassNLLCriterion, Module}
import com.intel.analytics.bigdl.optim.Trigger
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.utils.{Engine, T}
import com.intel.analytics.bigdl.visualization.{LogTrainSummary, LogValidateSummary, TrainSummary, ValidationSummary}
import net.sf.json.JSONArray
import org.apache.spark.ml.linalg.SQLDataTypes.VectorType
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.ml.param.Param
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}
import org.apache.spark.sql.expressions.UserDefinedFunction
import streaming.common.{HDFSOperator, ScriptCacheKey, SourceCodeCompiler}
import streaming.dsl.ScriptSQLExec
import streaming.dsl.mmlib._
import streaming.dsl.mmlib.algs.classfication.BaseClassification
import streaming.dsl.mmlib.algs.param.BaseParams
import streaming.dsl.mmlib.algs.bigdl._

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

class SQLBigDLClassifyExt(override val uid: String) extends SQLAlg with MllibFunctions with BigDLFunctions with BaseClassification {

  def this() = this(BaseParams.randomUID())

  override def train(df: DataFrame, path: String, params: Map[String, String]): DataFrame = {
    params.get(disableSparkLog.name).
      map { m =>
        if (m.toBoolean) {
          WowLoggerFilter.redirectSparkInfoLogs()
        }
      }.getOrElse {
      WowLoggerFilter.redirectSparkInfoLogs()
    }

    Engine.init

    params.get(keepVersion.name).
      map(m => set(keepVersion, m.toBoolean)).
      getOrElse($(keepVersion))

    val eTable = params.get(evaluateTable.name)

    SQLPythonFunc.incrementVersion(path, $(keepVersion))
    val spark = df.sparkSession

    val c = ScriptSQLExec.contextGetOrForTest()

    val wrap = (fn: () => Any) => {
      try {
        ScriptSQLExec.setContextIfNotPresent(c)
        fn()
      } catch {
        case e: Exception =>
          logError(format_exception(e))
          throw e
      }
    }

    val wrapClass = (function: String) => {
      val className = s"StreamingProUDF_${UUID.randomUUID().toString.replaceAll("-", "")}"
      val newfun =
        s"""
           |class  ${className}{
           |import com.intel.analytics.bigdl.nn.keras._
           |import com.intel.analytics.bigdl.utils.Shape
           |import com.intel.analytics.bigdl.numeric.NumericFloat
           |  ${function}
           |}
            """.stripMargin
      (className, newfun)
    }


    bigDLClassifyTrain[Float](df, path, params, (newFitParam) => {
      require(newFitParam.contains("code"), "code is required")
      val (className, newfun) = wrapClass(newFitParam("code"))
      val clazz = wrap(() => {
        SourceCodeCompiler.execute(ScriptCacheKey(newfun, className))
      }).asInstanceOf[Class[_]]

      val method = SourceCodeCompiler.getMethod(clazz, "apply")
      val model = method.invoke(clazz.newInstance(), newFitParam).asInstanceOf[Module[Float]]

      val classWeightParamExtractor = new ClassWeightParamExtractor(this, newFitParam)
      val criterion = WowClassNLLCriterion(classWeightParamExtractor)

      s"""Criterion is configured :
         |weights: [${classWeightParamExtractor.weights.mkString(",")}]
         |sizeAverage: ${classWeightParamExtractor.sizeAverage.getOrElse(true)}
         |logProbAsInput: ${classWeightParamExtractor.logProbAsInput.getOrElse(true)}
         |paddingValue: ${classWeightParamExtractor.paddingValue.getOrElse(-1)}
         """.stripMargin.split("\n").foreach(line => logInfo(format(line)))

      val alg = new DLClassifier[Float](model, criterion,
        JSONArray.fromObject(newFitParam("featureSize")).map(f => f.asInstanceOf[Int]).toArray)

      val evaluateParamsExtractor = new EvaluateParamsExtractor(this, newFitParam)
      val bigDLEvaluateConfig = evaluateParamsExtractor.bigDLEvaluateConfig
      if (eTable.isDefined || bigDLEvaluateConfig.validationTable.isDefined) {
        val trigger = bigDLEvaluateConfig.trigger.getOrElse(Trigger.everyEpoch)
        val validateTable = spark.table(bigDLEvaluateConfig.validationTable.getOrElse($(evaluateTable)))
        val vMethods = bigDLEvaluateConfig.vMethods.getOrElse(evaluateParamsExtractor.defaultEvaluateMethods)
        val evaluateBatchSize = bigDLEvaluateConfig.batchSize.getOrElse(128)
        alg.setValidation(
          trigger,
          validateTable,
          vMethods,
          evaluateBatchSize
        )
        s"""Evaluate table is configured :
           |trigger: ${trigger.getClass.getName}
           |validateTable: ${validateTable}
           |vMethods: ${vMethods.map(f => f.getClass.getName).mkString(",")}
           |evaluateBatchSize: ${evaluateBatchSize}
         """.stripMargin.split("\n").foreach(line => logInfo(format(line)))
      }
      val summaryParamExtractor = new SummaryParamExtractor(this, newFitParam)
      summaryParamExtractor.summaryTrainDir.map { dir =>
        alg.setTrainSummary(new LogTrainSummary(dir, "train" + this.uid.split("\\$").last))
      }
      summaryParamExtractor.summaryValidateDir.map { dir =>
        alg.setValidationSummary(new LogValidateSummary(dir, "validate" + this.uid.split("\\$").last))
      }

      val optimizeParamExtractor = new OptimizeParamExtractor(this, newFitParam)
      optimizeParamExtractor.optimizeMethod.map { om =>
        alg.setOptimMethod(om)
      }

      alg
    }, (_model, newFitParam) => {
      eTable match {
        case Some(etable) =>
          val model = _model
          val evaluateTableDF = spark.table(etable)
          val predictions = model.transform(evaluateTableDF)
          multiclassClassificationEvaluate(predictions, (evaluator) => {
            evaluator.setLabelCol(newFitParam.getOrElse("labelCol", "label"))
            evaluator.setPredictionCol("prediction")
          })

        case None => List()
      }
    })

    formatOutput(getModelMetaData(spark, path))
  }


  override def batchPredict(df: DataFrame, path: String, params: Map[String, String]): DataFrame = {
    Engine.init
    val models = load(df.sparkSession, path, params).asInstanceOf[ArrayBuffer[DLModel[Float]]]
    models.head.transform(df)
  }

  override def load(sparkSession: SparkSession, path: String, params: Map[String, String]): Any = {
    Engine.init
    val (bestModelPath, baseModelPath, metaPath) = mllibModelAndMetaPath(path, params, sparkSession)
    val trainParams = sparkSession.read.parquet(metaPath + "/0").collect().head.getAs[Map[String, String]]("trainParams")

    val featureSize = JSONArray.fromObject(trainParams("featureSize")).map(f => f.asInstanceOf[Int]).toArray

    val model = Module.loadModule[Float](HDFSOperator.getFilePath(bestModelPath(0)))
    val dlModel = new DLModel[Float](model, featureSize)
    ArrayBuffer(dlModel)
  }

  override def predict(sparkSession: SparkSession, _model: Any, name: String, params: Map[String, String]): UserDefinedFunction = {
    val dlmodel = _model.asInstanceOf[ArrayBuffer[DLModel[Float]]].head
    val featureSize = dlmodel.featureSize
    val modelBroadCast = ModelBroadcast[Float]().broadcast(sparkSession.sparkContext, dlmodel.model.evaluate())


    val f = (vec: Vector) => {

      val localModel = modelBroadCast.value()
      val featureTensor = Tensor(vec.toArray.map(f => f.toFloat), Array(1) ++ featureSize)
      val output = localModel.forward(featureTensor)
      val res = output.toTensor[Float].clone().storage().array().map(f => f.toDouble)
      Vectors.dense(res)
    }

    UserDefinedFunction(f, VectorType, Some(Seq(VectorType)))
  }


  override def explainParams(sparkSession: SparkSession): DataFrame = {
    _explainParams(sparkSession, () => {
      val model = LeNet5(classNum = 10)
      val criterion = ClassNLLCriterion[Float]()
      val alg = new DLClassifier[Float](model, criterion, Array(28, 28))
      alg
    })
  }

  override def modelType: ModelType = AlgType

  override def doc: Doc = Doc(MarkDownDoc,
    """
      |BigDLClassifyExt is used to do classification tasks with deep learning tech which
      |based on [BigDL](https://github.com/intel-analytics/BigDL).
      |
      |Check available params:
      |
      |```sql
      |load modelParams.`BigDLClassifyExt` as output;
      |```
      |
      |Check example:
      |
      |```
      |load modelExample.`BigDLClassifyExt` as output;
      |```
      |
      |The most powerful of this module is that you can define your model with code snippet in
      |Keras style.
      |
      |and fitParam.0.code='''
      |                   def apply(params:Map[String,String])={
      |                        val model = Sequential()
      |                        model.add(Reshape(Array(1, 28, 28), inputShape = Shape(28, 28, 1)))
      |                        model.add(Convolution2D(6, 5, 5, activation = "tanh").setName("conv1_5x5"))
      |                        model.add(MaxPooling2D())
      |                        model.add(Convolution2D(12, 5, 5, activation = "tanh").setName("conv2_5x5"))
      |                        model.add(MaxPooling2D())
      |                        model.add(Flatten())
      |                        model.add(Dense(100, activation = "tanh").setName("fc1"))
      |                        model.add(Dense(params("classNum").toInt, activation = "softmax").setName("fc2"))
      |                    }
      |
      |The `code` param is used to describe your model architecture.
      |The code example defines LeNet5 model.
      |
      |More layers(e.g. Reshape,Convolution2D) please check
      |[bigdl-keras-doc](https://bigdl-project.github.io/master/#KerasStyleAPIGuide/Layers/activation/)
      |and
      |[bigdl-keras-classes-list](https://github.com/intel-analytics/BigDL/tree/master/spark/dl/src/main/scala/com/intel/analytics/bigdl/nn/keras).
      |
      |
      |
    """.stripMargin)

  override def codeExample: Code = Code(SQLCode,
    """
      |-- You can download the MNIST Data from [here](http://yann.lecun.com/exdb/mnist/). Unzip all the
      |-- files and put them in one folder(e.g. mnist).
      |
      |set json = '''{}''';
      |load jsonStr.`json` as emptyData;
      |
      |run emptyData as MnistLoaderExt.`` where
      |mnistDir="/Users/allwefantasy/Downloads/mnist"
      |as data;
      |
      |set modelOutputPath = "/tmp/bigdl";
      |
      |train data as BigDLClassifyExt.`${modelOutputPath}` where
      |fitParam.0.featureSize="[28,28]"
      |and fitParam.0.classNum="10"
      |and fitParam.0.maxEpoch="1"
      |and fitParam.0.code='''
      |                   def apply(params:Map[String,String])={
      |                        val model = Sequential()
      |                        model.add(Reshape(Array(1, 28, 28), inputShape = Shape(28, 28, 1)))
      |                        model.add(Convolution2D(6, 5, 5, activation = "tanh").setName("conv1_5x5"))
      |                        model.add(MaxPooling2D())
      |                        model.add(Convolution2D(12, 5, 5, activation = "tanh").setName("conv2_5x5"))
      |                        model.add(MaxPooling2D())
      |                        model.add(Flatten())
      |                        model.add(Dense(100, activation = "tanh").setName("fc1"))
      |                        model.add(Dense(params("classNum").toInt, activation = "softmax").setName("fc2"))
      |                    }
      |
      |'''
      |;
      |predict data as BigDLClassifyExt.`${modelOutputPath}`;
      |
      |register BigDLClassifyExt.`${modelOutputPath}` as mnistPredict;
      |
      |select
      |vec_argmax(mnistPredict(vec_dense(features))) as predict_label,
      |label from data
      |as output;
    """.stripMargin)

  /*
  // evaluate.trigger.
  // evaluate.table
  // evaluate.method
  // evaluate.batchSize
   */
  final val evaluate_table: Param[String] = new Param[String](this, "fitParam.[group].evaluate.table", "")

  final val evaluate_methods: Param[String] = new Param[String](this, "fitParam.[group].evaluate.methods",
    s"${EvaluateParamsExtractor.evaluateMethodCandidatesStr}")

  final val evaluate_batchSize: Param[String] = new Param[String](this, "fitParam.[group].evaluate.batchSize", "")

  final val evaluate_trigger_everyEpoch: Param[String] = new Param[String](this, "fitParam.[group].evaluate.trigger.everyEpoch",
    "A trigger that triggers an action when each epoch finishs.")

  final val evaluate_trigger_severalIteration: Param[Int] = new Param[Int](this, "fitParam.[group].evaluate.trigger.severalIteration",
    "A trigger that triggers an action every \"n\" iterations.")

  final val evaluate_trigger_maxEpoch: Param[Int] = new Param[Int](this, "fitParam.[group].evaluate.trigger.maxEpoch",
    "A trigger that triggers an action when training reaches")

  final val evaluate_trigger_maxIteration: Param[Int] = new Param[Int](this, "fitParam.[group].evaluate.trigger.maxIteration",
    "A trigger that triggers an action when training reaches\n the number of iterations specified by \"max\".")

  final val evaluate_trigger_maxScore: Param[Float] = new Param[Float](this, "fitParam.[group].evaluate.trigger.maxScore",
    "A trigger that triggers an action when validation score larger than \"max\" score")

  final val minLoss: Param[Float] = new Param[Float](this, "fitParam.[group].evaluate.trigger.minLoss",
    "A trigger that triggers an action when training loss less than \"min\" loss")


  final val criterion_classWeight: Param[String] = new Param[String](this, "fitParam.[group].criterion.classWeight",
    """
      |It's used in negative log likelihood criterion which is useful to train a classification problem with n
      | * classes.
      |If provided, the optional argument weights should be float json array e.g. [2.0,1.0] assigning weight to
      |each of the classes. This is particularly useful when you have an unbalanced training set.
      |default null
    """.stripMargin)

  final val criterion_sizeAverage: Param[Boolean] = new Param[Boolean](this, "fitParam.[group].criterion.sizeAverage",
    "default true")

  final val criterion_logProbAsInput: Param[Boolean] = new Param[Boolean](this, "fitParam.[group].criterion.logProbAsInput",
    "default true")

  final val criterion_paddingValue: Param[Int] = new Param[Int](this, "fitParam.[group].criterion.paddingValue",
    "default -1")

  final val summary_trainDir: Param[String] = new Param[String](this, "fitParam.[group].summary.trainDir",
    "where you train summary store. HDFS/Local supports")

  final val summary_validateDir: Param[String] = new Param[String](this, "fitParam.[group].summary.validateDir",
    "where you validate summary store. HDFS/Local supports")

  final val disableSparkLog: Param[String] = new Param[String](this, "disableSparkLog",
    "")

  final val optimizeMethod: Param[String] = new Param[String](this, "fitParam.[group].optimizeMethod",
    s"""${OptimizeParamExtractor.optimizeMethodCandidatesStr}""")

}

