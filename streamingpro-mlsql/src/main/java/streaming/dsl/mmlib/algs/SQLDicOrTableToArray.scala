package streaming.dsl.mmlib.algs

import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.types.{ArrayType, StringType, StructField, StructType}
import streaming.dsl.mmlib.SQLAlg

/**
  * Created by allwefantasy on 8/2/2018.
  */
class SQLDicOrTableToArray extends SQLAlg with Functions {

  def internal_train(df: DataFrame, params: Map[String, String]) = {
    val session = df.sparkSession
    var result = Array[Row]()
    if (params.contains("dic.paths")) {
      result ++= params("dic.names").split(",").zip(params("dic.paths").split(",")).map { f =>
        val wordsList = session.sparkContext.textFile(f._2).collect()
        (f._1, wordsList)
      }.map(f => Row.fromSeq(Seq(f._1, f._2)))

    }

    if (params.contains("table.paths")) {
      result ++= params("table.names").split(",").zip(params("table.paths").split(",")).map { f =>
        val wordsList = session.table(f._2).collect().map(f => f.get(0).toString)
        (f._1, wordsList)
      }.map(f => Row.fromSeq(Seq(f._1, f._2)))
    }

    val model = session.createDataFrame(
      session.sparkContext.parallelize(result),
      StructType(Seq(StructField("name", StringType), StructField("tokens", ArrayType(StringType)))))
    //model is also is a table
    model
  }

  override def train(df: DataFrame, path: String, params: Map[String, String]): DataFrame = {
    val model = internal_train(df, params)
    model.write.mode(SaveMode.Overwrite).parquet(path)
    emptyDataFrame()(df)

  }

  override def load(sparkSession: SparkSession, path: String, params: Map[String, String]): Any = {
    sparkSession.read.parquet(path).collect().map(f => (f.getString(0), f.getSeq(1))).toMap
  }

  def internal_predict(sparkSession: SparkSession, _model: Any, name: String) = {
    val model = sparkSession.sparkContext.broadcast(_model.asInstanceOf[Map[String, Seq[String]]])
    val f = (name: String) => {
      model.value(name)
    }
    Map(name -> f)
  }

  override def predict(sparkSession: SparkSession, _model: Any, name: String, params: Map[String, String]): UserDefinedFunction = {
    val ip = internal_predict(sparkSession, _model, name)
    UserDefinedFunction(ip(name), ArrayType(StringType), Some(Seq(StringType)))
  }
}
