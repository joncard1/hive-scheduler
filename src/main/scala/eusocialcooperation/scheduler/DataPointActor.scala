package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import scala.reflect.ClassTag
import org.apache.pekko.actor.typed.scaladsl.ActorContext

object DataPointActor {

  def DataPointActorKey[A : ClassTag]: ServiceKey[DataPointActor.Command] =
    ServiceKey(s"dataPointActor-${implicitly[ClassTag[A]].runtimeClass.getSimpleName}")

  sealed trait Command
  final case class Create[A](value: A, phase:DataPoint.Phase, name: String, replyTo: ActorRef[DataPoint[A]], parent: Option[DataPoint[?]] = None) extends Command

  def apply[A : ClassTag](memory: java.util.concurrent.atomic.AtomicReference[Set[DataPoint[A]]]): Behavior[Command] = Behaviors.setup(implicit context => {
    val key = DataPointActorKey[A]
    context.log.info(s"DataPointActor ${context.self.path.name} ${key}")
    context.system.receptionist ! Receptionist.Register(key, context.self)
    
    nextState(0L, memory)
  })

  // TODO: Maybe add storage to this?
  def nextState[A : ClassTag](nextSequenceNumber: Long, memory: java.util.concurrent.atomic.AtomicReference[Set[DataPoint[A]]])(implicit context: ActorContext[?]): Behavior[Command] = Behaviors.receiveMessage { msg =>
      //context.log.info(s"Creating point ${msg.name} with value ${msg.value}, phase ${msg.phase} and sequence number $nextSequenceNumber")
      msg match {
        case Create(value: A, phase, name, replyTo : ActorRef[DataPoint[A]], parent) =>
          context.log.debug(s"Creating point ${name} with value ${value}, phase ${phase} and sequence number $nextSequenceNumber")
          val newPoint = new DataPoint(nextSequenceNumber, System.currentTimeMillis(), name, phase, value, parent)
          replyTo ! newPoint
          context.log.debug(s"Created point ${name} with value ${value}, phase ${phase} and sequence number $nextSequenceNumber")
          memory.updateAndGet(current => current + newPoint)
          nextState(nextSequenceNumber + 1, memory)
        case _ =>
          context.log.warn(s"Received unexpected message: $msg")
          Behaviors.same
      }
  }
}