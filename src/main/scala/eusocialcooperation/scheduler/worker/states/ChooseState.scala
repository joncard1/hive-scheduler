package eusocialcooperation.scheduler.worker.states

import eusocialcooperation.scheduler.LoggingComponent
import org.apache.pekko.actor.typed.ActorRef
import eusocialcooperation.scheduler.Dispatcher.Command
import com.typesafe.config.Config
import eusocialcooperation.scheduler.Dispatcher
import eusocialcooperation.scheduler.DataPointActor.Create
import eusocialcooperation.scheduler.Point
import eusocialcooperation.scheduler.Sample
import org.apache.pekko.actor.typed.Scheduler
import eusocialcooperation.scheduler.Worker
import scala.util.Random
import scala.concurrent.Await
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import eusocialcooperation.scheduler.DataPoint

final case class ChooseState(kernelFn: Worker.KernelFn, preference: BigDecimal, dispatcher: ActorRef[Dispatcher.Command])(implicit config: Config) extends WorkerState with LoggingComponent {
    val loopDelayMs = config.getMilliseconds(Worker.loopDelayConfigKey)
    val weightPerProspect = config.getDouble(Worker.weightPerProspectConfigKey)

    override def apply()(using ActorRef[Create[Sample]], ActorRef[Create[Point]], Scheduler): WorkerState = {
        try Thread.sleep(loopDelayMs)
        catch { case _: InterruptedException => Thread.currentThread().interrupt() }
        
        implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
        implicit val timeout: Timeout = Timeout(3.seconds)

        def createExplorer(): WorkerState = ExplorerState((BigDecimal(Random.nextDouble()), BigDecimal(Random.nextDouble())), kernelFn, preference, dispatcher)
        def createExploiter(prospect: DataPoint[Point]): WorkerState = ExploiterState(prospect, preference, kernelFn, dispatcher)

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
                val value = s.size * weightPerProspect
                logger.info(s"Worker has ${s.size} prospects, value is $value, preference is $preference")
                if (value < preference) {
                    logger.info(s"Creating Explorer because ${s.size} * ${weightPerProspect} = $value is less than ${preference}.")
                    createExplorer()
                } else {
                    val prospect = Random.shuffle(s).head
                    logger.info(s"Creating Exploiter because ${s.size} * ${weightPerProspect} = $value is greater than $preference for point $prospect")
                    createExploiter(prospect)
                }

            case _ =>
                logger.info("Creating Explorer because no prospects are available.")
                createExplorer()
        })
        
        Await.result(state, 3.seconds)

    }
  
}
