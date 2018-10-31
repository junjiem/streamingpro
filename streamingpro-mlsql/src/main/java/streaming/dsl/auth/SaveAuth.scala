package streaming.dsl.auth

import streaming.dsl.parser.DSLSQLParser._
import streaming.dsl.template.TemplateMerge
import streaming.dsl.{AuthProcessListener, DslTool}


/**
  * Created by allwefantasy on 11/9/2018.
  */
class SaveAuth(authProcessListener: AuthProcessListener) extends MLSQLAuth with DslTool {
  val env = authProcessListener.listener.env().toMap

  def evaluate(value: String) = {
    TemplateMerge.merge(value, authProcessListener.listener.env().toMap)
  }

  override def auth(_ctx: Any): TableAuthResult = {
    val ctx = _ctx.asInstanceOf[SqlContext]
    var final_path = ""
    var format = ""
    var option = Map[String, String]()
    var tableName = ""
    var partitionByCol = Array[String]()

    val owner = option.get("owner")

    (0 to ctx.getChildCount() - 1).foreach { tokenIndex =>
      ctx.getChild(tokenIndex) match {
        case s: FormatContext =>
          format = s.getText
          format match {
            case "hive" =>
            case _ =>
              format = s.getText
          }


        case s: PathContext =>
          format match {
            case "hive" | "kafka8" | "kafka9" | "hbase" | "redis" | "es" | "jdbc" =>
              final_path = cleanStr(s.getText)
            case "parquet" | "json" | "csv" | "orc" =>
              final_path = withPathPrefix(authProcessListener.listener.pathPrefix(owner), cleanStr(s.getText))
            case _ =>
              final_path = cleanStr(s.getText)
          }

          final_path = TemplateMerge.merge(final_path, env)

        case s: TableNameContext =>
          tableName = s.getText
        case s: ColContext =>
          partitionByCol = cleanStr(s.getText).split(",")
        case s: ExpressionContext =>
          option += (cleanStr(s.qualifiedName().getText) -> evaluate(getStrOrBlockStr(s)))
        case s: BooleanExpressionContext =>
          option += (cleanStr(s.expression().qualifiedName().getText) -> evaluate(getStrOrBlockStr(s.expression())))
        case _ =>
      }
    }

    val mLSQLTable = if (format == "hive") {
      val Array(db, table) = final_path.split("\\.")
      MLSQLTable(Some(db), Some(table), TableType.HIVE)
    } else {
      MLSQLTable(None, Some(final_path), TableType.from(format).get)
    }
    authProcessListener.addTable(mLSQLTable)
    TableAuthResult.empty()

  }
}
