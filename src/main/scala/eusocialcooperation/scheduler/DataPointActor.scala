package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import scala.reflect.ClassTag

object DataPointActor {

  // TODO: Using the UUID because this is uses a type parameter. This is a bit hacky, but it allows us to have multiple DataPointActor types without needing to specify the type in the service key. If we wanted to be more explicit, we could include the type in the service key name, but that would require some more complex type handling.
  def DataPointActorKey[A : ClassTag]: ServiceKey[DataPointActor.Create[A]] =
    ServiceKey(s"dataPointActor-${implicitly[ClassTag[A]].runtimeClass.getSimpleName}")


  final case class Create[A](value: A, phase:DataPoint.Phase, name: String, replyTo: ActorRef[DataPoint[A]])

  def apply[A : ClassTag](memory: java.util.concurrent.atomic.AtomicReference[Set[DataPoint[A]]]): Behavior[Create[A]] = Behaviors.setup(context => {
    val key = DataPointActorKey[A]
    context.log.info(s"DataPointActor ${key}")
    context.system.receptionist ! Receptionist.Register(key, context.self)
    
    nextState(0L, memory)
  })

  // TODO: Maybe add storage to this?
  def nextState[A : ClassTag](nextSequenceNumber: Long, memory: java.util.concurrent.atomic.AtomicReference[Set[DataPoint[A]]]): Behavior[Create[A]] = Behaviors.receiveMessage { msg =>
      //context.log.info(s"Creating point ${msg.name} with value ${msg.value} and sequence number $nextSequenceNumber")
      val newPoint = new DataPoint(nextSequenceNumber, System.currentTimeMillis(), msg.name, msg.phase, msg.value)
      msg.replyTo ! newPoint
      memory.updateAndGet(current => current + newPoint)
      nextState(nextSequenceNumber + 1, memory)
    }
}