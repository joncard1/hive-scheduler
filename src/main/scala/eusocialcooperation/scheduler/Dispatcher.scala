package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object Dispatcher {

  sealed trait Command
  final case class AddPoint(point: DataPoint[Sample]) extends Command
  final case class RequestPoint(value: BigDecimal, replyTo: ActorRef[Nothing]) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { ctx =>
    (1 to 5).foreach { i =>
      ctx.spawn(Worker(kernel, ctx.self), s"worker-$i")
    }
    active(Set.empty)
  }

  private def active(points: Set[DataPoint[Sample]]): Behavior[Command] =
    Behaviors.receiveMessage {
      case AddPoint(point) =>
        active(points + point)

      case RequestPoint(_, _) =>
        Behaviors.unhandled
    }
}
