package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.receptionist.{Receptionist, ServiceKey}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.Random
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.PostStop

object Worker {

  // TODO: Consider making this configuration-driven or dynamically adjustable at runtime
  val loopDelayMs = 500L

  type KernelFn = (BigDecimal, BigDecimal) => BigDecimal

  /** (x, y, kernelResult) */
  type Sample = (BigDecimal, BigDecimal, BigDecimal)

  val DataPointActorKey: ServiceKey[DataPointActor.Create[Sample]] =
    ServiceKey("dataPointActor")

  sealed private trait Phase
  private case object Explorer extends Phase
  private case object Exploiter extends Phase

  sealed trait Command
  case object Stop extends Command
  private final case class DpActorListing(
    actors: Set[ActorRef[DataPointActor.Create[Sample]]]
  ) extends Command

  def apply(kernelFn: KernelFn, dispatcher: ActorRef[Dispatcher.Command]): Behavior[Command] =
    Behaviors.setup { ctx =>
      implicit val scheduler: Scheduler = ctx.system.scheduler

      ctx.system.receptionist ! Receptionist.Subscribe(
        DataPointActorKey,
        ctx.messageAdapter[Receptionist.Listing](listing =>
          DpActorListing(listing.serviceInstances(DataPointActorKey))
        )
      )
      waitingForDpActor(kernelFn, dispatcher)
    }

  private def waitingForDpActor(
    kernelFn: KernelFn,
    dispatcher: ActorRef[Dispatcher.Command]
  )(implicit scheduler: Scheduler): Behavior[Command] =
    Behaviors.receiveMessage {
      case DpActorListing(actors) if actors.nonEmpty =>
        val running  = new AtomicBoolean(true)
        val dpActorRef = new AtomicReference[ActorRef[DataPointActor.Create[Sample]]](actors.head)
        val preference = Random.between(0.0, 1.0)

        val thread = new Thread(() => {
          var phase: WorkerState = ExplorerState((BigDecimal(0.5), BigDecimal(0.5)), kernelFn, preference, dispatcher)
          while (running.get()) {
            implicit val dpActor: ActorRef[DataPointActor.Create[Sample]] = dpActorRef.get()
            phase = phase()
            try Thread.sleep(loopDelayMs)
            catch { case _: InterruptedException => Thread.currentThread().interrupt() }

          }
        })
        thread.setDaemon(true)
        thread.start()

        active(running, thread, dpActorRef)

      case Stop =>
        Behaviors.stopped

      case _ =>
        Behaviors.same
    }


  private def active(
    running: AtomicBoolean,
    thread: Thread,
    dpActorRef: AtomicReference[ActorRef[DataPointActor.Create[Sample]]]
  ): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case Stop =>
        running.set(false)
        thread.join()
        Behaviors.stopped

      case DpActorListing(actors) if actors.nonEmpty =>
        dpActorRef.set(actors.head)
        Behaviors.same

      case _ =>
        Behaviors.same
    }.receiveSignal {
      // TODO: Consider watching fro PreRestart, also. Not sure if that would re-run setup. Probably.
      case (_, PostStop) =>
        running.set(false)
        thread.join()
        Behaviors.same
    }
}
