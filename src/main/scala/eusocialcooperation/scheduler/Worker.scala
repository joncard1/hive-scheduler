package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.receptionist.{Receptionist, ServiceKey}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.Random
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.Done
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import eusocialcooperation.scheduler.DataPointActor.DataPointActorKey
import eusocialcooperation.scheduler.worker.states.{ExplorerState, WorkerState}
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.receptionist.Receptionist.Listing
import eusocialcooperation.scheduler.distributions.DistributionStrategy
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.util.Success
import scala.util.Failure

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
  case class Stop(replyTo: ActorRef[Done]) extends Command

  /** A message that confirms to the worker the thread stopped, allowing the
    * actor to send the response to the calling actor that the work has stopped.
    *
    * This should only be sent by the Worker actor to itself.
    *
    * @param replyTo
    *   The reference to send the confirmation that the worker has been stopped.
    */
  case class WorkerThreadStopped(replyTo: ActorRef[Done]) extends Command

  /** A message containing the listing of actors that create DataPoint monads.
    *
    * This should only be sent by the receptionist in response to a subscription
    * by the Worker actor.
    *
    * @param actors
    *   The listing of actors that create the requested type of DataPoint
    *   monads.
    */
  private final case class DPActorListing(
      actors: Listing
  ) extends Command

  /** Constructs the requested worker actor.
    *
    * @param kernelFn
    *   The function the workers are exploring.
    * @param dispatcher
    *   The dispatcher to whom the worker should send prospects and request
    *   prospects from.
    * @param config
    *   The configuration object used to provide configuration parameters.
    * @param mdc
    *   The logging context information that allows the logs to write to the
    *   correct locations.
    * @return
    *   The actor behavior used by Apache Pekko.
    */
  def apply(
      kernelFn: KernelFn,
      dispatcher: ActorRef[Dispatcher.Command]
  )(implicit config: Config, mdc: Map[String, String]): Behavior[Command] =
    Behaviors.withMdc(mdc)(
      Behaviors.setup { implicit ctx =>
        implicit val scheduler: Scheduler = ctx.system.scheduler

        val sampleKey = DataPointActorKey[Sample]
        val pointKey = DataPointActorKey[Point]
        ctx.log.debug(s"Worker keys {} and {}", sampleKey, pointKey)

        // There can only be one adapter from Receptionist.Listing to ActorRef[DataPointActor.Command] (and DataPointActor.Create[A]] has A erased, so it's the same thing), so the listing messages must be differentiated in the receiver.
        val adapter = ctx.messageAdapter[Receptionist.Listing](listing =>
          DPActorListing(listing)
        )

        ctx.system.receptionist ! Receptionist.Subscribe(
          sampleKey,
          adapter
        )
        ctx.system.receptionist ! Receptionist.Subscribe(
          pointKey,
          adapter
        )
        waitingForDpActors(kernelFn, dispatcher)(using
          ctx,
          config.getConfig(Worker.workersConfigKey)
        )
      }
    )

  /** Represents the state of the actor in which it has requested a listing of
    * actors that create DataPoint monads and is waiting for the system to
    * provide them. The system cannot proceed to the next state until all types
    * of actors have been supplied, so the references to the actors are Option
    * and likely to be None.
    *
    * @param kernelFn
    *   The function the workers are exploring.
    * @param dispatcher
    *   The dispatcher to whom the worker should send prospects and request
    *   prospects from.
    * @param sampleActor
    *   The DataPoint[Sample] creator, such as is currently known.
    * @param pointActor
    *   The DataPoint[Point] creator, such as is currently known.
    * @param context
    *   The context in which the actor was created, used to provide access to
    *   the Apache Pekko system.
    * @param config
    *   The configuration used to provide configuration parameters.
    * @return
    *   The actor behavior used by Apache Pekko.
    */
  private def waitingForDpActors(
      kernelFn: KernelFn,
      dispatcher: ActorRef[Dispatcher.Command],
      sampleActor: Option[ActorRef[DataPointActor.Create[Sample]]] = None,
      pointActor: Option[ActorRef[DataPointActor.Create[Point]]] = None
  )(implicit
      context: ActorContext[Command],
      config: Config
  ): Behavior[Command] =
    Behaviors
      .receiveMessage[Command] { msg =>
        val loopDelayMs = config.getMilliseconds(Worker.loopDelayConfigKey)

        // Provides the next state. Refactored here because it must be run in response to either the incoming listing of DataPoint[Sample] actors or DataPoint[Point] actors.
        def createNextState(
            kernelFn: KernelFn,
            dispatcher: ActorRef[Dispatcher.Command],
            sampleActor: Option[ActorRef[DataPointActor.Create[Sample]]],
            pointActor: Option[ActorRef[DataPointActor.Create[Point]]]
        ) = {
          if (sampleActor.isDefined && pointActor.isDefined) {
            implicit val scheduler: Scheduler = context.system.scheduler

            // context.log.info(s"Worker ${context.self.path.name} found DataPointActor and is starting.")
            val running = new AtomicBoolean(true)
            val strategy = DistributionStrategy()
            val preference = strategy()

            context.log.trace(
              "Worker starting thread with preference: {}",
              preference
            )
            val thread = new Thread(
              () => {
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
                  implicit val dpSampleActor
                      : ActorRef[DataPointActor.Create[Sample]] =
                    sampleActor.get
                  implicit val dpPointActor
                      : ActorRef[DataPointActor.Create[Point]] = pointActor.get
                  phase = phase()
                }
              },
              s"Worker Thread ${context.self.path.name}"
            )
            thread.setDaemon(true)
            thread.start()
            active(running, thread, sampleActor.get, pointActor.get)
          } else {
            waitingForDpActors(kernelFn, dispatcher, sampleActor, pointActor)
          }
        }

        msg match {
          case DPActorListing(actors)
              if actors.isForKey(
                DataPointActor.DataPointActorKey[Sample]
              ) && actors
                .serviceInstances(DataPointActor.DataPointActorKey[Sample])
                .nonEmpty =>
            createNextState(
              kernelFn,
              dispatcher,
              Option(
                actors
                  .serviceInstances(DataPointActor.DataPointActorKey[Sample])
                  .head
              ),
              pointActor
            )
          case DPActorListing(actors)
              if actors.isForKey(
                DataPointActor.DataPointActorKey[Point]
              ) && actors
                .serviceInstances(DataPointActor.DataPointActorKey[Point])
                .nonEmpty =>
            createNextState(
              kernelFn,
              dispatcher,
              sampleActor,
              Option(
                actors
                  .serviceInstances(DataPointActor.DataPointActorKey[Point])
                  .head
              )
            )
          case DPActorListing(actors)
              if actors.isForKey(DataPointActor.DataPointActorKey[Sample]) =>
            createNextState(kernelFn, dispatcher, None, pointActor)
          case DPActorListing(actors)
              if actors.isForKey(DataPointActor.DataPointActorKey[Point]) =>
            createNextState(kernelFn, dispatcher, sampleActor, None)
          case Stop(replyTo) =>
            context.log.info(
              s"Worker ${context.self.path.name} stopping while waiting for DataPointActors."
            )
            replyTo ! Done
            Behaviors.stopped
          case event =>
            context.log.warn(
              s"Worker received unusable $event while waiting for DataPointActor. This might be for an unrecognized actor."
            )
            Behaviors.same
        }
      }
      .receiveSignal { case (_, PostStop) =>
        context.log.info(
          s"Worker ${context.self.path.name} stopping while waiting for DataPointActor."
        )
        Behaviors.same
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
  // TODO: I wonder if sampleActorRef or pointActorRef are worth passing through to this state. It doesn't seem to be needed.
  private def active(
      running: AtomicBoolean,
      thread: Thread,
      sampleActorRef: ActorRef[DataPointActor.Create[Sample]],
      pointActorRef: ActorRef[DataPointActor.Create[Point]]
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
        context.pipeToSelf(Future { thread.join() }) {
          case Success(_) => WorkerThreadStopped(replyTo)
          case Failure(e) =>
            context.log.error(
              s"Worker ${context.self.path.name} encountered error while stopping: ${e.getMessage}"
            )
            WorkerThreadStopped(
              replyTo
            ) // Still reply to the sender to avoid hanging, even if there was an error.
        }
        context.log.debug(
          s"Worker ${context.self.path.name} stopped from message."
        )
        Behaviors.same
      case WorkerThreadStopped(replyTo) =>
        context.log.info(
          s"Worker ${context.self.path.name} stopped successfully."
        )
        replyTo ! Done
        Behaviors.stopped
      case DPActorListing(actors)
          if actors.isForKey(DataPointActor.DataPointActorKey[Sample]) && actors
            .serviceInstances(DataPointActor.DataPointActorKey[Sample])
            .nonEmpty =>
        active(
          running,
          thread,
          actors
            .serviceInstances(DataPointActor.DataPointActorKey[Sample])
            .head,
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
          actors.serviceInstances(DataPointActor.DataPointActorKey[Point]).head
        )
      case _ =>
        Behaviors.same
    }
}
