package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import scala.reflect.ClassTag
import org.apache.pekko.actor.typed.scaladsl.ActorContext

/** The definition of the actor used to generate DataPoints. Some mechanism was
  * required to serialize the creation across many threads in order to provide a
  * sequence number for the DataPoints, and I chose to use an actor for this
  * purpose. An alternative mechanism, such as insertion into a database, could
  * be used instead, but the actor seemed like a simple solution that would be
  * easy to implement and test.
  */
object DataPointActor {

  /** The key used to register the DataPointActor with the Apache Pekko
    * receptionist. This is used to allow other actors to find the
    * DataPointActor in order to send it messages to create DataPoints.
    *
    * @return
    */
  def DataPointActorKey[A: ClassTag]: ServiceKey[DataPointActor.Command] =
    ServiceKey(
      s"dataPointActor-${implicitly[ClassTag[A]].runtimeClass.getSimpleName}"
    )

  /** The general class of messages that the DataPointActor can receive.
    */
  sealed trait Command

  /** A message to create a DataPoint.
    *
    * @param value
    *   The value to be encapsulated in the DataPoint.
    * @param phase
    *   The phase in which the point is being generated.
    * @param name
    *   The name of the context (thread name, actor name, etc.) in which the
    *   point is being generated.
    * @param replyTo
    *   The actor reference to which to send the constructed DataPoint.
    * @param parent
    *   The precedent data that led to the generation of this data point, if
    *   applicable.
    */
  final case class Create[A](
      value: A,
      phase: DataPoint.Phase,
      name: String,
      replyTo: ActorRef[DataPoint[A]],
      parent: Option[DataPoint[?]] = None
  ) extends Command

  /** Constructs the actor used to generate DataPoint objects.
    *
    * @param memory
    *   A reference to the collection of data generated. This allows the client
    *   system to track the data generated while not supplying the algorithm
    *   itself the ability to to "cheat" and inspect the entire collection of
    *   data. This algorithm is for use in scenarios where exploration of the
    *   phase space must be done, but not necessarily reported, such as drones
    *   navigating real space that need to visit certain points and perform a
    *   task but not necessarily creating a large, centrally aggregated dataset.
    * @param mdc
    *   The map of values to be used for the MDC of the logger. This allows the
    *   system to change the location of the logs at runtime, which I would like
    *   it to do to aggregate the log data in the experiment folder with the
    *   other data output, and so keep the data separated by experiment
    *   execution.
    * @return
    */
    // TODO: Possibly I could make mdc optional by providing a default, but considering what a pain it's been trying to ensure data gets out, I don't think that's the next thing to do.
  def apply[A: ClassTag](
      memory: java.util.concurrent.atomic.AtomicReference[Set[DataPoint[A]]]
  )(implicit mdc: Map[String, String]): Behavior[Command] =
    Behaviors.withMdc(mdc)(
      Behaviors.setup(implicit context => {
        val key = DataPointActorKey[A]
        context.log.info(s"DataPointActor ${context.self.path.name} ${key}")
        context.system.receptionist ! Receptionist.Register(key, context.self)

        nextState(0L, memory)
      })
    )

  /**
    * The main behavior of the DataPointActor, initialized with the sequence number and already registered with the receptionist.
    *
    * @param nextSequenceNumber
    * The next sequence number to be assigned to a DataPoint created by this actor.
    * @param memory
    * The reference to the collection of data generated.
    * @param context
    * The context of the actor, used to log messages and manage the actor's behavior.
    * @return
      The next state of the actor, whether to continue the same, stop, or with the sequence number incremented.
    */
  def nextState[A: ClassTag](
      nextSequenceNumber: Long,
      memory: java.util.concurrent.atomic.AtomicReference[Set[DataPoint[A]]]
  )(implicit context: ActorContext[?]): Behavior[Command] =
    Behaviors.receiveMessage { msg =>
      // context.log.info(s"Creating point ${msg.name} with value ${msg.value}, phase ${msg.phase} and sequence number $nextSequenceNumber")
      msg match {
        case Create(
              value: A,
              phase,
              name,
              replyTo: ActorRef[DataPoint[A]],
              parent
            ) =>
          context.log.debug(
            s"Creating point ${name} with value ${value}, phase ${phase} and sequence number $nextSequenceNumber"
          )
          val newPoint = new DataPoint(
            nextSequenceNumber,
            System.currentTimeMillis(),
            name,
            phase,
            value,
            parent
          )
          replyTo ! newPoint
          context.log.debug(
            s"Created point ${name} with value ${value}, phase ${phase} and sequence number $nextSequenceNumber"
          )
          memory.updateAndGet(current => current + newPoint)
          nextState(nextSequenceNumber + 1, memory)
        case _ =>
          context.log.warn(s"Received unexpected message: $msg")
          Behaviors.same
      }
    }
}
