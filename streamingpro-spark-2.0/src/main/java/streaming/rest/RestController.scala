package streaming.rest

import net.csdn.annotation.rest.At
import net.csdn.common.collections.WowCollections
import net.csdn.common.path.Url
import net.csdn.modules.http.{ApplicationController, ViewType}
import net.csdn.modules.http.RestRequest.Method._
import net.csdn.modules.transport.HttpTransportService
import org.apache.spark.sql.SaveMode
import streaming.core.{AsyncJobRunner, DownloadRunner, JobCanceller}
import streaming.core.strategy.platform.{PlatformManager, SparkRuntime}

import scala.collection.JavaConversions._

/**
  * Created by allwefantasy on 28/3/2017.
  */
class RestController extends ApplicationController {

  @At(path = Array("/runtime/spark/sql"), types = Array(GET, POST))
  def sql = {
    if (!runtime.isInstanceOf[SparkRuntime]) render(400, "only support spark application")
    val sparkRuntime = runtime.asInstanceOf[SparkRuntime]
    val sparkSession = sparkRuntime.sparkSession
    val tableToPaths = params().filter(f => f._1.startsWith("tableName.")).map(table => (table._1.split("\\.").last, table._2))

    tableToPaths.foreach { tableToPath =>
      val tableName = tableToPath._1
      val loaderClzz = params.filter(f => f._1 == s"loader_clzz.${tableName}").head
      val newParams = params.filter(f => f._1.startsWith(s"loader_param.${tableName}.")).map { f =>
        val coms = f._1.split("\\.")
        val paramStr = coms.takeRight(coms.length - 2).mkString(".")
        (paramStr, f._2)
      }.toMap + loaderClzz

      if (!sparkSession.catalog.tableExists(tableToPath._1) || paramAsBoolean("forceCreateTable", false)) {
        sparkRuntime.operator.createTable(tableToPath._2, tableToPath._1, newParams)
      }
    }

    val sql = if (param("sql").contains(" limit ")) param("sql") else param("sql") + " limit 1000"
    val result = sparkRuntime.operator.runSQL(sql).mkString(",")

    param("resultType", "html") match {
      case "json" => render(200, "[" + result + "]", ViewType.json)
      case _ => renderHtml(200, "/rest/sqlui-result.vm", WowCollections.map("feeds", result))
    }
  }

  @At(path = Array("/run/sql"), types = Array(GET, POST))
  def ddlSql = {
    val sparkSession = runtime.asInstanceOf[SparkRuntime].sparkSession
    val path = param("path", "-")
    if (paramAsBoolean("async", false) && path == "-") {
      render(s"""path should not be empty""")
    }

    paramAsBoolean("async", false).toString match {
      case "true" if param("callback", "") != "" =>
        AsyncJobRunner.run(() => {
          sparkSession.sql(param("sql")).write.format(param("format", "csv")).mode(SaveMode.Overwrite).save(path)
          val htp = findService(classOf[HttpTransportService])
          import scala.collection.JavaConversions._
          htp.get(new Url(param("callback")), Map("url_path" -> s"""/download?fileType=raw&file_suffix=${param("format", "csv")}&paths=$path"""))
        })
        render("[]")

      case "false" if param("resultType", "") == "file" =>
        JobCanceller.runWithGroup(sparkSession.sparkContext, paramAsLong("timeout", 30000), () => {
          sparkSession.sql(param("sql")).write.format(param("format", "csv")).mode(SaveMode.Overwrite).save(path)
          render(s"""/download?fileType=raw&file_suffix=${param("format", "csv")}&paths=$path""")
        })

      case "false" if param("resultType", "") != "file" =>
        JobCanceller.runWithGroup(sparkSession.sparkContext, paramAsLong("timeout", 30000), () => {
          val res = sparkSession.sql(param("sql")).toJSON.collect().mkString(",")
          render("[" + res + "]")
        })
    }
  }

  @At(path = Array("/download"), types = Array(GET, POST))
  def download = {
    val filename = param("fileName", System.currentTimeMillis() + "")
    param("fileType", "raw") match {
      case "tar" =>
        restResponse.httpServletResponse().setContentType("application/octet-stream")
        restResponse.httpServletResponse().addHeader("Content-Disposition", "attachment;filename=" + new String((filename + ".tar").getBytes))
        restResponse.httpServletResponse().addHeader("Transfer-Encoding", "chunked")
        DownloadRunner.getTarFileByPath(restResponse.httpServletResponse(), param("paths")) match {
          case 200 => render("success")
          case 400 => render(400, "download fail")
          case 500 => render(500, "server error")
        }
      case "raw" =>
        restResponse.httpServletResponse().setContentType("application/octet-stream")
        restResponse.httpServletResponse().addHeader("Content-Disposition", "attachment;filename=" + new String((filename + "." + param("file_suffix")).getBytes))
        restResponse.httpServletResponse().addHeader("Transfer-Encoding", "chunked")
        DownloadRunner.getRawFileByPath(restResponse.httpServletResponse(), param("paths"), paramAsLong("pos", 0)) match {
          case 200 => render("success")
          case 400 => render(400, "download fail")
          case 500 => render(500, "server error")
        }

    }

  }

  @At(path = Array("/table/create"), types = Array(GET, POST))
  def tableCreate = {
    val sparkRuntime = runtime.asInstanceOf[SparkRuntime]
    if (!runtime.isInstanceOf[SparkRuntime]) render(400, "only support spark application")

    try {
      sparkRuntime.operator.createTable(param("tableName"), param("tableName"), params().toMap)
    } catch {
      case e: Exception =>
        e.printStackTrace()
        render(e.getMessage)
    }

    render("register success")

  }

  @At(path = Array("/check"), types = Array(GET, POST))
  def check = {
    val sparkSession = runtime.asInstanceOf[SparkRuntime].sparkSession
    render(sparkSession.table(param("name")))
  }

  def runtime = PlatformManager.getRuntime
}
