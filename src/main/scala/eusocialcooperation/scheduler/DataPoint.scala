package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.concurrent.Await
import org.apache.pekko.actor.typed.Scheduler
import scala.concurrent.duration.Duration
import cats.Monad
import cats.Applicative
import scala.reflect.ClassTag

case class DataPointContext(actorName: String, hostname: String)

/** The companion object to DataPoint, which provides the "unit" operation of
  * the monad.
  */
object DataPoint {

  type DataPointMonadCreator = (phase: DataPoint.Phase, context: DataPointContext) ?=> Monad[DataPoint]

  implicit def defaultDataPointMonad (
    using sampleActor: ActorRef[DataPointActor.Create[Sample]]
    , pointActor: ActorRef[DataPointActor.Create[Point]]
    , scheduler: Scheduler
    , timeout: Timeout = Timeout(3.seconds)

  ): DataPointMonadCreator = (phase, context) ?=> new Monad[DataPoint]() {


        override def tailRecM[A, B](a: A)(f: A => DataPoint[Either[A, B]]): DataPoint[B] = ???

        override def flatten[A](ffa: DataPoint[DataPoint[A]]): DataPoint[A] = {
          new DataPoint(ffa.sequenceNumber, ffa.timestamp, ffa.actorName, ffa.phase, ffa.value.value, ffa.parent)
        }

        override def flatMap[A, B](fa: DataPoint[A])(f: A => DataPoint[B]): DataPoint[B] = {
          this.flatten(map(fa)(f))
        }

        override def map[A, B](fa: DataPoint[A])(f: A => B): DataPoint[B] = {
          val newValue = f(fa.value)
          newValue match {
            case value: Point =>
              Await.result(
                  pointActor.ask[DataPoint[Point]](replyTo =>
                    DataPointActor.Create(value, phase, context.actorName, replyTo, Some(fa))
                  ),
                  Duration.Inf
                ).asInstanceOf[DataPoint[B]]
            case value: Sample =>
              Await.result(
                sampleActor.ask[DataPoint[Sample]](replyTo =>
                  DataPointActor.Create(value, phase, context.actorName, replyTo, Some(fa))
                ),
                Duration.Inf
              ).asInstanceOf[DataPoint[B]]
            case _ => 
              throw new Exception("The DataPoint monad can only handle Point and Sample types at this time.")
          }

        }

        override def pure[A](value: A): DataPoint[A] = {
          value match {
            case value : Point =>
              Await.result(
                pointActor.ask[DataPoint[Point]](replyTo =>
                  DataPointActor.Create(value, phase, context.actorName, replyTo, None)
                ),
                Duration.Inf
              ).asInstanceOf[DataPoint[A]]
            case value: Sample =>
              Await.result(
                sampleActor.ask[DataPoint[Sample]](replyTo =>
                  DataPointActor.Create(value, phase, context.actorName, replyTo, None)  
                ),
                Duration.Inf
              ).asInstanceOf[DataPoint[A]]
            case _ => 
              throw new Exception("The DataPoint monad can only handle Point and Sample types at this time.")
          }
        }
  }

  /** An enum to designate the phases in which a DataPoint can be generated.
    */
  enum Phase:
    case ExplorerStart, ChooseState, Explorer, Exploiter

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
case class DataPoint[A](
    val sequenceNumber: Long,
    val timestamp: Long,
    val actorName: String,
    val phase: DataPoint.Phase,
    val value: A,
    val parent: Option[DataPoint[?]] = None
  )
