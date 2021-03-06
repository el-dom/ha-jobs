package de.kaufhof.hajobs

import java.util.UUID

import com.datastax.driver.core.utils.UUIDs
import de.kaufhof.hajobs.JobResult.JobResult
import de.kaufhof.hajobs.JobState.JobState
import de.kaufhof.hajobs.utils.EnumJsonSupport
import org.joda.time.{DateTime, Period}
import org.joda.time.format.{ISODateTimeFormat, PeriodFormat}
import play.api.libs.json.{JsValue, _}

import scala.language.implicitConversions
import scala.util.control.NonFatal

/**
  * Represents Status of Import Jobs
  * @param jobId should represent the Job start time as time-based UUID (type 1)
  */
case class JobStatus(triggerId: UUID, jobType: JobType, jobId: UUID, jobState: JobState, jobResult: JobResult, jobStatusTs: DateTime,
                     content: Option[JsValue] = None)

object JobState extends Enumeration {
  type JobState = Value

  // remember to add new values to stateResultMapping as well
  val Running = Value("RUNNING")
  val Preparing = Value("PREPARING")
  val Finished = Value("FINISHED")
  val Failed = Value("FAILED")
  val Canceled = Value("CANCELED")
  val Dead = Value("DEAD")
  val Warning = Value("WARNING")
  val Skipped = Value("SKIPPED")
  val NoActionNeeded = Value("NO_ACTION_NEEDED")

  implicit val enumRead: Reads[JobState] = EnumJsonSupport.enumReads(JobState)
  implicit val enumWrite: Writes[JobState] = EnumJsonSupport.enumWrites

}

object JobResult extends Enumeration {
  type JobResult = Value
  val Pending = Value("PENDING")
  val Success = Value("SUCCESS")
  val Failed = Value("FAILED")

  implicit val enumRead: Reads[JobResult] = EnumJsonSupport.enumReads(JobResult)
  implicit val enumWrite: Writes[JobResult] = EnumJsonSupport.enumWrites
}

/**
 * The job type, identified by its name, specifies a LockType.
 *
 * JobTypes do not override <code>toString</code> so that there can more useful log output when
 * a jobType is just printed. When storing a reference to a JobType e.g. in C*, the name property
 * must be used instead of toString (like it's done for Enumerations).
 */
case class JobType(name: String, lockType: LockType)

object JobType {

  implicit def jobTypeReads(implicit jobTypes: JobTypes): Reads[JobType] = new Reads[JobType] {
    def reads(json: JsValue): JsResult[JobType] = json match {
      case JsString(s) => jobTypes(s).map(JsSuccess(_)).getOrElse(JsError(s"No JobType found with name '$s'"))
      case _ => JsError("String value expected")
    }
  }

  implicit val jobTypeWrites: Writes[JobType] = new Writes[JobType] {
    def writes(v: JobType): JsValue = JsString(v.name)
  }

}

case class JobTypes(all: Iterable[JobType]) {

  private val _all = all.toSeq :+ JobTypes.JobSupervisor

  /**
   * Resolves a JobType by name. Compares built in JobType and given JobTypes.
   */
  def apply(name: String): Option[JobType] = {
    _all.find(_.name == name)
  }

}

object JobTypes {

  object JobSupervisor extends JobType("supervisor", lockType = LockTypes.JobSupervisorLock)

  def apply(jobTypes: JobType*): JobTypes = new JobTypes(jobTypes.toList)

}

object JobStatus {

  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads._
  import play.api.libs.json._

  /**
   * Override the default DateTime json Format (just prints the unix timestamp)
   * with a one that uses a more readable form (ISO8601).
   * The Reads also supports the former timestamp (millis) based format, so that
   * old values read from the storage don't fail the JobStatus Reads.
   */
  private implicit val iso8601DateTimeFormat = new Format[DateTime] {
    override def writes(o: DateTime): JsValue = JsString(o.toString(ISODateTimeFormat.dateTime()))

    override def reads(json: JsValue): JsResult[DateTime] = json match {
      case JsString(value) =>
        try {
          JsSuccess(ISODateTimeFormat.dateTime().parseDateTime(json.as[String]))
        }
        catch {
          case NonFatal(e) => JsError(s"Could not parse jobStatusTs: $e")
        }
      case JsNumber(value) => JsSuccess(new DateTime(value.toLong))
      case default =>
        JsError(s"Unexpected value for jobStatusTs: $json")
    }
  }

  private implicit val periodFormat = new Writes[Period] {
    override def writes(o: Period): JsValue = JsString(o.toString(PeriodFormat.wordBased()))
  }

  implicit def jobStatusReads(implicit jobTypes: JobTypes): Reads[JobStatus] = Json.reads[JobStatus]

  implicit def jobStatusWrites = (
    (__ \ "triggerId").write[UUID] and
      (__ \ "jobType").write[JobType] and
      (__ \ "jobId").write[UUID] and
      (__ \ "jobState").write[JobState] and
      (__ \ "jobResult").write[JobResult] and
      (__ \ "jobStatusTs").write[DateTime] and
      (__ \ "content").writeNullable[JsValue] and
      (__ \ "startTime").writeNullable[DateTime] and
      (__ \ "duration").writeNullable[Period]
    ).apply(asJsonTuple _)


  private def asJsonTuple(j: JobStatus): (UUID, JobType, UUID, JobState, JobResult, DateTime, Option[JsValue], Option[DateTime], Option[Period]) = {

    val maybeStartTime: Option[DateTime] = j.jobId.version() match {
      case 1 => Some(new DateTime(UUIDs.unixTimestamp(j.jobId)))
      case _ => None
    }

    val maybeDuration: Option[Period] = maybeStartTime.map { startTime =>
      new Period(startTime, j.jobStatusTs)
    }
    (j.triggerId, j.jobType, j.jobId, j.jobState, j.jobResult, j.jobStatusTs, j.content, maybeStartTime, maybeDuration)
  }

  private val stateResultMapping = Map[JobState, JobResult](
    JobState.Running -> JobResult.Pending,
    JobState.Preparing -> JobResult.Pending,
    JobState.Finished -> JobResult.Success,
    JobState.Failed -> JobResult.Failed,
    JobState.Canceled -> JobResult.Failed,
    JobState.Dead -> JobResult.Failed,
    JobState.Warning -> JobResult.Success,
    JobState.Skipped -> JobResult.Success,
    JobState.NoActionNeeded -> JobResult.Success
  )

  def stateToResult(state: JobState): JobResult = stateResultMapping(state)
}