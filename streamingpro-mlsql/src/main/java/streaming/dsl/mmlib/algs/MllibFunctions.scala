package streaming.dsl.mmlib.algs

import org.apache.spark.sql.types.{MapType, StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}
import org.joda.time.DateTime
import streaming.log.{Logging, WowLog}

/**
  * Created by allwefantasy on 25/7/2018.
  */
trait MllibFunctions extends Logging with WowLog with Serializable {

  def formatOutput(newDF: DataFrame) = {
    val schema = newDF.schema

    def formatMetrics(field: StructField, row: Row) = {
      val value = row.getSeq[Row](schema.fieldIndex(field.name))
      value.map(row => s"${row.getString(0)}:  ${row.getDouble(1)}").mkString("\n")
    }

    def formatDate(field: StructField, row: Row) = {
      val value = row.getLong(schema.fieldIndex(field.name))
      new DateTime(value).toString("yyyyMMdd mm:HH:ss:SSS")
    }

    val rows = newDF.collect().flatMap { row =>
      List(Row.fromSeq(Seq("---------------", "------------------"))) ++ schema.fields.map { field =>
        val value = field.name match {
          case "metrics" => formatMetrics(field, row)
          case "startTime" | "endTime" => formatDate(field, row)
          case _ => row.get(schema.fieldIndex(field.name)).toString
        }
        Row.fromSeq(Seq(field.name, value))
      }

    }
    val newSchema = StructType(Seq(StructField("name", StringType), StructField("value", StringType)))
    newDF.sparkSession.createDataFrame(newDF.sparkSession.sparkContext.parallelize(rows, 1), newSchema)
  }

  def mllibModelAndMetaPath(path: String, params: Map[String, String], sparkSession: SparkSession) = {
    val maxVersion = SQLPythonFunc.getModelVersion(path)
    var algIndex = params.get("algIndex").map(f => f.toInt)

    val versionEnabled = maxVersion match {
      case Some(v) => true
      case None => false
    }
    val modelVersion = params.getOrElse("modelVersion", maxVersion.getOrElse(-1).toString).toInt

    val baseModelPath = if (modelVersion == -1) SQLPythonFunc.getAlgModelPath(path, versionEnabled)
    else SQLPythonFunc.getAlgModelPathWithVersion(path, modelVersion)


    val metaPath = if (modelVersion == -1) SQLPythonFunc.getAlgMetalPath(path, versionEnabled)
    else SQLPythonFunc.getAlgMetalPathWithVersion(path, modelVersion)


    val autoSelectByMetric = params.getOrElse("autoSelectByMetric", "f1")

    val modelList = sparkSession.read.parquet(metaPath + "/0").collect()

    val bestModelPath = algIndex match {
      case Some(i) => Seq(baseModelPath + "/" + i)
      case None =>
        modelList.map { row =>
          var metric: Row = null
          val metrics = row(3).asInstanceOf[scala.collection.mutable.WrappedArray[Row]]
          if (metrics.size > 0) {
            val targeMetrics = metrics.filter(f => f.getString(0) == autoSelectByMetric)
            if (targeMetrics.size > 0) {
              metric = targeMetrics.head
            } else {
              metric = metrics.head
              logInfo(format(s"No target metric: ${autoSelectByMetric} is found, use the first metric: ${metric.getDouble(1)}"))
            }
          }
          val metricScore = if (metric == null) {
            logInfo(format("No metric is found, system  will use first model"))
            0.0
          } else {
            metric.getAs[Double](1)
          }

          (metricScore, row(0).asInstanceOf[String], row(1).asInstanceOf[Int])
        }
          .toSeq
          .sortBy(f => f._1)(Ordering[Double].reverse)
          .take(1)
          .map(f => {
            algIndex = Option(f._3)
            baseModelPath + "/" + f._2.split("/").last
          })
    }


    (bestModelPath, baseModelPath, metaPath)
  }

  def saveMllibTrainAndSystemParams(sparkSession: SparkSession, params: Map[String, String], metaPath: String) = {
    val tempRDD = sparkSession.sparkContext.parallelize(Seq(Seq(Map[String, String](), params)), 1).map { f =>
      Row.fromSeq(f)
    }
    sparkSession.createDataFrame(tempRDD, StructType(Seq(
      StructField("systemParam", MapType(StringType, StringType)),
      StructField("trainParams", MapType(StringType, StringType))))).
      write.
      mode(SaveMode.Overwrite).
      parquet(metaPath + "/1")
  }
}

case class MetricValue(name: String, value: Double)
