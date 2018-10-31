package streaming.test.mmlib

import java.io.File

import org.apache.spark.SparkCoreVersion
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.streaming.BasicSparkOperation
import streaming.common.ShellCommand
import streaming.core.pojo.Rating
import streaming.core.strategy.platform.SparkRuntime
import streaming.core.{BasicMLSQLConfig, SpecFunctions}
import streaming.dsl.ScriptSQLExec
import streaming.dsl.mmlib.algs._

/**
  * Created by allwefantasy on 13/9/2018.
  */
class MLLibSpec extends BasicSparkOperation with SpecFunctions with BasicMLSQLConfig {

  copySampleMovielensRratingsData
  copySampleLibsvmData
  copyTitanic

  "als" should "work fine" in {
    withBatchContext(setupBatchContext(batchParams, "classpath:///test/empty.json")) { runtime: SparkRuntime =>
      //执行sql
      implicit val spark = runtime.sparkSession
      import spark.implicits._
      ScriptSQLExec.contextGetOrForTest()

      ShellCommand.exec("rm -rf /tmp/william//tmp/als")

      val ratings = spark.read.textFile("/tmp/william/sample_movielens_ratings.txt")
        .map { str =>
          val fields = str.split("::")
          Rating(fields(0).toInt, fields(1).toInt, fields(2).toFloat, fields(3).toLong)
        }
        .toDF()
      val Array(training, test) = ratings.randomSplit(Array(0.8, 0.2))
      test.createOrReplaceTempView("test")
      val als = new SQLALSInPlace()
      val modelPath = "/tmp/als"
      als.train(training, "/tmp/william" + modelPath, Map(
        "fitParam.0.maxIter" -> "5",
        "fitParam.0.regParam" -> "0.01",
        "fitParam.0.userCol" -> "userId",
        "fitParam.0.itemCol" -> "movieId",
        "fitParam.0.ratingCol" -> "rating",
        "fitParam.1.maxIter" -> "1",
        "fitParam.1.regParam" -> "0.1",
        "fitParam.1.userCol" -> "userId",
        "fitParam.1.itemCol" -> "movieId",
        "fitParam.1.ratingCol" -> "rating",
        "evaluateTable" -> "test",
        "userRec" -> "10"
      ))
      val finalModelPath = SQLPythonFunc.getAlgMetalPath("/tmp/william/tmp/als", true) + "/0"
      spark.sql(s"select * from parquet.`$finalModelPath`").show()


      als.train(training, "/tmp/william" + modelPath, Map(
        "fitParam.0.maxIter" -> "1",
        "fitParam.0.regParam" -> "0.0001",
        "fitParam.0.userCol" -> "userId",
        "fitParam.0.itemCol" -> "movieId",
        "fitParam.0.ratingCol" -> "rating",
        "fitParam.0.userRec" -> "10",
        "fitParam.0.evaluateTable" -> "test"
      ))

      assume(new File("/tmp/william//tmp/als/_model_1").exists())
    }
  }

  "unbalance_sample" should "work fine" in {
    withBatchContext(setupBatchContext(batchParams, "classpath:///test/empty.json")) { runtime: SparkRuntime =>
      //执行sql
      implicit val spark = runtime.sparkSession
      ScriptSQLExec.contextGetOrForTest()
      val sq = createSSEL

      ScriptSQLExec.parse(
        """
          |load libsvm.`/sample_libsvm_data.txt` as data;
          |
          |train data as NaiveBayes.`/tmp/bayes_model` where multiModels="true";
          |
          |register NaiveBayes.`/tmp/bayes_model` as bayes_predict;
          |
          |select bayes_predict(features) as predict_label, label  from data as result;
          |
          |save overwrite result as json.`/tmp/result`;
          |
          |select * from result as output;
        """.stripMargin, sq)
      val res = spark.sql("select * from output").show(false)

    }
  }

  "SQLRandomForest" should "work fine" in {
    copySampleLibsvmData
    withBatchContext(setupBatchContext(batchParams, "classpath:///test/empty.json")) { runtime: SparkRuntime =>
      implicit val spark = runtime.sparkSession
      val randomForest = new SQLRandomForest()
      ScriptSQLExec.contextGetOrForTest()

      val df = spark.read.format("libsvm").load("/tmp/william/sample_libsvm_data.txt")
      df.createOrReplaceTempView("data")
      randomForest.train(df, "/tmp/SQLRandomForest", Map(
        "keepVersion" -> "true",
        "evaluateTable" -> "data",
        "fitParam.0.maxDepth" -> "3"
      ))
      val models = randomForest.load(spark, "/tmp/SQLRandomForest", Map("autoSelectByMetric" -> "f1"))
      val udf = randomForest.predict(spark, models, "jack", Map("autoSelectByMetric" -> "f1"))
      spark.udf.register("jack", udf)
      df.selectExpr("jack(features) as predict").show()
    }
  }

  "KMeans" should "work fine" in {
    copySampleLibsvmData
    withBatchContext(setupBatchContext(batchParams, "classpath:///test/empty.json")) { runtime: SparkRuntime =>
      implicit val spark = runtime.sparkSession
      val randomForest = new SQLKMeans()
      ScriptSQLExec.contextGetOrForTest()

      val df = spark.read.format("libsvm").load("/tmp/william/sample_libsvm_data.txt")
      df.createOrReplaceTempView("data")
      randomForest.train(df, "/tmp/KMeans", Map(
        "keepVersion" -> "true",
        "evaluateTable" -> "data",
        "fitParam.0.k" -> "2"
      ))
      var models = randomForest.load(spark, "/tmp/KMeans", Map("autoSelectByMetric" -> "silhouette"))
      var udf = randomForest.predict(spark, models, "jack", Map("autoSelectByMetric" -> "silhouette"))
      spark.udf.register("jack", udf)
      df.selectExpr("jack(features) as predict").show()


      models = randomForest.load(spark, "/tmp/KMeans", Map())
      udf = randomForest.predict(spark, models, "jack", Map())
      spark.udf.register("jack", udf)
      df.selectExpr("jack(features) as predict").show()


      randomForest.train(df, "/tmp/KMeans", Map(
        "keepVersion" -> "true",
        "fitParam.0.k" -> "2"))

      models = randomForest.load(spark, "/tmp/KMeans", Map())
      udf = randomForest.predict(spark, models, "jack", Map())
      spark.udf.register("jack", udf)
      df.selectExpr("jack(features) as predict").show()
    }
  }

  "GBTs" should "work fine" in {

    withBatchContext(setupBatchContext(batchParams, "classpath:///test/empty.json")) { runtime: SparkRuntime =>
      implicit val spark = runtime.sparkSession
      val randomForest = new SQLGBTs()
      ScriptSQLExec.contextGetOrForTest()

      val df = spark.read.format("libsvm").load("/tmp/william/sample_libsvm_data.txt")
      df.createOrReplaceTempView("data")
      randomForest.train(df, "/tmp/GBTs", Map(
        "keepVersion" -> "true",
        "evaluateTable" -> "data",
        "fitParam.0.maxDepth" -> "2"
      ))
      val models = randomForest.load(spark, "/tmp/GBTs", Map("autoSelectByMetric" -> "f1"))
      val udf = randomForest.predict(spark, models, "jack", Map("autoSelectByMetric" -> "f1"))
      spark.udf.register("jack", udf)
      df.selectExpr("jack(features) as predict").show()
    }
  }
  "AutoFeature" should "work fine" in {
    withBatchContext(setupBatchContext(batchParams, "classpath:///test/empty.json")) { runtime: SparkRuntime =>
      implicit val spark = runtime.sparkSession
      ScriptSQLExec.contextGetOrForTest()

      if (SparkCoreVersion.is_2_2_X()) {
        val df = spark.read.format("csv").option("header", "true").option("inferSchema", "true").load("/tmp/william/titanic.csv")
        val feature = new SQLAutoFeatureExt()
        feature.train(df, "/tmp/model2", Map("labelCol" -> "Survived", "workflowName" -> "wow"))
        feature.batchPredict(df, "/tmp/model2", Map()).show()
      }

      if (SparkCoreVersion.is_2_2_X()) {
        val df = spark.read.format("csv").option("header", "true").option("inferSchema", "true").load("/tmp/william/titanic.csv")
        val feature = new SQLAutoFeatureExt()
        feature.train(df, "/tmp/model2", Map(
          "labelCol" -> "Survived",
          "workflowName" -> "wow"
        ))
        feature.batchPredict(df, "/tmp/model2", Map()).show()
      }

    }
  }

//  "XGBoost" should "work fine" in {
//    withBatchContext(setupBatchContext(batchParams, "classpath:///test/empty.json")) { runtime: SparkRuntime =>
//      implicit val spark = runtime.sparkSession
//      ScriptSQLExec.contextGetOrForTest()
//      val df = spark.read.format("libsvm").load("/tmp/william/sample_libsvm_data.txt")
//      val feature = new SQLXGBoostExt()
//      val newdf = feature.train(df, "/tmp/model2", Map())
//      val status = newdf.collect().map(f => f.getAs[String]("status")).head
//      assert(status == "success")
//      feature.batchPredict(df, "/tmp/model2", Map()).show()
//    }
//  }

}
