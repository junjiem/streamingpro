package streaming.dsl.load.batch

import com.google.common.reflect.ClassPath
import net.csdn.common.reflect.ReflectHelper
import org.apache.spark.sql.{Row, SparkSession, _}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import scala.collection.JavaConversions._

/**
  * Created by allwefantasy on 21/9/2018.
  */
object AutoWorkflowSelfExplain {
  def apply(format: String, path: String, option: Map[String, String], sparkSession: SparkSession): AutoWorkflowSelfExplain = new AutoWorkflowSelfExplain(format, path, option)(sparkSession)
}


class AutoWorkflowSelfExplain(format: String, path: String, option: Map[String, String])(sparkSession: SparkSession) {
  private var table: DataFrame = null

  def isMatch = {
    new Then(this, Set("workflow", "workflowList", "workflowParams", "workflowExample", "workflowTypes").contains(format))
  }

  def explain() = {
    table = List(
      new AutoWorkflowStartup(format, path, option)(sparkSession),
      new AutoWorkflowList(format, path, option)(sparkSession),
      new AutoWorkflowTypes(format, path, option)(sparkSession)
    ).
      filter(explainer => explainer.isMatch).
      head.
      explain
  }

  class Then(parent: AutoWorkflowSelfExplain, isMatch: Boolean) {
    def thenDo() = {
      if (isMatch) {
        parent.explain()
      }
      new OrElse(parent, isMatch)
    }

  }

  class OrElse(parent: AutoWorkflowSelfExplain, isMatch: Boolean) {
    def orElse(f: () => DataFrame) = {
      if (!isMatch) {
        parent.table = f()
      }
      parent
    }

  }

  def get() = {
    table
  }
}

class AutoWorkflowStartup(format: String, path: String, option: Map[String, String])(sparkSession: SparkSession) extends SelfExplain {
  override def isMatch: Boolean = {
    format == "workflow" && path.isEmpty
  }

  override def explain: DataFrame = {

    val rows = sparkSession.sparkContext.parallelize(Seq(
      Row.fromSeq(Seq("command:list ", "load workflow.`list` as output;"))
    ), 1)

    sparkSession.createDataFrame(rows,
      StructType(Seq(
        StructField(name = "desc", dataType = StringType),
        StructField(name = "command", dataType = StringType)
      )))
  }
}


class AutoWorkflowList(format: String, path: String, option: Map[String, String])(sparkSession: SparkSession) extends SelfExplain {
  override def isMatch: Boolean = {
    (format == "workflow" && path == "list") || (format == "workflowList")
  }

  override def explain: DataFrame = {
    val instance = Class.forName("streaming.dsl.mmlib.algs.AutoFeature").newInstance()
    val items = ReflectHelper.method(instance, "listWorkflows").asInstanceOf[Seq[String]].map(f => Row.fromSeq(Seq(f)))
    val rows = sparkSession.sparkContext.parallelize(items, 1)
    sparkSession.createDataFrame(rows,
      StructType(Seq(
        StructField(name = "name", dataType = StringType)
      )))
  }
}

class AutoWorkflowTypes(format: String, path: String, option: Map[String, String])(sparkSession: SparkSession) extends SelfExplain {
  override def isMatch: Boolean = {
    (format == "workflow" && path == "types") || (format == "workflowTypes")
  }

  override def explain: DataFrame = {
    val items = ClassPath.from(getClass.getClassLoader).getTopLevelClasses("com.salesforce.op.features.types").map { f =>
      Row.fromSeq(Seq(f.getSimpleName, ""))
    }.toSeq
    val rows = sparkSession.sparkContext.parallelize(items, 1)
    sparkSession.createDataFrame(rows,
      StructType(Seq(
        StructField(name = "name", dataType = StringType),
        StructField(name = "desc", dataType = StringType)
      )))
  }
}
