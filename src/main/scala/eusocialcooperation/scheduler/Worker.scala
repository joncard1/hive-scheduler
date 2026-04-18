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

object Worker {

  // TODO: Consider making this configuration-driven or dynamically adjustable at runtime
  val loopDelayMs = 50L

  type KernelFn = (BigDecimal, BigDecimal) => BigDecimal

  /** (x, y, kernelResult) */
  type Sample = (BigDecimal, BigDecimal, BigDecimal)

  sealed private trait Phase
  private case object Explorer extends Phase
  private case object Exploiter extends Phase

  sealed trait Command
  case class Stop(replyTo: ActorRef[Done]) extends Command
  private final case class DpActorListing(
    actors: Set[ActorRef[DataPointActor.Create[Sample]]]
  ) extends Command

  def apply(kernelFn: KernelFn, dispatcher: ActorRef[Dispatcher.Command]): Behavior[Command] =
    Behaviors.setup { implicit ctx =>
      implicit val scheduler: Scheduler = ctx.system.scheduler

      val subscriptionKey = DataPointActorKey[Sample]
      ctx.log.info(s"Subscribing to key ${subscriptionKey}")
      ctx.system.receptionist ! Receptionist.Subscribe(
        DataPointActorKey[Sample],
        ctx.messageAdapter[Receptionist.Listing](listing =>
          DpActorListing(listing.serviceInstances(DataPointActorKey[Sample]))
        )
      )
      waitingForDpActor(kernelFn, dispatcher)
    }

  private def waitingForDpActor(
    kernelFn: KernelFn,
    dispatcher: ActorRef[Dispatcher.Command]
  )(implicit context: ActorContext[?]): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case DpActorListing(actors) if actors.nonEmpty =>
        implicit val scheduler: Scheduler = context.system.scheduler

        context.log.info(s"Worker ${context.self.path.name} found DataPointActor and is starting.")
        val running  = new AtomicBoolean(true)
        val dpActorRef = new AtomicReference[ActorRef[DataPointActor.Create[Sample]]](actors.head)
        val preference = Random.between(0.0, 1.0)

        val thread = new Thread(() => {
          var phase: WorkerState = ExplorerState((BigDecimal(Random.nextDouble()), BigDecimal(Random.nextDouble())), kernelFn, preference, dispatcher)
          while (running.get()) {
            println("Looping")
            implicit val dpActor: ActorRef[DataPointActor.Create[Sample]] = dpActorRef.get()
            phase = phase()
            try Thread.sleep(loopDelayMs)
            catch { case _: InterruptedException => Thread.currentThread().interrupt() }

          }
        })
        thread.setDaemon(true)
        thread.start()

        active(running, thread, dpActorRef)

      case Stop(replyTo) =>
        context.log.info(s"Worker ${context.self.path.name} stopping.")
        replyTo ! Done
        Behaviors.stopped

      case event =>
        context.log.info(s"Worker received weird event $event while waiting for DataPointActor.")
        Behaviors.same
    }.receiveSignal {
      case (_, PostStop) =>
        context.log.info(s"Worker ${context.self.path.name} stopping while waiting for DataPointActor.")
        Behaviors.same
    }


  private def active(
    running: AtomicBoolean,
    thread: Thread,
    dpActorRef: AtomicReference[ActorRef[DataPointActor.Create[Sample]]]
  )(implicit context: ActorContext[?]): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case Stop(replyTo) =>
        println(s"Worker ${context.self.path.name} stopping.")
        running.set(false)
        thread.join()
        replyTo ! Done
        Behaviors.stopped

      case DpActorListing(actors) if actors.nonEmpty =>
        dpActorRef.set(actors.head)
        Behaviors.same

      case _ =>
        Behaviors.same
    }.receiveSignal {
      // TODO: Consider watching fro PreRestart, also. Not sure if that would re-run setup. Probably.
      case (_, PostStop) =>
        println(s"Worker ${context.self.path.name} stopping.")
        running.set(false)
        thread.join()
        Behaviors.same
      case event =>
        println(s"Worker received unexpected signal: $event")
        Behaviors.same
    }
}
