package streaming.dsl.auth

import org.antlr.v4.runtime.misc.Interval
import org.apache.spark.sql.execution.MLSQLAuthParser
import streaming.dsl.parser.DSLSQLLexer
import streaming.dsl.parser.DSLSQLParser._
import streaming.dsl.template.TemplateMerge
import streaming.dsl.{AuthProcessListener, DslTool}


/**
  * Created by allwefantasy on 11/9/2018.
  */
class SelectAuth(authProcessListener: AuthProcessListener) extends MLSQLAuth with DslTool {
  val env = authProcessListener.listener.env().toMap

  def evaluate(value: String) = {
    TemplateMerge.merge(value, authProcessListener.listener.env().toMap)
  }

  override def auth(_ctx: Any): TableAuthResult = {
    val ctx = _ctx.asInstanceOf[SqlContext]
    val input = ctx.start.getTokenSource().asInstanceOf[DSLSQLLexer]._input

    val start = ctx.start.getStartIndex()
    val stop = ctx.stop.getStopIndex()
    val interval = new Interval(start, stop)
    val originalText = input.getText(interval)

    val wowText = TemplateMerge.merge(originalText, env)

    val chunks = wowText.split("\\s+")
    val tableName = chunks.last.replace(";", "")
    val sql = wowText.replaceAll(s"as[\\s|\\n]+${tableName}", "")

    val tableRefs = MLSQLAuthParser.filterTables(sql, authProcessListener.listener.sparkSession)

    val tables = tableRefs.foreach { f =>
      f.database match {
        case Some(db) =>
          val exists = authProcessListener.withDBs.filter(m => f.table == m.table.get && db == m.db.get).size > 0
          if (!exists) {
            authProcessListener.addTable(MLSQLTable(Some(db), Some(f.table), TableType.HIVE))
          }
        case None =>
          val exists = authProcessListener.withoutDBs.filter(m => f.table == m.table.get).size > 0
          if (!exists) {
            authProcessListener.addTable(MLSQLTable(Some("default"), Some(f.table), TableType.HIVE))
          }
      }
    }

    val exists = authProcessListener.withoutDBs.filter(m => tableName == m.table.get).size > 0
    if (!exists) {
      authProcessListener.addTable(MLSQLTable(None, Some(tableName), TableType.TEMP))
    }


    TableAuthResult.empty()

  }
}
