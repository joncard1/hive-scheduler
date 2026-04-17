package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object DataPointActor {

  final case class Create[A](value: A, replyTo: ActorRef[DataPoint[A]])

  def apply[A](): Behavior[Create[A]] = nextState(0)

  def nextState[A](nextSequenceNumber: Long): Behavior[Create[A]] = Behaviors.receiveMessage { msg =>
    msg.replyTo ! new DataPoint(nextSequenceNumber, System.currentTimeMillis(), msg.value)
    nextState(nextSequenceNumber + 1)
  }
}