package eusocialcooperation.scheduler.processor

import eusocialcooperation.scheduler.Demo
import com.typesafe.config.Config
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext
import org.slf4j.MDC
import scala.collection.JavaConverters._
import eusocialcooperation.scheduler.LoggingComponent
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.concurrent.Promise
import org.apache.pekko.cluster.Cluster
import scala.concurrent.Await
import java.util.concurrent.TimeoutException
import eusocialcooperation.scheduler.dispatcher.ClusterDispatcher
import org.apache.pekko.actor.typed.ActorRefResolver
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import eusocialcooperation.scheduler.dispatcher.Dispatcher
import scala.jdk.DurationConverters.JavaDurationOps
import org.apache.pekko.management.scaladsl.PekkoManagement
import scala.util.Using

// TODO: Not sure if there's a usefulness of inserting workerFactory
// TODO: Note: processors assume that the MDC values passed in are already applied for the thread they are called upon.
// TODO: Note to self: suppliedActorSystem needs to be an ActorSystem, not an ActorRef, so that the processor can monitor whether it joined the cluster or not. I think.
// The Actor System is of type ActorSystem[Nothing] to be consistent with the limitations of the testing framework.
// If the actor system is supplied, it must have a dispatcher started at "user/dispatcher"
class ClusterProcessor(/*, workerFactory: Dispatcher.WorkerFactory, */config: Config, suppliedActorSystem: Option[ActorSystem[Nothing]] = None)(using ExecutionContext) extends Processor with LoggingComponent {

  given Map[String, String] = Option(MDC.getCopyOfContextMap().asScala).getOrElse(Map()).toMap

  // TODO: Need to put the name of the system in the pod template in the kubernetes job.
  // TODO: This may be too much for a constructor. Maybe it needs an init function?

  // THIS IS A PLACEHOLDER TO HELP THE IDE WHILE I FIGURE THIS OUT
  // TODO: I think this needs to:
  //  1. Set up a monitor for MemberUp so it can error if it didn't join the cluster
  //  2. Start the dispatcher and get a reference to it out to the surrounding class somehow.
  val promise = Promise[Unit]()

  def defaultBehavior = Behaviors.setup[Nothing] { ctx =>
    // Start the dispatcher
    val actorRef = ctx.spawn(ClusterDispatcher()(using config = config.getConfig("eusocialcooperation.scheduler")), "dispatcher")

    // This would replace registerOnMemberUp below. It would only be worth it if I can get the Scheduler of a provided actor system. But if I can get an ActorRer[Dispatcher.Command] instead in the constructor, it would be a little easier to start the ClusterDispatcher as the guardian behavior.
    /*
    Cluster(ctx.system).subscribe(ctx.spawn(Behaviors.receive[MemberEvent] { (ctx, msg) => msg match
      case MemberUp(member) /* if member.address is this one */ => 
        promise.success(())
        Behaviors.same
    }, "cluster-monitor").toClassic,
    classOf[MemberUp])
    */
    Behaviors.empty
  }

  // TODO: Need to test with the created actor system here.
  val system: ActorSystem[Nothing] = suppliedActorSystem.getOrElse { 
    val system = ActorSystem(defaultBehavior, "hive", config)
    PekkoManagement(system).start()
    system
  }
  val resolver = ActorRefResolver(system)
  val dispatcher = resolver.resolveActorRef(s"${system.path.toString()}/dispatcher")

  // TODO: Make a decision whether this OK or if it should be done with the typed way. This is ALMOST the only use of the system, so if I can get rid of this, I can get the ActorRef[Dispatcher.Command] instead and I'd prefer that. I'd just have to assume that a provided ActorRef points to a sytsem that is definitely in a cluster. (I also need it to get the scheduler)
  Cluster(system).registerOnMemberUp {
    promise.success(())
  }
  try {
    Await.result(promise.future, 15.seconds)
  } catch {
    case e: TimeoutException => throw new Exception("Failed to join the cluster in the alotted time.", e)
  }

  // TODO: I'm not happy with using CommandLineParams here. These should be removed and parsed by this point and the required stuff should be explicit.
  // TODO: Processors apparently need the config already (in some cases), and there's a possibility of passing in a config that this not part of the one used to start the ActorSystem. Not sure if this is right, but the point of "runExperiment" is to use the configuration in the experiment folder, right? And we supply this to support running multiple experiments with a shared parent configuration and different folder configurations? So this is still correct?
  override def runExperiment(
    params: Demo.CommandLineParams,
    config: Config
  )(implicit ec: scala.concurrent.ExecutionContext): Unit = {
    require(params.experimentPath.isDefined, "The method runExperiment requires an experimentPath be set. If one was not provided by the command-line, a copy of CommandLineParams with the path set should have been provided by the caller.")

    // TODO: Refactor to a constant.
    val appConfig = config.getConfig("eusocialcooperation.scheduler")
    val duration = {
      appConfig.getDuration(Demo.durationConfigKey) match {
        case ms if ms.toMillis > 0 => ms.toScala
        case ms =>
          throw new IllegalArgumentException(
            s"${Demo.durationConfigKey} must be positive, but got $ms"
          )
      }
    }


    // TODO: Somewhere in here need to aadd the queue length sampling
    (1 to params.runs).foreach(runSingleExperiment(params, _, duration)(using config = appConfig))
    // TODO: This is to counter a side-effect-ful problem with the MDC. Re-consider whether it should noted and the caller can fix if the want.
    MDC.put(Demo.mdcKey, params.experimentPath.get)
  }

  override protected def runSingleExperiment(params: Demo.CommandLineParams, runNumber: Int, durationMs: FiniteDuration)(implicit ec: ExecutionContext, config: Config): Unit = {
    // TODO: This bit is now in two places. Consider moving up to Processor.
    val outputPath =
      Demo.runOutputPath(params, runNumber)
    new java.io.File(outputPath).mkdirs() // TODO: I think this failing doesn't cause a failure, which it probably should. I think that happened in the cluster.
    new java.io.File(s"${outputPath}logs").mkdirs() // TODO: I think this failing doesn't cause a failure, which it probably should. I think that happened in the cluster.
    // TODO: This is side-effect-ful. It correctly sets the MDC for the current thread, but it clears out the prior value.
    val mdcCloseable = MDC.putCloseable(Demo.mdcKey, outputPath)
    Using(mdcCloseable) { _ =>
      val askTimeoutDuration = durationMs.plus(5.seconds)
      given Timeout = askTimeoutDuration
      
      // TODO: implement queue length monitor

      given Scheduler = system.scheduler
      Await.result(dispatcher.ask(Dispatcher.StartRun(params.experimentPath.get, runNumber, durationMs, _)).map {
          case Dispatcher.RunCompleted() =>
            logger.debug("Run completed")
            //queueSampler.cancel()
        },
        askTimeoutDuration.plus(2.seconds)
      )
    }
  }

  
}
