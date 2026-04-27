package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import scala.concurrent.duration.DurationLong
import java.util.concurrent.atomic.AtomicReference
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.PostStop
import javax.xml.crypto.Data
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout
import scala.concurrent.Future
import scala.concurrent.Await

object Dispatcher {

  sealed trait Command
  // TODO: Actually, is AddPoint redundant? I could just put storage in DataPointActor
  //final case class AddPoint(point: DataPoint[Sample]) extends Command
  final case class AddProspect(point: DataPoint[Point], delayMs: Long) extends Command
  final case class RequestPoints(replyTo: ActorRef[RequestedPoints]) extends Command
  final case class RemoveProspect(point: DataPoint[Point]) extends Command
  final case class Stop(replyTo: ActorRef[Response]) extends Command
  final case class WorkersStopped(replyTo: ActorRef[Response]) extends Command

  sealed trait Response
  final case class RequestedPoints(points: Set[DataPoint[Point]]) extends Response
  final case class Stopped() extends Response

  def apply(pointsMemory: AtomicReference[Set[DataPoint[Sample]]], prospectsMemory: AtomicReference[Set[DataPoint[Point]]])(implicit appConfig: Config, mdc: Map[String, String]): Behavior[Command] =
    Behaviors.withMdc(mdc)(
      Behaviors.setup { ctx =>
        val localConfig = appConfig.getConfig("dispatcher")
        val numWorkers = {
          val num = localConfig.getInt("numWorkers")
          if (num < 1) {
            throw new IllegalArgumentException(s"numWorkers must be at least 1, but got $num")
          }
          num
        }
        val sampleActor = ctx.spawn(DataPointActor[Sample](pointsMemory), "sampleActor")
        val pointActor = ctx.spawn(DataPointActor[Point](new AtomicReference(Set.empty)), "pointActor")

        var workers = Set.empty[ActorRef[Worker.Command]]
        ctx.log.trace("Spawning {} workers.", numWorkers)
        (1 to numWorkers).foreach { i =>
          workers += ctx.spawn(Worker(kernel, ctx.self), s"worker-$i")
        }
        active(Set.empty, Set.empty, prospectsMemory, sampleActor, pointActor, workers, ctx)
      }
  )

  private def active(points: Set[DataPoint[Sample]], prospects: Set[DataPoint[Point]], prospectsMemory: AtomicReference[Set[DataPoint[Point]]], sampleActor: ActorRef[DataPointActor.Command], pointActor: ActorRef[DataPointActor.Command], workers: Set[ActorRef[Worker.Command]], ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      //case AddPoint(point) =>
      //  active(points + point, prospects, ctx)
      case AddProspect(point, delayMs) =>
        ctx.log.info(s"Adding prospect: $point with delay: $delayMs ms")
        ctx.scheduleOnce(delayMs.milliseconds, ctx.self, RemoveProspect(point))
        active(points, prospects + point, prospectsMemory, sampleActor, pointActor, workers, ctx)
      case RemoveProspect(point) => 
        ctx.log.info(s"Removing prospect: $point")
        prospectsMemory.updateAndGet(_ + point)
        active(points, prospects - point, prospectsMemory, sampleActor, pointActor, workers, ctx)
      case RequestPoints(replyTo) =>
        replyTo ! RequestedPoints(prospects)
        active(points, prospects, prospectsMemory, sampleActor, pointActor, workers, ctx)
      case Stop(replyTo: ActorRef[Response]) => 
        ctx.log.info("Received stop command, terminating workers and self.")
        given Scheduler = ctx.system.scheduler
        given Timeout = 5.seconds
        given scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
        ctx.pipeToSelf(Future.sequence(workers.map(_.ask(Worker.Stop(_))))) {
          case scala.util.Success(_) => 
            ctx.log.info("All workers stopped successfully.")
            WorkersStopped(replyTo)
          case scala.util.Failure(e) => 
            ctx.log.error("Error while stopping workers: {}", e.getMessage)
            WorkersStopped(replyTo) // Still attempt to stop self even if workers fail to stop
        }
        Behaviors.same
      case WorkersStopped(replyTo) =>
        ctx.log.info("All workers have been stopped, now stopping dispatcher.")
        prospectsMemory.updateAndGet(_ ++ prospects)
        ctx.stop(sampleActor)
        ctx.stop(pointActor)
        replyTo ! Stopped()
        Behaviors.stopped
    }
}
