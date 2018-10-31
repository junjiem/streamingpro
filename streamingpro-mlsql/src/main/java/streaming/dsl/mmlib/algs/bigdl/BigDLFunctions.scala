package streaming.dsl.mmlib.algs.bigdl

import com.intel.analytics.bigdl.dlframes.{DLClassifier, DLModel}
import org.apache.spark.sql.{DataFrame, Row, SaveMode}
import org.apache.spark.sql.types._
import streaming.common.{HDFSOperator, ScalaObjectReflect}
import streaming.common.ScalaMethodMacros._
import streaming.dsl.mmlib.algs._
import streaming.log.{Logging, WowLog}


trait BigDLFunctions extends Functions with Logging with WowLog with Serializable {

  def bigDLClassifyTrain[T](df: DataFrame, path: String, params: Map[String, String],
                            modelType: (Map[String, String]) => DLClassifier[T],
                            evaluate: (DLModel[T], Map[String, String]) => List[MetricValue]
                           ) = {

    val keepVersion = params.getOrElse("keepVersion", "true").toBoolean

    val mf = (trainData: DataFrame, fitParam: Map[String, String], modelIndex: Int) => {
      require(fitParam.contains("classNum"), "classNum is required")
      require(fitParam.contains("featureSize"), "featureSize is required")

      val newFitParam = Map(
        str[BigDLDefaultConfig](_.batchSize) -> BigDLDefaultConfig().batchSize.toString,
        str[BigDLDefaultConfig](_.maxEpoch) -> BigDLDefaultConfig().maxEpoch.toString
      ) ++ fitParam

      val alg = modelType(newFitParam)
      configureModel(alg, newFitParam)

      logInfo(format(s"[training] [alg=${alg.getClass.getName}] [keepVersion=${keepVersion}]"))

      var status = "success"
      val modelTrainStartTime = System.currentTimeMillis()
      val modelPath = SQLPythonFunc.getAlgModelPath(path, keepVersion) + "/" + modelIndex
      var scores: List[MetricValue] = List()
      try {
        val newmodel = alg.fit(trainData)
        newmodel.model.saveModule(HDFSOperator.getFilePath(modelPath))
        scores = evaluate(newmodel, newFitParam)
        logInfo(format(s"[trained] [alg=${alg.getClass.getName}] [metrics=${scores}] [model hyperparameters=${
          newmodel.explainParams().replaceAll("\n", "\t")
        }]"))
      } catch {
        case e: Exception =>
          logInfo(format_exception(e))
          status = "fail"
      }
      val modelTrainEndTime = System.currentTimeMillis()
      val metrics = scores.map(score => Row.fromSeq(Seq(score.name, score.value))).toArray
      Row.fromSeq(Seq(modelPath, modelIndex, alg.getClass.getName, metrics, status, modelTrainStartTime, modelTrainEndTime, fitParam))
    }
    var fitParam = arrayParamsWithIndex("fitParam", params)
    if (fitParam.size == 0) {
      fitParam = Array((0, Map[String, String]()))
    }

    val wowRes = fitParam.map { fp =>
      mf(df, fp._2, fp._1)
    }

    val wowRDD = df.sparkSession.sparkContext.parallelize(wowRes, 1)

    df.sparkSession.createDataFrame(wowRDD, StructType(Seq(
      StructField("modelPath", StringType),
      StructField("algIndex", IntegerType),
      StructField("alg", StringType),
      StructField("metrics", ArrayType(StructType(Seq(
        StructField(name = "name", dataType = StringType),
        StructField(name = "value", dataType = DoubleType)
      )))),

      StructField("status", StringType),
      StructField("startTime", LongType),
      StructField("endTime", LongType),
      StructField("trainParams", MapType(StringType, StringType))
    ))).
      write.
      mode(SaveMode.Overwrite).
      parquet(SQLPythonFunc.getAlgMetalPath(path, keepVersion) + "/0")
  }


}


