package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.concurrent.Await
import org.apache.pekko.actor.typed.Scheduler
import org.checkerframework.checker.units.qual.s

object DataPoint {
    enum Phase:
        case Explorer, Exploiter

    // TODO add a reference to the current actor or possibly thread ID
    def apply[A](value: A, worker: String, phase: Phase, parent: Option[DataPoint[?]] = None)(implicit dpa: ActorRef[DataPointActor.Create[A]], scheduler: Scheduler): DataPoint[A] = {
        implicit val timeout: Timeout = Timeout(3.seconds)

        //println(s"Creating DataPoint with value: $value, worker: $worker, phase: $phase")
        Await.result(dpa.ask[DataPoint[A]](replyTo => DataPointActor.Create(value, phase, worker, replyTo, parent)), 3.seconds)
    }
}

class DataPoint[A] (
    val sequenceNumber: Long,
    val timestamp: Long,
    val actorName: String,
    val phase: DataPoint.Phase,
    val value: A,
    val parent: Option[DataPoint[?]] = None
) {
    def flatMap[B](f: A => DataPoint[B]): DataPoint[B] = {
        f(value)
    }
}
