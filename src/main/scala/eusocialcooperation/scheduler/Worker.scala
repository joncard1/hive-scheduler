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

object Worker {

  val workersConfigKey = "workers"
  val loopDelayConfigKey = "loopDelay"

  type KernelFn = (BigDecimal, BigDecimal) => BigDecimal

  /** (x, y, kernelResult) */
  type Sample = (BigDecimal, BigDecimal, BigDecimal)

  sealed private trait Phase
  private case object Explorer extends Phase
  private case object Exploiter extends Phase

  sealed trait Command
  case class Stop(replyTo: ActorRef[Done]) extends Command
  private final case class DPActorListing(
    actors: Listing
  ) extends Command

  def apply(kernelFn: KernelFn, dispatcher: ActorRef[Dispatcher.Command])(implicit config: Config): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
      implicit val scheduler: Scheduler = ctx.system.scheduler

      val sampleKey = DataPointActorKey[Sample]
      val pointKey = DataPointActorKey[Point]
      ctx.log.info(s"Worker keys {} and {}", sampleKey, pointKey)

      // There can only be one adapter from Receptionist.Listing to ActorRef[DataPointActor.Command] (and DataPointActor.Create[A]] has A erased, so it's the same thing), so the listing messages must be differentiated in the receiver.
      val adapter = ctx.messageAdapter[Receptionist.Listing](listing => DPActorListing(listing))

      ctx.system.receptionist ! Receptionist.Subscribe(
        sampleKey,
        adapter
      )
      ctx.system.receptionist ! Receptionist.Subscribe(
        pointKey,
        adapter
      )
      waitingForDpActors(kernelFn, dispatcher)(using ctx, config.getConfig(Worker.workersConfigKey))
    }

  private def waitingForDpActors(
    kernelFn: KernelFn,
    dispatcher: ActorRef[Dispatcher.Command]
    , sampleActor: Option[ActorRef[DataPointActor.Create[Sample]]] = None
    , pointActor: Option[ActorRef[DataPointActor.Create[Point]]] = None
  )(implicit context: ActorContext[?], config: Config): Behavior[Command] =
    Behaviors.receiveMessage[Command] { msg =>
      val loopDelayMs = config.getMilliseconds(Worker.loopDelayConfigKey)
      def createNextState(
        kernelFn: KernelFn
        , dispatcher: ActorRef[Dispatcher.Command]
        , sampleActor: Option[ActorRef[DataPointActor.Create[Sample]]]
        , pointActor: Option[ActorRef[DataPointActor.Create[Point]]]
      ) = {
        if (sampleActor.isDefined && pointActor.isDefined) {
          implicit val scheduler: Scheduler = context.system.scheduler

          //context.log.info(s"Worker ${context.self.path.name} found DataPointActor and is starting.")
          val running  = new AtomicBoolean(true)
          val strategy = DistributionStrategy()
          val preference = strategy()

          val thread = new Thread(() => {
            var phase: WorkerState = ExplorerState((BigDecimal(Random.nextDouble()), BigDecimal(Random.nextDouble())), kernelFn, preference, dispatcher)
            while (running.get()) {
              implicit val dpSampleActor: ActorRef[DataPointActor.Create[Sample]] = sampleActor.get
              implicit val dpPointActor: ActorRef[DataPointActor.Create[Point]] = pointActor.get
              phase = phase()
              try Thread.sleep(loopDelayMs)
              catch { case _: InterruptedException => Thread.currentThread().interrupt() }
            }
          }, s"Worker Thread ${context.self.path.name}")
          thread.setDaemon(true)
          thread.start()
          active(running, thread, sampleActor.get, pointActor.get)
        } else {
          waitingForDpActors(kernelFn, dispatcher, sampleActor, pointActor)
        }
      }

      msg match {
        case DPActorListing(actors) if actors.isForKey(DataPointActor.DataPointActorKey[Sample]) && actors.serviceInstances(DataPointActor.DataPointActorKey[Sample]).nonEmpty =>
          createNextState(kernelFn, dispatcher, Option(actors.serviceInstances(DataPointActor.DataPointActorKey[Sample]).head), pointActor)
        case DPActorListing(actors) if actors.isForKey(DataPointActor.DataPointActorKey[Point]) && actors.serviceInstances(DataPointActor.DataPointActorKey[Point]).nonEmpty =>
          createNextState(kernelFn, dispatcher, sampleActor, Option(actors.serviceInstances(DataPointActor.DataPointActorKey[Point]).head))
        case DPActorListing(actors) if actors.isForKey(DataPointActor.DataPointActorKey[Sample]) =>
          createNextState(kernelFn, dispatcher, None, pointActor)
        case DPActorListing(actors) if actors.isForKey(DataPointActor.DataPointActorKey[Point]) =>
          createNextState(kernelFn, dispatcher, sampleActor, None)
        case Stop(replyTo) =>
          context.log.info(s"Worker ${context.self.path.name} stopping while waiting for DataPointActors.")
          replyTo ! Done
          Behaviors.stopped
        case event =>
          context.log.warn(s"Worker received unusable $event while waiting for DataPointActor. This might be for an unrecognized actor.")
          Behaviors.same
      }
    }.receiveSignal {
      case (_, PostStop) =>
        context.log.info(s"Worker ${context.self.path.name} stopping while waiting for DataPointActor.")
        Behaviors.same
    }


  private def active(
    running: AtomicBoolean,
    thread: Thread,
    sampleActorRef: ActorRef[DataPointActor.Create[Sample]],
    pointActorRef: ActorRef[DataPointActor.Create[Point]]
  )(implicit context: ActorContext[?], config: Config): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case Stop(replyTo) =>
        context.log.debug(s"Worker ${context.self.path.name} stopping from message.")
        running.set(false)
        thread.join()
        context.log.debug(s"Worker ${context.self.path.name} stopped from message.")
        replyTo ! Done
        Behaviors.stopped

      case DPActorListing(actors) if actors.isForKey(DataPointActor.DataPointActorKey[Sample]) && actors.serviceInstances(DataPointActor.DataPointActorKey[Sample]).nonEmpty =>
        active(running, thread, actors.serviceInstances(DataPointActor.DataPointActorKey[Sample]).head, pointActorRef)
      case DPActorListing(actors) if actors.isForKey(DataPointActor.DataPointActorKey[Point]) && actors.serviceInstances(DataPointActor.DataPointActorKey[Point]).nonEmpty =>
        active(running, thread, sampleActorRef, actors.serviceInstances(DataPointActor.DataPointActorKey[Point]).head)
      case _ =>
        Behaviors.same
    }.receiveSignal {
      // TODO: Consider watching fro PreRestart, also. Not sure if that would re-run setup. Probably.
      case (_, PostStop) =>
        running.set(false)
        thread.join()
        Behaviors.same
      case event =>
        //println(s"Worker received unexpected signal: $event")
        Behaviors.same
    }
}
