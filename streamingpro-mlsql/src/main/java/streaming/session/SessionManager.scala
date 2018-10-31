package streaming.session

import java.util.concurrent.ConcurrentHashMap

import org.apache.spark.sql.SparkSession
import streaming.log.Logging

/**
  * Created by allwefantasy on 1/6/2018.
  */
class SessionManager(rootSparkSession: SparkSession) extends Logging {

  private[this] val identifierToSession = new ConcurrentHashMap[SessionIdentifier, MLSQLSession]
  private[this] var shutdown: Boolean = false
  private[this] val opManager = new MLSQLOperationManager(60)


  def start(): Unit = {
    opManager.start()
    SparkSessionCacheManager.startCacheManager()
  }

  def stop(): Unit = {
    shutdown = true
    SparkSessionCacheManager.get.stop()
  }

  def openSession(
                   username: String,
                   password: String,
                   ipAddress: String,
                   sessionConf: Map[String, String],
                   withImpersonation: Boolean): SessionIdentifier = {

    val session = new MLSQLSession(
      username,
      password,
      ipAddress,
      withImpersonation,
      this, opManager
    )
    log.info(s"Opening session for $username")
    session.open(sessionConf)

    identifierToSession.put(SessionIdentifier(username), session)
    SessionIdentifier(username)
  }

  def getSession(sessionIdentifier: SessionIdentifier): MLSQLSession = {
    synchronized {
      var session = identifierToSession.get(sessionIdentifier)
      if (session == null) {
        openSession(sessionIdentifier.owner, "", "", Map(), true)
      }
      session = identifierToSession.get(sessionIdentifier)
      //to record active times
      session.visit()
    }
  }

  def closeSession(sessionIdentifier: SessionIdentifier) {
    val session = identifierToSession.remove(sessionIdentifier)
    if (session == null) {
      throw new MLSQLException(s"Session $sessionIdentifier does not exist!")
    }
    val sessionUser = session.getUserName
    SparkSessionCacheManager.get.decrease(sessionUser)
    session.close()
  }

  def getOpenSessionCount: Int = identifierToSession.size
}


