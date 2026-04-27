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

trait WorkerState extends LoggingComponent {
    /**
      * The weight the worker gives to being an exploiter rather than an explorer.
      */
    val preference: BigDecimal

    /**
      * The dispatcher to communicate with to submit points and request prospects.
      */
    val dispatcher: ActorRef[Dispatcher.Command]

    def apply()(using ActorRef[DataPointActor.Create[Sample]], ActorRef[DataPointActor.Create[Point]],Scheduler):  WorkerState = ???
}