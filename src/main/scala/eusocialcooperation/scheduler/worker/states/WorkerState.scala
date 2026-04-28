package eusocialcooperation.scheduler.worker.states

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern._

import scala.util.Random
import scala.concurrent.Future
import scala.concurrent.Await
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import eusocialcooperation.scheduler._
import com.typesafe.config.Config

/** The state a worker thread can be in as it performs its work. The worker
  * thread represents each agent in the algorithm and it can transition between
  * exploring, where it looks for promising points around which to exploit, and
  * exploting, where it chooses a prospect, selects a nearby point, and
  * systematically samples in a grid around that point.
  */
trait WorkerState extends LoggingComponent {

  /** The weight the worker gives to being an exploiter rather than an explorer.
    */
  val preference: BigDecimal

  /** The dispatcher to communicate with to submit points and request prospects.
    */
  val dispatcher: ActorRef[Dispatcher.Command]

  /** Execute the proper behavior for this state. The state returned is the next
    * state the worker should be in.
    *
    * @param sampleRef
    *   The Apache Pekko actor used to create Sample data points.
    * @param pointRef
    *   The Apache Pekko actor used to create Point data points.
    * @param scheduler
    *   The Apache Pekko scheduler used to schedule messages to be sent in the
    *   future.
    * @return
    *   The next state the worker should be in after executing the behavior for
    *   this state.
    */
  def apply()(using
      ActorRef[DataPointActor.Create[Sample]],
      ActorRef[DataPointActor.Create[Point]],
      Scheduler
  ): WorkerState
}
