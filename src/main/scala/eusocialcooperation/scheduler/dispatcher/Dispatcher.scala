package eusocialcooperation.scheduler.dispatcher

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import scala.concurrent.duration.DurationLong
import java.util.concurrent.atomic.AtomicReference
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout
import eusocialcooperation.scheduler.datapoint.DataPoint
import scala.util.{Success, Failure}
import scala.util.Try
import eusocialcooperation.scheduler.dispatcher.Dispatcher.WorkersStopped
import scala.concurrent.duration.FiniteDuration
import eusocialcooperation.scheduler.dispatcher.Dispatcher.WorkerFailedToStop
import org.apache.pekko.actor.Cancellable
import eusocialcooperation.scheduler._

/** This actor represents the "world" that the agents can see. It starts the
  * various workers and references, but it is only accessed by the workers so
  * that they can "see" which other workers are advertising prospects they have
  * found. In other applications, this can be replaced by agents returning to a
  * rendevous point and interacting with each other through Bluetooth BLE,
  * flashing lights, etc. That it does not assign workers to specific tasks or
  * perform any priority filtering of the data except to expire messages based
  * on the reported desired expiration time is important to understanding the
  * difference between this and, for example, the Bees Algorithm of Pham,
  * Ghanbarzadeh, et al. (https://en.wikipedia.org/wiki/Bees_algorithm) That
  * difference is what makes this algorithm generalizable to other eusocial
  * behaviors, like bee bearding, and human emergent behaviors such as supply
  * and demand.
  */
object Dispatcher extends Dispatcher {

  val dispatcherConfigKey = "dispatcher"
  val numWorkersConfigKey = "numWorkers"

  /** The trait that represents incoming messages to the dispatcher.
    */
  trait Command

  case class StartRun(
      experimentName: String,
      run: Int,
      duration: FiniteDuration,
      replyTo: ActorRef[Dispatcher.Response]
  ) extends Dispatcher.Command


  /** A request to add a prospect discovered by an explorer.
    * @param point
    *   The prospect to be added.
    * @param delayMs
    *   The time in milliseconds after which the prospect should be removed from
    *   the list of prospects.
    */
  final case class AddProspect(point: DataPointP, delayMs: Long)
      extends Command

  /** A request to see the tasks being advertised by explorers.
    * @param replyTo
    *   The actor reference to which to send the list of prospects.
    */
  final case class RequestPoints(replyTo: ActorRef[RequestedPoints])
      extends Command

  /** A request to remove a prospect from the list of advertised prospects. This
    * message should only be sent by the scheduler in response to a scheduled
    * event.
    * @param point
    *   The prospect to be removed.
    */
  final case class RemoveProspect(point: DataPointP) extends Command

  /** A request to stop the dispatcher and all workers. This is used to
    * gracefully stop the services; otherwise, stopping the actors in response
    * to PostStop resulted in workers not being able to finish their
    * transactions with the DataPointActors and nothing stopping gracefully.
    * Maybe I've fixed it, but I'm not going back.
    *
    * @param replyTo
    *   The actor reference to which to send the stop confirmation.
    */
  final case class Stop(replyTo: ActorRef[Response]) extends Command

  /** A message sent to the dispatcher when all workers have been stopped to
    * avoid locking the actor threads while other actors need to finish
    * interactions with the DataPointActors or receving messages to stop
    * themselves.
    *
    * @param e
    *   The optional throwable expressing the results of the workers. [[None]]
    *   represents a successful operation, a throwable indicates at least one
    *   error during execution.
    */
  final case class WorkersStopped(e: Option[Throwable]) extends Command

  /**
   * A message indicating that a worker has stopped. This may be expected as a
   * response to a Worker.Stop command or as a result of the thread completing
   * on its own.
   * 
   * @param worker
   * A reference to the worker that stopped.
   * @param result
   * The result of the thread.
   */
  final case class WorkerStopped(worker: ActorRef[Worker.Command], result: Try[Unit]) extends Command

  final case class WorkerFailedToStop(worker: ActorRef[Worker.Command]) extends Command


  /** The trait that represents outgoing messages from the dispatcher.
    */
  trait Response

  /** The prospects being advertised by the explorers.
    *
    * @param points
    *   The set of prospects being advertised by the explorers.
    */
  final case class RequestedPoints(points: Set[DataPointP])
      extends Response

  /** Confirmation that the dispatcher has successfully stopped.
    */
  final case class Stopped() extends Response

  final case class RunCompleted() extends Dispatcher.Response

  /** A function that spawns a single worker as a child of the dispatcher.
    * 
    * @param duration
    * @param ctx
    *   The dispatcher's actor context, used to call `ctx.spawn`.
    * @param index
    *   The 1-based index of the worker being created. Implementations should
    *   use this to give each worker a unique name within the actor hierarchy.
    * @param dataPointSample
    *   The unit operation for [[Sample]] data.
    * @param dataPointProspect
    *   The unit operation for [[Point]] data.
    * @return
    *   The `ActorRef` of the newly spawned worker.
    */
  type WorkerFactory = (FiniteDuration, ActorContext[Command], Int, DataPoint.DataPointUnit[Sample], DataPoint.DataPointUnit[Point]) => ActorRef[Worker.Command]

}

trait Dispatcher {
  /** Convenience overload that uses the standard [[Worker]] as the worker
    * implementation. This is the factory that should be used in production.
    *
    * @param sampleUnit
    *   The unit operation to lift [[Sample]] objects to DataPoint[Sample].
    * @param prospectUnit
    *   The unit operation to lift [[Point]] objects to DataPoint[Point]
    * @param f
    *   An operation to run when an experiment starts.
    * @param appConfig
    *   The configuration object the dispatcher will use to access global
    *   configuration parameters.
    * @param mdc
    *   The MDC configuration to use for the dispatcher and all of its child
    *   actors.
    * @return
    */
  def apply(
    sampleUnit: DataPoint.DataPointUnit[Sample]
    , prospectUnit: DataPoint.DataPointUnit[Point]
  )(
    f: (ActorContext[Dispatcher.Command]
      , Int
      , String
      , (DataPoint.DataPointUnit[Sample], DataPoint.DataPointUnit[Point]) => PartialFunction[Dispatcher.Command, Behavior[Dispatcher.Command]]
    ) => Behavior[Dispatcher.Command]
  )(implicit
    appConfig: Config
    , mdc: Map[String, String]
  ): Behavior[Dispatcher.Command] = 
    apply((duration, ctx, i, sampleUnit, prospectUnit) => ctx.spawn(Worker(kernel, ctx.self, duration)(using sampleUnit = sampleUnit, prospectUnit = prospectUnit), s"worker-$i")
    )(f)(using appConfig, mdc = mdc)

  def apply(
    workerFactory: Dispatcher.WorkerFactory
  )(
    f: (ActorContext[Dispatcher.Command], Int, String, (DataPoint.DataPointUnit[Sample], DataPoint.DataPointUnit[Point]) => PartialFunction[Dispatcher.Command, Behavior[Dispatcher.Command]]) => Behavior[Dispatcher.Command]
  )(
    implicit appConfig: Config
    , mdc: Map[String, String]
  ): Behavior[Dispatcher.Command] =
      Behaviors.setup { ctx => 
        waitForStart(ctx, workerFactory, appConfig, f)
      }
  
  /**
    * Represents the state of a dispatcher waiting for a StartRun message. When
    *  the message is received, the function provided as f is executed and the
    *  workers are started.
    *
    * @param ctx
    *   The context of the dispatcher.
    * @param workerFactory
    *   The function used to generate a worker. Used to further testing.
    * @param appConfig
    *   The application configuration.
    * @param f
    *   The function used to forward information to a sub-class.
    * @return
    *   The state to wait for StartRun.
    */
  def waitForStart(
    ctx: ActorContext[Dispatcher.Command]
    , workerFactory: Dispatcher.WorkerFactory
    , appConfig: Config
    , f: (ActorContext[Dispatcher.Command], Int, String, (DataPoint.DataPointUnit[Sample], DataPoint.DataPointUnit[Point]) => PartialFunction[Dispatcher.Command, Behavior[Dispatcher.Command]]) => Behavior[Dispatcher.Command]
  ): Behavior[Dispatcher.Command] = Behaviors.receiveMessage {
    case Dispatcher.StartRun(experimentName, run, duration, replyTo) =>
      // TODO: 
      ctx.log.debug("Received StartRun for {}, {}", experimentName, run)
      f(ctx, run, experimentName, startWorkers(duration, workerFactory, ctx, replyTo, _, _, appConfig, f))
    case Dispatcher.Stop(replyTo) =>
      ctx.log.debug(s"Dispatcher got message to stop.")
      replyTo ! Dispatcher.Stopped()
      Behaviors.stopped
    case c =>
      ctx.log.debug(s"Received an unexpected message: ${c}. This may not be a problem (for instance, persistent data update messages between runs)")
      Behaviors.same
  }

  /** Starts the workers and returns the general behavior handling the limited
   *  behavior handling generic dispatcher behavior (generally handling the
   *  management of workers).
    *
    * @param duration
    *   The duration the workers are expected to run.
    * @param workerFactory
    *   A function that spawns a single worker given the dispatcher context and
    *   a 1-based worker index. Called once per configured worker at startup.
    * @param ctx
    *   The context of the dispatcher spawning the workers.
    * @param replyTo
    *   The actor ref of the dispatcher. (Possibly redudant with the context)
    * @param sampleUnit
    *   The unit operation to lift a [[Sample]] to a DataPoint[Sample]
    * @param prospectUnit
    *   The unit operation to lift a [[Point]] to a DataPoint[Point]
    * @param appConfig
    *   The configuration object the dispatcher will use to access global
    *   configuration parameters. Contains the [[Dispatcher.dispatcherConfigKey]] key.
    * @return
    */
  protected def startWorkers(
    duration: FiniteDuration
    , workerFactory: Dispatcher.WorkerFactory
    , ctx: ActorContext[Dispatcher.Command]
    , replyTo: ActorRef[Dispatcher.Response]
    , sampleUnit: DataPoint.DataPointUnit[Sample]
    , prospectUnit: DataPoint.DataPointUnit[Point]
    , appConfig: Config
    , f: (ActorContext[Dispatcher.Command], Int, String, (DataPoint.DataPointUnit[Sample], DataPoint.DataPointUnit[Point]) => PartialFunction[Dispatcher.Command, Behavior[Dispatcher.Command]]) => Behavior[Dispatcher.Command]
  ) = {
      val scheduler = ctx.system.scheduler
      val localConfig = appConfig.getConfig(Dispatcher.dispatcherConfigKey)
      val numWorkers = {
        val num = localConfig.getInt(Dispatcher.numWorkersConfigKey)
        if (num < 1) {
          throw new IllegalArgumentException(
            s"numWorkers must be at least 1, but got $num"
          )
        }
        num
      }

      var workers = Set.empty[ActorRef[Worker.Command]]
      ctx.log.trace("Spawning {} workers.", numWorkers)
      (1 to numWorkers).foreach { i =>
        workers += workerFactory(duration, ctx, i, sampleUnit, prospectUnit)
      }

      activeParentBehavior(AtomicReference(workers), AtomicReference(Map()), AtomicReference(Set()), ctx, AtomicReference(replyTo), AtomicReference(None), workerFactory, appConfig, f)
    }

  /** Generates the main behavior of the dispatcher, after the DataPointActors
    * and the workers have been started.
    *
    * @param sampleActor
    *   The actor used to create DataPoint[Sample] objects.
    * @param pointActor
    *   The actor used to create DataPoint[Point] objects.
    * @param workers
    *   The workers created.
    * @param ctx
    *   The context used to create the actor that provides access to logging and
    *   other utilities.
    * @return
    *   The behavior of the actor to use in subsequent calls.
    */
  // In this pattern, the parent behavior can't update the parent's state, or at least I can't figure out how, because I can't figure out how to get the child's state in the updated object without standardizing the method signature of the child state method.
  protected def activeParentBehavior(
      workersRunning: AtomicReference[Set[ActorRef[Worker.Command]]]
      , workersStopping: AtomicReference[Map[ActorRef[Worker.Command], Cancellable]]
      , results: AtomicReference[Set[Try[Unit]]]
      , ctx: ActorContext[Dispatcher.Command]
      , startCommander: AtomicReference[ActorRef[Dispatcher.RunCompleted]]
      , stopCommander: AtomicReference[Option[ActorRef[Dispatcher.Stopped]]]
      , workerFactory: Dispatcher.WorkerFactory
      , appConfig: Config
      , f: (ActorContext[Dispatcher.Command], Int, String, (DataPoint.DataPointUnit[Sample], DataPoint.DataPointUnit[Point]) => PartialFunction[Dispatcher.Command, Behavior[Dispatcher.Command]]) => Behavior[Dispatcher.Command]
  ): PartialFunction[Dispatcher.Command, Behavior[Dispatcher.Command]] = {
    // TODO: Actually, this might be the answer. Receiving the Stop message means it needs to stop once the workers are completed; receiving WorkerCompleted without having received a Stop should result in sending RunCompleted (in ClusterDispatcher)
      case Dispatcher.Stop(replyTo: ActorRef[Dispatcher.Response]) =>
        ctx.log.info("Received stop command, terminating workers and self.")
        stopCommander.getAndUpdate(_ => Some(replyTo))
        given Scheduler = ctx.system.scheduler
        given Timeout = 5.seconds
        given scala.concurrent.ExecutionContext =
          scala.concurrent.ExecutionContext.global
        workersRunning.get().foreach { worker =>
          worker ! Worker.Stop(ctx.self)
          val shutdownTimeout = 1.second
          val cancellable = ctx.scheduleOnce(shutdownTimeout, ctx.self, WorkerFailedToStop(worker))
          workersStopping.getAndUpdate(_ + (worker -> cancellable))
        }
        Behaviors.same
      case Dispatcher.WorkerFailedToStop(worker) =>
        workersStopping.getAndUpdate(workersStopping => {
          workersStopping.removed(worker)
        })
        ctx.self ! Dispatcher.WorkerStopped(worker, Failure(new Exception("Timed out shutting down worker.")))
        Behaviors.same
      case Dispatcher.WorkerStopped(worker, result) =>
        ctx.log.debug("Received message that worker stopped")
        workersStopping.getAndUpdate(workersStopping => {
          workersStopping.get(worker).map { _.cancel() }
          workersStopping.removed(worker)
        })
        workersRunning.getAndUpdate(_ - worker)
        results.getAndUpdate(_ + result) // TODO: This is dumb; it should keep track of which thread failed, etc.
        if (workersRunning.get().isEmpty) {
          ctx.log.debug("Stopping workers")
          val accResult = results.get().foldLeft(Success(()): Try[Unit])((acc, x) => if (acc.isFailure) { acc } else { x }) match { // There's probably a better way to accumulate this Set[Try[Unit]].
            case Failure(exception) => 
              ctx.log.debug("Found at least one failed worker")
              ctx.self ! WorkersStopped(Some(exception)) // TODO: Not sure about this, but the way things are now, the PekkoDispatcher doesn't notify anyone when all of the workers are stopped. Maybe that should be different.
            case Success(value) => 
              ctx.log.debug("Found no failed workers")
              ctx.self ! WorkersStopped(None) // TODO: Not sure about this, but the way things are now, the PekkoDispatcher doesn't notify anyone when all of the workers are stopped. Maybe that should be different.
          }
        }
        Behaviors.same
      case Dispatcher.WorkersStopped(None) =>
        ctx.log.info("All workers have been stopped, now stopping dispatcher.")
        stopCommander.get().fold {
          ctx.log.debug("Not stopping dispatcher")
          startCommander.get() ! Dispatcher.RunCompleted()
          waitForStart(ctx, workerFactory, appConfig, f)
        } { ref =>
          ctx.log.debug("Stopping dispatcher")
          (ref ! Dispatcher.Stopped())
          Behaviors.stopped
        }
      case Dispatcher.WorkersStopped(Some(e)) => 
        ctx.log.error("Error while stopping workers: {}", e.getMessage)
        stopCommander.get().fold {
          ctx.log.debug("Not stopping dispatcher")
          startCommander.get() ! Dispatcher.RunCompleted()
          waitForStart(ctx, workerFactory, appConfig, f)
        } { ref =>
          ctx.log.debug("Stopping dispatcher")
          (ref ! Dispatcher.Stopped())
          Behaviors.stopped
        }
  }
}
