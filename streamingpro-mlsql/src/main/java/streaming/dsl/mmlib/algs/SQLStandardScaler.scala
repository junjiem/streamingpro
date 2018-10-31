package streaming.dsl.mmlib.algs

import org.apache.spark.ml.feature.{StandardScaler, StandardScalerModel}
import org.apache.spark.ml.linalg.SQLDataTypes._
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.mllib.feature
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.DoubleType
import streaming.dsl.mmlib.SQLAlg
import org.apache.spark.mllib.linalg.{Vector => OldVector, Vectors => OldVectors}


/**
  * Created by allwefantasy on 4/2/2018.
  */
class SQLStandardScaler extends SQLAlg with Functions {
  override def train(df: DataFrame, path: String, params: Map[String, String]): DataFrame = {
    val rfc = new StandardScaler()
    configureModel(rfc, params)
    val model = rfc.fit(df)
    model.write.overwrite().save(path)
    emptyDataFrame()(df)
  }

  override def load(sparkSession: SparkSession, path: String, params: Map[String, String]): Any = {
    val model = StandardScalerModel.load(path)
    model
  }

  override def predict(sparkSession: SparkSession, _model: Any, name: String, params: Map[String, String]): UserDefinedFunction = {
    val model = sparkSession.sparkContext.broadcast(_model.asInstanceOf[StandardScalerModel])
    val std = getModelField(model.value, "std").asInstanceOf[Vector]
    val mean = getModelField(model.value, "mean").asInstanceOf[Vector]
    val withStd = model.value.getWithStd
    val withMean = model.value.getWithMean
    val scaler = new org.apache.spark.mllib.feature.StandardScalerModel(OldVectors.fromML(std), OldVectors.fromML(mean), withStd, withMean)
    val f: Vector => Vector = v => scaler.transform(OldVectors.fromML(v)).asML
    UserDefinedFunction(f, VectorType, Some(Seq(VectorType)))
  }
}
