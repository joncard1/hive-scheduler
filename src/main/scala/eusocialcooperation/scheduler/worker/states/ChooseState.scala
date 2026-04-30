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
import scala.concurrent.Future

/** The state in which the worker chooses its next state and also sleeps for the
  * time configured in {@Worker.loopDelayConfigKey} before making that choice.
  * The choice is made by asking the dispatcher for the current prospects and
  * then using the worker's preference to choose between the explorer state and
  * the exploiter state. The more prospects there are, the more likely the
  * exploiter state is to be chosen, but in a non-linear way that still gives
  * some chance to the explorer state even with many prospects.
  *
  * @param kernelFn
  *   The function that the worker will exploit or explore.
  * @param preference
  *   The worker's preference for being in the ExploiterState or the
  *   ExplorerState.
  * @param dispatcher
  *   The dispatcher to which the worker will send the generated data points.
  * @param config
  *   The configuration object the worker will use to access global parameters.
  */
final case class ChooseState(
    kernelFn: Worker.KernelFn,
    preference: BigDecimal,
    dispatcher: ActorRef[Dispatcher.Command]
)(implicit config: Config)
    extends WorkerState
    with LoggingComponent {
  val loopDelayMs = config.getMilliseconds(Worker.loopDelayConfigKey)
  val weightPerProspect = config.getDouble(Worker.weightPerProspectConfigKey)

  override def apply()(using
      ActorRef[Create[Sample]],
      ActorRef[Create[Point]],
      Scheduler
  ): WorkerState = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global
    implicit val timeout: Timeout = Timeout(3.seconds)

    // Sleep to avoid flogging the processor and to simulate performing a difficult task.
    try Thread.sleep(loopDelayMs)
    catch { case _: InterruptedException => Thread.currentThread().interrupt() }

    def createExplorer(): WorkerState = ExplorerState(
      (BigDecimal(Random.nextDouble()), BigDecimal(Random.nextDouble())),
      kernelFn,
      preference,
      dispatcher
    )
    def createExploiter(prospect: DataPoint[Point]): WorkerState =
      ExploiterState(prospect, preference, kernelFn, dispatcher)

    /** Observe the control variable and compare it to the agent's preference.
      * In this case, the control variable is the queue length of tasks
      * available to run, weighted by "weightPerProspect".
      */
    def observeControlVariable(): WorkerState = {
      val points = Await.result(dispatcher
        .ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)), 3.seconds).points
      points match {
        case s if s.nonEmpty =>
          val value = s.size * weightPerProspect
          logger.trace(
            "Worker has {} prospects, value is {}, preference is {}",
            s.size,
            value,
            preference
          )
          if (value < preference) {
            logger.trace(
              "Creating Explorer because {} * {} = {} is less than {}.",
              s.size,
              weightPerProspect,
              value,
              preference
            )
            createExplorer()
          } else {
              val prospect = Random.shuffle(s).head
              logger.trace(
                "Creating Exploiter because {} * {} = {} is greater than {} for point {}",
                s.size,
                weightPerProspect,
                value,
                preference,
                prospect
              )
              createExploiter(prospect)
            }

        case _ =>
          logger
            .trace("Creating Explorer because no prospects are available.")
          createExplorer()
      }

    }
    
    observeControlVariable()
  }
}
