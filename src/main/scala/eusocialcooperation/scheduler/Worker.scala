package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._
import scala.util.Random

object Worker {

  type KernelFn = (BigDecimal, BigDecimal) => BigDecimal

  sealed trait Command
  private final case class RemoveDataPoint(dp: DataPoint[Sample]) extends Command

  def apply(kernelFn: KernelFn, dispatcher: ActorRef[Dispatcher.Command]): Behavior[Command] = Behaviors.setup { ctx =>
    val preference = BigDecimal(Random.nextDouble())
    // TODO: Not sure when to implement this, but this should be referenced by address in the system
    implicit val dpActor: ActorRef[DataPointActor.Create[Sample]] =
      ctx.spawn(DataPointActor[Sample](), "dataPointActor")
    explorer(kernelFn, preference, Set.empty)
  }

  def explorer(
    kernelFn: KernelFn,
    position: BigDecimal,
    memory: Set[DataPoint[Sample]]

    
  )(implicit
    dpActor: ActorRef[DataPointActor.Create[Sample]]
  ): Behavior[Command] = Behaviors.setup { ctx =>
    val x      = BigDecimal(Random.nextDouble())
    val y      = BigDecimal(Random.nextDouble())
    val result = kernelFn(x, y)

    val dataPoint = DataPoint((x, y, result))

    val delaySeconds = (1.0 - result.toDouble).max(0.0)
    ctx.scheduleOnce(delaySeconds.seconds, ctx.self, RemoveDataPoint(dataPoint))

    val newMemory = memory + dataPoint

    Behaviors.receiveMessage {
      case RemoveDataPoint(dp) =>
        explorer(kernelFn, position, newMemory - dp)
    }
  }

  def exploiter(
    kernelFn: KernelFn,
    position: BigDecimal,
    memory: Set[DataPoint[Sample]],
    dpActor: ActorRef[DataPointActor.Create[Sample]]
  ): Behavior[Command] =
    Behaviors.receiveMessage { _ =>
      Behaviors.unhandled
    }
}

