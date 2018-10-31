package org.apache.spark

/**
  * Created by allwefantasy on 20/9/2018.
  */
object SparkCoreVersion {
  def version = {
    val coreVersion = org.apache.spark.SPARK_VERSION.split("\\.").take(2).mkString(".") + ".x"
    coreVersion
  }

  def is_2_2_X() = {
    version == VERSION_2_2_X
  }

  def is_2_3_X() = {
    version == VERSION_2_3_X
  }

  val VERSION_2_2_X = "2.2.x"
  val VERSION_2_3_X = "2.3.x"
}
