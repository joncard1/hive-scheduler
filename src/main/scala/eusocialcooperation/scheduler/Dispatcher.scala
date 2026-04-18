package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import scala.concurrent.duration.DurationLong
import java.util.concurrent.atomic.AtomicReference

object Dispatcher {
  // TODO: Possibly move this to a configuration file or make it adjustable at runtime
  val numWorkers = 5

  sealed trait Command
  // TODO: Actually, is AddPoint redundant? I could just put storage in DataPointActor
  //final case class AddPoint(point: DataPoint[Sample]) extends Command
  final case class AddProspect(point: Point, delayMs: Long) extends Command
  final case class RequestPoints(replyTo: ActorRef[RequestedPoints]) extends Command
  final case class RemoveProspect(point: Point) extends Command

  sealed trait Response
  final case class RequestedPoints(points: Set[(BigDecimal, BigDecimal)]) extends Response

  def apply(memory: AtomicReference[Set[DataPoint[Sample]]]): Behavior[Command] = Behaviors.setup { ctx =>
    val dpActor = ctx.spawn(DataPointActor[Sample](memory), "dataPointActor")
    

    (1 to numWorkers).foreach { i =>
      ctx.spawn(Worker(kernel, ctx.self), s"worker-$i")
    }
    active(Set.empty, Set.empty, ctx)
  }

  private def active(points: Set[DataPoint[Sample]], prospects: Set[(BigDecimal, BigDecimal)], ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      //case AddPoint(point) =>
      //  active(points + point, prospects, ctx)
      case AddProspect(point, delayMs) =>
        ctx.scheduleOnce(delayMs.milliseconds, ctx.self, RemoveProspect(point))
        active(points, prospects + point, ctx)
      case RemoveProspect(point) => 
        active(points, prospects - point, ctx)
      case RequestPoints(replyTo) =>
        replyTo ! RequestedPoints(prospects)
        active(points, prospects, ctx)
    }
}
