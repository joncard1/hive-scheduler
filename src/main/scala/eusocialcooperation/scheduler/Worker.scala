package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Random
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import eusocialcooperation.scheduler.worker.states.{ExplorerState, WorkerState}
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.receptionist.Receptionist.Listing
import eusocialcooperation.scheduler.distributions.DistributionStrategy
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import scala.util.Try
import eusocialcooperation.scheduler.datapoint.DataPointActor
import eusocialcooperation.scheduler.datapoint.DataPoint
import eusocialcooperation.scheduler.datapoint.PekkoDataPoint
import eusocialcooperation.scheduler.dispatcher.Dispatcher
import scala.concurrent.duration.FiniteDuration
import eusocialcooperation.scheduler.datapoint.DataPointContext

/** Actor that controls the worker threads.
  */
object Worker {

  /** The key used to group the worker configuration variables.
    */
  val workersConfigKey = "workers"

  /** The key used to retrieve from the workers group the delay that each worker
    * waits between tasks.
    */
  val loopDelayConfigKey = "loopDelay"

  /** The key used to retrieve from the workers group the weight to use to scale
    * the size of the list of prospects to be comparable to the worker's
    * preference.
    */
  val weightPerProspectConfigKey = "weightPerProspect"

  /** The function signature for the task the workers are exploring.
    */
  type KernelFn = (BigDecimal, BigDecimal) => BigDecimal

  /** The possible states the workers can be in.
    *
    * This does not include ChooseState, because there's no point.
    */
  // TODO: This is a duplicate of DataPoint.Phase and these should be consolidated.
  sealed private trait Phase
  private case object Explorer extends Phase
  private case object Exploiter extends Phase

  /** Generic class for messages that can be served by this actor.
    */
  sealed trait Command

  /** Message instructing the actor to stop the associated worker thread.
    *
    * @param replyTo
    *   The actor to whom to send a message confirming the thread stopped.
    */
  case class Stop(replyTo: ActorRef[Dispatcher.WorkerStopped]) extends Command

  /** A message that confirms to the worker the thread stopped, allowing the
    * actor to send the response to the calling actor that the work has stopped.
    *
    * This should only be sent by the Worker actor to itself.
    *
    * @param replyTo
    *   The reference to send the confirmation that the worker has been stopped.
    */
  case class WorkerThreadStopped(
      result: Try[Unit],
      replyTo: ActorRef[Dispatcher.WorkerStopped]
  ) extends Command

  /** A message containing the listing of actors that create DataPoint monads.
    *
    * This should only be sent by the receptionist in response to a subscription
    * by the Worker actor.
    *
    * @param actors
    *   The listing of actors that create the requested type of DataPoint
    *   monads.
    */
  private[scheduler] final case class DPActorListing(
      actors: Listing
  ) extends Command

  /** Constructs the requested worker actor.
    *
    * @param kernelFn
    *   The function the workers are exploring.
    * @param dispatcher
    *   The dispatcher to whom the worker should send prospects and request.
    *   prospects from.
    * @param duration
    *   The duration the workers should operate.
    * @param config
    *   The configuration object used to provide configuration parameters.
    * @param mdc
    *   The logging context information that allows the logs to write to the
    *   correct locations.
    * @param sampleUnit
    *   The unit operation to lift [[Sample]] objects to DataPoint[Sample]
    * @param prospectUnit
    *   The unit operation to lift [[Point]] objects to DataPoint[Point]
    * @return
    *   The actor behavior used by Apache Pekko.
    */
  def apply(
      kernelFn: KernelFn,
      dispatcher: ActorRef[Dispatcher.Command],
      duration: FiniteDuration
  )(implicit config: Config
    , mdc: Map[String, String]
    , sampleUnit: DataPoint.DataPointUnit[Sample]
    , prospectUnit: DataPoint.DataPointUnit[Point]
  ): Behavior[Command] = apply(
    kernelFn,
    dispatcher,
    duration,
    defaultWorkerThreadFactory
  )
  
  /** Constructs a worker to explore the function.
   * 
   * This version allows the injection of a testable worker through the worker thread factory.
    * 
    *
    * @param kernelFn
    *   The function the workers are exploring.
    * @param dispatcher
    *   The dispatcher to whom the worker should send prospects and request.
    *   prospects from.
    * @param duration
    *   The duration the workers should operate.
    * @param workerThreadFactory
    *   A function to create a worker thread for testing.
    * @param config
    *   The configuration object used to provide configuration parameters.
    * @param mdc
    *   The logging context information that allows the logs to write to the
    *   correct locations.
    * @param sampleUnit
    *   The unit operation to lift [[Sample]] objects to DataPoint[Sample]
    * @param prospectUnit
    *   The unit operation to lift [[Point]] objects to DataPoint[Point]
    * @return
    */
  private[scheduler] def apply(
      kernelFn: KernelFn,
      dispatcher: ActorRef[Dispatcher.Command],
      duration: FiniteDuration,
      workerThreadFactory: WorkerThreadFactory
  )(implicit config: Config
    , mdc: Map[String, String]
    , sampleUnit: DataPoint.DataPointUnit[Sample]
    , prospectUnit: DataPoint.DataPointUnit[Point]
  ): Behavior[Command] =
    Behaviors.withMdc(mdc)(
      Behaviors.setup { implicit context =>
        given Scheduler = context.system.scheduler
        given ExecutionContext = context.system.executionContext
        given Config = config.getConfig(workersConfigKey)

        val running = new AtomicBoolean(true)
        val strategy = DistributionStrategy()
        val preference = strategy()

        context.log.trace(
          "Worker starting thread with preference: {}",
          preference
        )
        context.scheduleOnce(duration, context.self, Stop(dispatcher))
        val thread = workerThreadFactory(kernelFn, dispatcher, preference, running) andThen (res => {
          dispatcher ! Dispatcher.WorkerStopped(context.self, res)
        })
        active(running, thread, sampleUnit, prospectUnit)
      }
    )

  type WorkerThreadFactory = (
      KernelFn,
      ActorRef[Dispatcher.Command],
      BigDecimal,
      AtomicBoolean
  ) => (config: Config, context: ActorContext[Command], dpaSample: DataPoint.DataPointUnit[Sample], dpaPoint: DataPoint.DataPointUnit[Point], mdc: Map[String, String]) ?=> Future[Unit]

  // TODO: Not sure I like doing this with Future instead of Thread. It's probably more efficient, generally, but I think it's confusing the traceability of the workers. I suspect I'd have to add another environment parameter for the worker name, because the Futures are being run on the same threads.
  def defaultWorkerThreadFactory(
    kernelFn: KernelFn,
    dispatcher: ActorRef[Dispatcher.Command],
    preference: BigDecimal,
    running: AtomicBoolean
  )(implicit
      config: Config,
      context: ActorContext[Command],
      sampleUnit: DataPoint.DataPointUnit[Sample],
      pointUnit: DataPoint.DataPointUnit[Point],
      mdc: Map[String, String]
  ) = {
    import context.executionContext
    given Scheduler = context.system.scheduler
    Future {
      val logger = LoggerFactory.getLogger(s"eusocialcooperation.scheduler.Worker.${context.self.path.name}")
      mdc.map { case (key, value) => MDC.put(key, value) }

      try {
        var phase: WorkerState = ExplorerState(
          (
            BigDecimal(Random.nextDouble()),
            BigDecimal(Random.nextDouble())
          ),
          kernelFn,
          preference,
          dispatcher
        )
        while (running.get()) {
          given dpSampleUnit: DataPoint.DataPointUnit[Sample] = sampleUnit
          given dpPointUnit: DataPoint.DataPointUnit[Point] = pointUnit
          val actorName: String = context.self.path.toString
          val hostName = java.net.InetAddress.getLocalHost.getHostName
          given DataPointContext = DataPointContext(phase.phase, hostName, actorName)
          phase = phase()
        }
      } catch {
        case e: Exception =>
          logger.error(s"worker ${context.self.path.toString} encountered error in worker thread", e)
          throw e
      }
    }
  }

  /** Represents the principle state in which the actor operates, waiting for
    * instructions to stop the worker thread it manages.
    *
    * @param running
    *   A reference to a boolean value that the worker thread monitors. When it
    *   is set to false, the thread runs to completion.
    * @param thread
    *   The worker thread that this actor manages.
    * @param sampleActorRef
    *   The DataPoint[Sample] actor used by the worker thread.
    * @param pointActorRef
    *   The DataPoint[Point] actor used by the worker thread.
    * @param context
    *   The context in which the actor was created, used to access Apache Pekko
    *   utilities.
    * @param config
    *   The configuration from which the system gets configuration parameters.
    * @return
    *   The actor behavior used by Apache Pekko.
    */
  private def active(
      running: AtomicBoolean,
      thread: Future[Unit],
      sampleActorRef: DataPoint.DataPointUnit[Sample],
      pointActorRef: DataPoint.DataPointUnit[Point]
  )(implicit
      context: ActorContext[Command],
      config: Config
  ): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case Stop(replyTo) =>
        given ExecutionContext = context.system.executionContext
        context.log.debug(
          s"Worker ${context.self.path.name} stopping from message."
        )
        running.set(false)
        context.pipeToSelf(thread)(WorkerThreadStopped(_, replyTo))
        Behaviors.same
      case WorkerThreadStopped(result, replyTo) =>
        context.log.info(
          s"Worker ${context.self.path.name} stopped successfully."
        )
        replyTo ! Dispatcher.WorkerStopped(context.self, result)
        Behaviors.stopped
      case DPActorListing(actors)
          if actors.isForKey(DataPointActor.DataPointActorKey[Sample]) && actors
            .serviceInstances(DataPointActor.DataPointActorKey[Sample])
            .nonEmpty =>
        active(
          running,
          thread,
          PekkoDataPoint.getActorDataPointUnit(
            actors
              .serviceInstances(DataPointActor.DataPointActorKey[Sample])
              .head,
            context.system.scheduler,
          ),
          pointActorRef
        )
      case DPActorListing(actors)
          if actors.isForKey(DataPointActor.DataPointActorKey[Point]) && actors
            .serviceInstances(DataPointActor.DataPointActorKey[Point])
            .nonEmpty =>
        active(
          running,
          thread,
          sampleActorRef,
          PekkoDataPoint.getActorDataPointUnit(
            actors
              .serviceInstances(DataPointActor.DataPointActorKey[Point])
              .head,
            context.system.scheduler,
          )
        )
      case _ =>
        Behaviors.same
    }
}
