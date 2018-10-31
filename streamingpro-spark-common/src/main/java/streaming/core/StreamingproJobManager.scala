package streaming.core

import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import org.apache.log4j.Logger
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession

/**
  * Created by allwefantasy on 15/5/2017.
  */
object StreamingproJobManager {
  val logger = Logger.getLogger(classOf[StreamingproJobManager])
  private[this] var _jobManager: StreamingproJobManager = _
  private[this] val _executor = Executors.newFixedThreadPool(100)

  def shutdown = {
    _executor.shutdownNow()
    _jobManager.shutdown
  }

  def init(sc: SparkContext, initialDelay: Long = 30, checkTimeInterval: Long = 5) = {
    synchronized {
      if (_jobManager == null) {
        logger.info(s"JobCanceller Timer  started with initialDelay=${initialDelay} checkTimeInterval=${checkTimeInterval}")
        _jobManager = new StreamingproJobManager(sc, initialDelay, checkTimeInterval)
        _jobManager.run
      }
    }
  }

  def run(session: SparkSession, job: StreamingproJobInfo, f: () => Unit): Unit = {
    try {
      if (_jobManager == null) {
        f()
      } else {
        session.sparkContext.setJobGroup(job.groupId, job.jobName, true)
        _jobManager.groupIdToStringproJobInfo.put(job.groupId, job)
        f()
      }
    } finally {
      handleJobDone(job.groupId)
      session.sparkContext.clearJobGroup()
    }
  }

  def asyncRun(session: SparkSession, job: StreamingproJobInfo, f: () => Unit) = {
    // TODO: (fchen) 改成callback
    _executor.execute(new Runnable {
      override def run(): Unit = {
        try {
          _jobManager.groupIdToStringproJobInfo.put(job.groupId, job)
          session.sparkContext.setJobGroup(job.groupId, job.jobName, true)
          f()
        } finally {
          handleJobDone(job.groupId)
          session.sparkContext.clearJobGroup()
        }
      }
    })
  }

  def getStreamingproJobInfo(owner: String,
                             jobType: String,
                             jobName: String,
                             jobContent: String,
                             timeout: Long): StreamingproJobInfo = {
    val startTime = System.currentTimeMillis()
    val groupId = _jobManager.nextGroupId.incrementAndGet().toString
    StreamingproJobInfo(owner, jobType, jobName, jobContent, groupId, startTime, timeout)
  }

  def getJobInfo: Map[String, StreamingproJobInfo] =
    _jobManager.groupIdToStringproJobInfo.asScala.toMap

  def killJob(groupId: String): Unit = {
    _jobManager.cancelJobGroup(groupId)
  }

  private def handleJobDone(groupId: String): Unit = {
    _jobManager.groupIdToStringproJobInfo.remove(groupId)

  }
}

class StreamingproJobManager(sc: SparkContext, initialDelay: Long, checkTimeInterval: Long) {
  val groupIdToStringproJobInfo = new ConcurrentHashMap[String, StreamingproJobInfo]()
  val nextGroupId = new AtomicInteger(0)
  val logger = Logger.getLogger(classOf[StreamingproJobManager])
  val executor = Executors.newSingleThreadScheduledExecutor()

  def run = {
    executor.scheduleWithFixedDelay(new Runnable {
      override def run(): Unit = {
        groupIdToStringproJobInfo.foreach { f =>
          val elapseTime = System.currentTimeMillis() - f._2.startTime
          if (f._2.timeout > 0 && elapseTime >= f._2.timeout) {
            cancelJobGroup(f._1)
          }
        }
      }
    }, initialDelay, checkTimeInterval, TimeUnit.SECONDS)
  }

  def cancelJobGroup(groupId: String): Unit = {
    logger.info("JobCanceller Timer cancel job group " + groupId)
    sc.cancelJobGroup(groupId)
    groupIdToStringproJobInfo.remove(groupId)
  }

  def shutdown = {
    executor.shutdownNow()
  }
}

case object StreamingproJobType {
  val SCRIPT = "script"
  val SQL = "sql"
  val STREAM = "stream"
}

case class StreamingproJobInfo(
                                owner: String,
                                jobType: String,
                                jobName: String,
                                jobContent: String,
                                groupId: String,
                                startTime: Long,
                                timeout: Long)
