package org.apache.mesos.chronos.scheduler.jobs

import java.util.logging.Logger

import org.apache.mesos.chronos.scheduler.state.PersistenceStore
import org.apache.mesos.chronos.utils.{JobDeserializer, JobSerializer}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.google.common.base.{Charsets, Joiner}
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, Period, Seconds}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * @author Florian Leibert (flo@leibert.de)
 */
object JobUtils {

  val jobNamePattern = """([\w\s\.#_-]+)""".r
  val stats = new mutable.HashMap[String, DescriptiveStatistics]()
  val maxValues = 100
  //The object mapper, which is, according to the docs, Threadsafe once configured.
  val objectMapper = new ObjectMapper
  val mod = new SimpleModule("JobModule")
  private[this] val log = Logger.getLogger(getClass.getName)

  mod.addSerializer(classOf[BaseJob], new JobSerializer)
  mod.addDeserializer(classOf[BaseJob], new JobDeserializer)
  objectMapper.registerModule(mod)

  def toBytes[T <: BaseJob](job: T): Array[Byte] =
    objectMapper.writeValueAsString(job).getBytes(Charsets.UTF_8)

  def fromBytes(data: Array[Byte]): BaseJob = {
    //TODO(FL): Fix this, as it is very inefficient since we're parsing twice.
    //          Link to article, doing this by creating a deserializer handling polymorphism nicer (but more code):
    //          http://programmerbruce.blogspot.com.es/2011/05/deserialize-json-with-jackson-into.html
    val strData = new String(data, Charsets.UTF_8)
    val map = objectMapper.readValue(strData, classOf[java.util.Map[String, _]])

    if (map.containsKey("parents"))
      objectMapper.readValue(strData, classOf[DependencyBasedJob])
    else if (map.containsKey("schedule"))
      objectMapper.readValue(strData, classOf[ScheduleBasedJob])
    else
      objectMapper.readValue(strData, classOf[BaseJob])
  }

  def isValidJobName(jobName: String): Boolean = {
    jobName match {
      case jobNamePattern(part) => true
      case _ => false
    }
  }

  def isValidURIDefinition(baseJob: BaseJob) = baseJob.uris.isEmpty || baseJob.fetch.isEmpty  // when you leave the deprecated one, then it should be empty

  //TODO(FL): Think about moving this back into the JobScheduler, though it might be a bit crowded.
  def loadJobs(scheduler: JobScheduler, store: PersistenceStore) {
    //TODO(FL): Create functions that map strings to jobs
    val scheduledJobs = new ListBuffer[ScheduleBasedJob]
    val dependencyBasedJobs = new ListBuffer[DependencyBasedJob]

    val jobs = store.getJobs

    jobs.foreach {
      case d: DependencyBasedJob => dependencyBasedJobs += d
      case s: ScheduleBasedJob => scheduledJobs += s
      case x: Any =>
        throw new IllegalStateException("Error, job is neither ScheduleBased nor DependencyBased:" + x.toString)
    }

    log.info("Registering jobs:" + scheduledJobs.size)
    scheduler.registerJob(scheduledJobs.toList)

    //We cannot simply register
    dependencyBasedJobs.foreach({ x =>
      log.info("Adding vertex in the vertex map:" + x.name)
      scheduler.jobGraph.addVertex(x)
    })

    dependencyBasedJobs.foreach {
      x =>
        log.info("mapping:" + x)
        import scala.collection.JavaConversions._
        log.info("Adding dependencies for %s -> [%s]".format(x.name, Joiner.on(",").join(x.parents)))

        scheduler.jobGraph.parentJobsOption(x) match {
          case None =>
            log.warning(s"Coudn't find all parents of job ${x.name}... dropping it.")
            scheduler.jobGraph.removeVertex(x)
          case Some(parentJobs) =>
            parentJobs.foreach {
              //Setup all the dependencies
              y: BaseJob =>
                scheduler.jobGraph.addDependency(y.name, x.name)
            }
        }
    }
  }

  def makeScheduleStream(job: ScheduleBasedJob, dateTime: DateTime) = {
    Iso8601Expressions.parse(job.schedule, job.scheduleTimeZone) match {
      case Some((_, scheduledTime, _)) =>
        if (scheduledTime.plus(job.epsilon).isBefore(dateTime)) {
          skipForward(job, dateTime)
        } else {
          Some(new ScheduleStream(job.schedule, job.name, job.scheduleTimeZone))
        }
      case None =>
        None
    }
  }

  def skipForward(job: ScheduleBasedJob, dateTime: DateTime): Option[ScheduleStream] = {
    Iso8601Expressions.parse(job.schedule, job.scheduleTimeZone) match {
      case Some((rec, start, per)) =>
        val skip = calculateSkips(dateTime, start, per)
        if (rec == -1) {
          val nStart = start.plus(per.multipliedBy(skip))
          log.warning("Skipped forward %d iterations, modified start from '%s' to '%s"
            .format(skip, start.toString(DateTimeFormat.fullDate),
              nStart.toString(DateTimeFormat.fullDate)))
          Some(new ScheduleStream(Iso8601Expressions.create(rec, nStart, per), job.name, job.scheduleTimeZone))
        } else if (rec < skip) {
          log.warning("Filtered job as it is no longer valid.")
          None
        } else {
          val nRec = rec - skip
          val nStart = start.plus(per.multipliedBy(skip))
          log.warning("Skipped forward %d iterations, iterations is now '%d' , modified start from '%s' to '%s"
            .format(skip, nRec, start.toString(DateTimeFormat.fullDate),
              nStart.toString(DateTimeFormat.fullDate)))
          Some(new ScheduleStream(Iso8601Expressions.create(nRec, nStart, per), job.name, job.scheduleTimeZone))
        }
      case None =>
        None
    }
  }

  /**
   * Calculates the number of skips needed to bring the job start into the future
   */
  protected def calculateSkips(dateTime: DateTime, jobStart: DateTime, period: Period): Int = {
    // If the period is at least a month, we have to actually add the period to the date
    // until it's in the future because a month-long period might have different seconds
    if (period.getMonths >= 1) {
      var skips = 0
      var newDate = new DateTime(jobStart)
      while (newDate.isBefore(dateTime)) {
        newDate = newDate.plus(period)
        skips += 1
      }
      skips
    } else if (period.toStandardSeconds.getSeconds > 0) {
      Seconds.secondsBetween(jobStart, dateTime).getSeconds / period.toStandardSeconds.getSeconds
    } else {
      0 // no skip needed for non-repetitive schedule
    }
  }

  def getJobWithArguments(job : BaseJob, arguments: String): BaseJob = {
    val commandWithArgs = job.command + " " + arguments
    job match {
      case j: DependencyBasedJob => j.copy(command = commandWithArgs)
      case j: ScheduleBasedJob => j.copy(command = commandWithArgs)
    }
  }
}
