package eusocialcooperation.scheduler.datapoint

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.DurationInt
import scala.concurrent.Await
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import scala.concurrent.duration.Duration

object PekkoDataPoint {
  /** The primary factory method for lifting a value to a DataPoint[?]. The
    * value itself is provided, but there are a number of environmental data
    * sources that need to be made available for this to operate.
    *
    * This factory method uses an Apache Pekko actor to create the DataPoint in
    * order to provide the sequence number, because I am interested in the order
    * the points are created in. An alternative method, such as insertion into a
    * database, could be an alternative.
    *
    * My intent for this class is that, as the reporting needs of the
    * application evolved, it would not be necessary to make major structural
    * changes to the rest of the algorithm to keep up with them, slowing
    * development and confusing the human reader. Instead, the compiler should
    * be able to adapt to additions or subtractions from the list of implicit
    * parameters with only the occasional addition of a "given" in the code
    * that is easier to ignore than changes to the parameter list of a function.
    * This way, changes to the implementation that are only of use to the
    * reporting system, such as the use of a database or an actor to provide the
    * sequence number, will be as low-impact as possible, although it isn't
    * completely invisible.
    *
    * @param value
    *   The value to be lifted into the DataPoint monad.
    * @param dpa
    *   The actor used to create the DataPoint.
    * @param scheduler
    *   The Apache Pekko scheduler used to coordinate messages, since this
    *   constructor requires a return message.
    * @param phase
    *   The phase in which the point is being generated.
    * @param parent
    *   The precedent data that led to the generation of this data point, if
    *   applicable.
    * @return
    *   The DataPoint containing the value, with the metadata provided by the
    *   implicit parameters and the actor message.
    */
  def getActorDataPointUnit[A](dpa: ActorRef[DataPointActor.Create[A]], scheduler: Scheduler): DataPoint.DataPointUnit[A] = {
    (value) => (phase, actorName, parent) ?=> {
      implicit val timeout: Timeout = Timeout(3.seconds)
      //val worker: String = Thread.currentThread().getName

      // Using Inf because the pekko ask function takes a timeout, and it's specified above.
      Await.result(
        dpa.ask[DataPoint[A]](replyTo =>
          DataPointActor.Create(value, phase, actorName, replyTo, parent)
        )(using scheduler = scheduler),
        Duration.Inf
      )
    }
  }

}
