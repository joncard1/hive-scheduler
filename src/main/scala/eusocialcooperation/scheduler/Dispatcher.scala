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

object Dispatcher {

  sealed trait Command
  // TODO: Actually, is AddPoint redundant? I could just put storage in DataPointActor
  //final case class AddPoint(point: DataPoint[Sample]) extends Command
  final case class AddProspect(point: DataPoint[Point], delayMs: Long) extends Command
  final case class RequestPoints(replyTo: ActorRef[RequestedPoints]) extends Command
  final case class RemoveProspect(point: DataPoint[Point]) extends Command

  sealed trait Response
  final case class RequestedPoints(points: Set[DataPoint[Point]]) extends Response

  def apply(pointsMemory: AtomicReference[Set[DataPoint[Sample]]], prospectsMemory: AtomicReference[Set[DataPoint[Point]]])(implicit appConfig: Config): Behavior[Command] = Behaviors.setup { ctx =>
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

    (1 to numWorkers).foreach { i =>
      ctx.spawn(Worker(kernel, ctx.self), s"worker-$i")
    }
    active(Set.empty, Set.empty, prospectsMemory, ctx)
  }

  private def active(points: Set[DataPoint[Sample]], prospects: Set[DataPoint[Point]], prospectsMemory: AtomicReference[Set[DataPoint[Point]]], ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      //case AddPoint(point) =>
      //  active(points + point, prospects, ctx)
      case AddProspect(point, delayMs) =>
        ctx.log.info(s"Adding prospect: $point with delay: $delayMs ms")
        ctx.scheduleOnce(delayMs.milliseconds, ctx.self, RemoveProspect(point))
        active(points, prospects + point, prospectsMemory, ctx)
      case RemoveProspect(point) => 
        ctx.log.info(s"Removing prospect: $point")
        prospectsMemory.updateAndGet(_ + point)
        active(points, prospects - point, prospectsMemory, ctx)
      case RequestPoints(replyTo) =>
        replyTo ! RequestedPoints(prospects)
        active(points, prospects, prospectsMemory, ctx)
    }.receiveSignal({
      case (ctx, PostStop) =>
        ctx.log.info(s"Received stop signal in Dispatcher, shutting down.")
        prospectsMemory.updateAndGet(_ ++ prospects)
        Behaviors.same
    })
}
