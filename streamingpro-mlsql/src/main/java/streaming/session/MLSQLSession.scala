package streaming.session

import java.io.IOException

import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.security.UserGroupInformation
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.{MLSQLSparkConst, SparkConf}

import scala.collection.mutable.{HashSet => MHSet}
import streaming.log.Logging


/**
  * Created by allwefantasy on 1/6/2018.
  */
class MLSQLSession(username: String,
                   password: String,
                   ipAddress: String,
                   withImpersonation: Boolean,
                   sessionManager: SessionManager,
                   opManager: MLSQLOperationManager,
                   sessionConf: Map[String, String] = Map()
                  ) extends Logging {


  @volatile private[this] var lastAccessTime: Long = System.currentTimeMillis()
  private[this] var lastIdleTime = 0L

  private[this] val activeOperationSet = new MHSet[String]()

  private[this] val sessionUGI: UserGroupInformation = {
    val currentUser = UserGroupInformation.getCurrentUser
    if (withImpersonation) {
      if (UserGroupInformation.isSecurityEnabled) {
        if (sessionConf.contains(MLSQLSparkConst.PRINCIPAL) && sessionConf.contains(MLSQLSparkConst.KEYTAB)) {
          // If principal and keytab are configured, do re-login in case of token expiry.
          // Do not check keytab file existing as spark-submit has it done
          currentUser.reloginFromKeytab()
        }
        UserGroupInformation.createProxyUser(username, currentUser)
      } else {
        UserGroupInformation.createRemoteUser(username)
      }
    } else {
      currentUser
    }
  }
  private[this] lazy val sparkSessionWithUGI = new SparkSessionWithUGI(sessionUGI, sessionConf)

  private[this] def acquire(userAccess: Boolean): Unit = {
    if (userAccess) {
      lastAccessTime = System.currentTimeMillis
    }
  }

  private[this] def release(userAccess: Boolean): Unit = {
    if (userAccess) {
      lastAccessTime = System.currentTimeMillis
    }
    if (activeOperationSet.isEmpty) {
      lastIdleTime = System.currentTimeMillis
    } else {
      lastIdleTime = 0
    }
  }

  def sparkSession: SparkSession = this.sparkSessionWithUGI.sparkSession

  def ugi: UserGroupInformation = this.sessionUGI

  def open(sessionConf: Map[String, String]): Unit = {
    sparkSessionWithUGI.init(sessionConf)
    lastAccessTime = System.currentTimeMillis
    lastIdleTime = lastAccessTime
  }

  def close(): Unit = {
    acquire(true)
    try {
      // Iterate through the opHandles and close their operations
      activeOperationSet.foreach { op => opManager.closeOp(op) }
      activeOperationSet.clear()
    } finally {
      release(true)
      try {
        FileSystem.closeAllForUGI(sessionUGI)
      } catch {
        case ioe: IOException =>
          throw new MLSQLException("Could not clean up file-system handles for UGI: "
            + sessionUGI, ioe)
      }
    }
  }

  def cancelOp(opName: String): Unit = {
    acquire(true)
    try {
      opManager.cancelOp(opName)
      activeOperationSet.remove(opName)
    } finally {
      release(true)
    }
  }

  def visit(): MLSQLSession = {
    acquire(true)
    release(true)
    this
  }

  def execute(f: () => Unit, opId: String, desc: String, operationTimeout: Int): MLSQLOperation = {
    acquire(true)
    val operation = opManager.createOp(this, f, opId, desc, operationTimeout)
    try {
      operation.run()
      activeOperationSet.add(operation.getOpId)
    } catch {
      case e: MLSQLException =>
        operation.close()
        throw e
    } finally {
      release(true)
    }
    operation
  }

  def sql(sql: String, opId: String, desc: String, operationTimeout: Int) = {
    var result: DataFrame = null
    val operation = execute(() => {
      result = sparkSession.sql(sql)
    }, opId, desc, operationTimeout)
    operation.getResult.get()
    result
  }


  def getUserName = username

  def getOpManager = opManager

}
