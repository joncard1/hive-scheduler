package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.concurrent.Await
import org.apache.pekko.actor.typed.Scheduler
import scala.concurrent.duration.Duration

/** The companion object to DataPoint, which provides the "unit" operation of
  * the monad.
  */
object DataPoint {

  /** An enum to designate the phases in which a DataPoint can be generated.
    */
  enum Phase:
    case Explorer, Exploiter

  /** The primary factory method for lifting a value to a DataPoint[?]. The
    * value itself is provided, but there are a number of environmental data
    * sources that need to be made available for this to operate.
    *
    * This factory method uses an Apache Pekko actor to create the DataPoint in
    * order to provide the sequence number, because I am interested in the order
    * the points are created in. An alternative method, such as insertion into a
    * database, could be an alternative.
    *
    * My intent for this class is that, as the reporting needs of the
    * application evolved, it would not be necessary to make major structural
    * changes to the rest of the algorithm to keep up with them, slowing
    * development and confusing the human reader. Instead, the compiler should
    * be able to adapt to additions or subtractions from the list of implicit
    * parameters with only the occaisional addition of a "given" in the code
    * that is easier to ignore than changes to the parameter list of a function.
    * This way, changes to the implementation that are only of use to the
    * reporting system, such as the use of a database or an actor to provide the
    * sequence number, will be as low-impact as possible, although it isn't
    * completely invisible.
    *
    * @param value
    *   The value to be lifted into the DataPoint monad.
    * @param dpa
    *   The actor used to create the DataPoint.
    * @param scheduler
    *   The Apache Pekko scheduler used to coordinate messages, since this
    *   constructor requires a return message.
    * @param phase
    *   The phase in which the point is being generated.
    * @param parent
    *   The precedent data that led to the generation of this data point, if
    *   applicable.
    * @return
    *   The DataPoint containing the value, with the metadata provided by the
    *   implicit parameters and the actor message.
    */
  def apply[A](value: A)(implicit
      dpa: ActorRef[DataPointActor.Create[A]],
      scheduler: Scheduler,
      phase: Phase,
      parent: Option[DataPoint[?]] = None
  ): DataPoint[A] = {
    implicit val timeout: Timeout = Timeout(3.seconds)
    val worker: String = Thread.currentThread().getName

    // Using Inf because the pekko ask function takes a timeout, and it's specified above.
    Await.result(
      dpa.ask[DataPoint[A]](replyTo =>
        DataPointActor.Create(value, phase, worker, replyTo, parent)
      ),
      Duration.Inf
    )
  }
}

/** This represents a monad that tracks the metadata containing the
  * environmental conditions when the point was generated. This implements the
  * "bind" function of the monad, and the companion object's apply method should
  * be used as the "unit" function. (Translation: this constructor is for
  * testing purposes only and may be made private to the package in future. Use
  * DataPoint(...), not new DataPoint(...))
  *
  * @param sequenceNumber
  *   The sequence number that this data point was recorded. I am interesting in
  *   what order all of the points were created in across the different threads.
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

  // TODO: I didn't implement map because it wasn't clear whether the "correct" solution was to generate a new DataPoint or to use the metadata of the original DataPoint, or to generate new DataPoint and use the original DataPoint as the parent, etc. Since it never came up, it never got implemented. (It was never necessary to generate a List[Sample] from List[Point] of prospects, for example. Simply having one prospect didn't really require using map, but the need to track precedence came later and perhaps that use case suggests the proper implementation of map).
}
