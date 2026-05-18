package eusocialcooperation.scheduler.datapoint

import scala.concurrent.duration.DurationInt
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** The companion object to DataPoint, which provides the "unit" operation of
  * the monad.
  */
object DataPoint {

  type DataPointUnit[A] =
    (A) => (Phase, String, Option[DataPoint[?]]) ?=> DataPoint[A]

  /** An enum to designate the phases in which a DataPoint can be generated.
    */
  enum Phase:
    case Explorer, Exploiter
}

/** This represents a monad that tracks the metadata containing the
  *
  * @param sequenceNumber
  *   The sequence number that this data point was recorded. I am interested in
  *   environmental conditions when the point was generated. To create one of
  *   these objects, use one of the "unit" functions provided by the DataPoint
  *   companion object. what order all of the points were created in across the
  *   different threads.
  * @param timestamp
  *   The time at which the data point was created.
  * @param actorName
  *   This is really the thread name, in practice, but it allows grouping by
  *   which worker generated the point.
  * @param phase
  *   The state the worker was in when the point was generated, explorer or
  *   exploiter.
  * @param value
  *   The value contained in the monad.
  * @param parent
  *   The value that led to the generation of the value. In general, the
  *   prospect point that the exploiter is working off of when it generated the
  *   sample point. This allows grouping by precedent to validate whether points
  *   with high prospects are properly being favored for exploitation.
  */
class DataPoint[A](
    val sequenceNumber: Long,
    val timestamp: Long,
    val actorName: String,
    val phase: DataPoint.Phase,
    val value: A,
    val parent: Option[DataPoint[?]] = None
) {
  def flatMap[B](f: A => DataPoint[B]): DataPoint[B] = {
    f(value)
  }

  def map[B](f: A => B)(implicit
      dpb: DataPoint.DataPointUnit[B],
      phase: DataPoint.Phase,
      actorName: String
  ): DataPoint[B] = {
    given Option[DataPoint[?]] = Some(this)
    dpb(
      f(value)
    )
  }

  // TODO: I didn't implement map because it wasn't clear whether the "correct" solution was to generate a new DataPoint or to use the metadata of the original DataPoint, or to generate new DataPoint and use the original DataPoint as the parent, etc. Since it never came up, it never got implemented. (It was never necessary to generate a List[Sample] from List[Point] of prospects, for example. Simply having one prospect didn't really require using map, but the need to track precedence came later and perhaps that use case suggests the proper implementation of map).
}
