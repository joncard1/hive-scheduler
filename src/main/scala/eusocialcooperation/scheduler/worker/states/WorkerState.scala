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

object WorkerState {
    val weightPerProspect = 0.2
}

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

    def chooseState(createExplorer: () => WorkerState, createExploiter: (DataPoint[Point]) => WorkerState)(implicit scheduler: Scheduler): WorkerState = {
        // TODO: Maybe make this an implicit parameter
        implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
        implicit val timeout: Timeout = Timeout(3.seconds)

        val state = dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)).map(_.points).map({
            case s if s.nonEmpty => 
                // Adjust it so that the more prospects there are, the more likely the exploiter state is to be chosen, but in a non-linear way that still gives some chance to the explorer state even with many prospects.
                //val adjustedPreference = (1.0 - Math.pow((1.0 - preference).toDouble, s.size))
                // I'm assuming I got this backwards
                /*
                if (Random.nextDouble() < adjustedPreference) createExplorer()
                else
                    createExploiter(Random.shuffle(s).head)
                */
                val value = s.size * WorkerState.weightPerProspect
                logger.info(s"Worker has ${s.size} prospects, value is $value, preference is $preference")
                if (value < preference) {
                    logger.info(s"Creating Explorer because ${s.size} * 0.2 = $value is less than ${preference}.")
                    createExplorer()
                } else {
                    val prospect = Random.shuffle(s).head
                    logger.info(s"Creating Exploiter because ${s.size} * 0.2 = $value is greater than $preference for point $prospect")
                    createExploiter(prospect)
                }

            case _ =>
                logger.info("Creating Explorer because no prospects are available.")
                createExplorer()
        })
        
        Await.result(state, 3.seconds)
    }
}