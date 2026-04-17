package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.ActorRef

object DataPoint {
    // TODO add access to the actor to generate the sequence number and timestamp
    def apply[A](value: A)(implicit system: ActorRef[DataPointActor.Create[A]]): DataPoint[A] = {
        new DataPoint(0, System.currentTimeMillis(), value)
    }
}

class DataPoint[A] (
    val sequenceNumber: Long,
    val timestamp: Long,
    val value: A
) {
    def flatMap[B](f: A => DataPoint[B]): DataPoint[B] = {
        f(value)
    }
}
