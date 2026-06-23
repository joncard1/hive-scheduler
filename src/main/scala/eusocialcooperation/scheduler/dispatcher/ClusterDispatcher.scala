package eusocialcooperation.scheduler.dispatcher

import org.apache.pekko.cluster.ddata.typed.scaladsl.DistributedData
import org.apache.pekko.cluster.ddata.ORSetKey
import org.apache.pekko.cluster.ddata.typed.scaladsl.ReplicatorMessageAdapter
import org.apache.pekko.cluster.ddata.ORSet
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import slick.basic.DatabaseConfig
import com.typesafe.config.Config
import eusocialcooperation.scheduler.datapoint.DataPoint
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import Dispatcher.WorkerFactory
import org.apache.pekko.cluster.ddata.typed.scaladsl.Replicator
import org.apache.pekko.cluster.ddata.SelfUniqueAddress
import scala.concurrent.duration.DurationLong
import slick.jdbc.PostgresProfile
import org.apache.pekko.cluster.ddata.ORMapKey
import org.apache.pekko.cluster.ddata.Flag
import org.apache.pekko.cluster.ddata.ORMap
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import scala.reflect.ClassTag
import org.apache.pekko.cluster.typed.ClusterSingleton
import org.apache.pekko.cluster.typed.SingletonActor
import eusocialcooperation.scheduler.dispatcher.Dispatcher.WorkersStopped
import eusocialcooperation.scheduler._
import eusocialcooperation.scheduler.datapoint.DataPointActor
import eusocialcooperation.scheduler.datapoint.PekkoDataPoint
import java.util.concurrent.atomic.AtomicReference

object ClusterDispatcher extends Dispatcher {

  val DispatcherServiceKey: ServiceKey[Dispatcher.Command] =
    ServiceKey("dispatcher-service-key")

  case class GetDataResponse(
      points: Replicator.GetResponse[ORSet[DataPointP]],
      replyTo: ActorRef[Dispatcher.RequestedPoints]
  ) extends Dispatcher.Command
  case class UpdateProspectsResponse(
      resp: Replicator.UpdateResponse[ORSet[DataPointP]]
  ) extends Dispatcher.Command
  case class UpdateCompletedResponse(
      resp: Replicator.UpdateResponse[ORMap[String, Flag]]
  ) extends Dispatcher.Command
  case class DataReset() extends Dispatcher.Command
  //case class DatabaseQueueDrained(msg: Dispatcher.WorkersStopped) extends Dispatcher.Command

  case class DatabaseReady() extends Dispatcher.Response

  val prospectSetKey = ORSetKey[DataPointP]("hive-scheduler-prospect-set")
  val completedMapKey = ORMapKey[String, Flag]("hive-scheduler-completed-map")

  def apply(pointsMemory: AtomicReference[Set[DataPoint[Sample]]], prospectsMemory: AtomicReference[Set[DataPoint[Point]]])(implicit config: Config, mdc: Map[String, String]): Behavior[Dispatcher.Command] =
    apply(pointsMemory, prospectsMemory, (duration, ctx, i, sampleUnit, prospectUnit) => ctx.spawn(Worker(kernel, ctx.self, duration)(using sampleUnit = sampleUnit, prospectUnit = prospectUnit), s"worker-$i"))

  def apply(
    pointsMemory: AtomicReference[Set[DataPoint[Sample]]],
    prospectsMemory: AtomicReference[Set[DataPoint[Point]]],
    f: WorkerFactory)(implicit
      config: Config,
      mdc: Map[String, String]
  ) = Behaviors.withMdc[Dispatcher.Command](mdc) {
        Behaviors.setup { ctx =>
          val dbConfig =
            DatabaseConfig.forConfig[PostgresProfile]("postgres_db", config)

          val sampleActor =
            ctx.spawn(DataPointActor[Sample](pointsMemory), "sampleActor")
          val pointActor = ctx.spawn(
            DataPointActor[Point](prospectsMemory),
            "pointActor"
          )

          val sampleUnit = PekkoDataPoint
                .getActorDataPointUnit[Sample](sampleActor, ctx.system.scheduler)
          val prospectUnit = PekkoDataPoint
                .getActorDataPointUnit[Point](pointActor, ctx.system.scheduler)

          val singletonManager = ClusterSingleton(ctx.system)
          val monitor = singletonManager.init(SingletonActor(ClusterMonitor(), "cluster-monitor"))

          DistributedData.withReplicatorMessageAdapter[Dispatcher.Command, ORSet[
            DataPointP
          ]] { setReplicator =>
            DistributedData
              .withReplicatorMessageAdapter[Dispatcher.Command, ORMap[String, Flag]] {
                mapReplicator =>
                  implicit val node: SelfUniqueAddress = DistributedData(
                    ctx.system
                  ).selfUniqueAddress

                  super.apply(f) { (ctx, run, experimentName, parentBehavior) => {
                    mapReplicator.askUpdate(
                      Replicator.Update(
                        completedMapKey,
                        ORMap.empty,
                        Replicator.WriteAll(5.seconds),
                        _
                      )(_ :+ (node.toString -> Flag.Disabled)),
                      UpdateCompletedResponse(_)
                    )
                    Behaviors.receiveMessage(
                      active(mapReplicator, setReplicator, sampleUnit, prospectUnit, dbConfig, parentBehavior(sampleUnit, prospectUnit), ctx)
                    )
                  }
              }
          }
      }
    }
  }

  protected def active(
      completedReplicator: ReplicatorMessageAdapter[Dispatcher.Command, ORMap[String, Flag]],
      setReplicator: ReplicatorMessageAdapter[Dispatcher.Command, ORSet[
        DataPointP
      ]],
      sampleUnit: DataPoint.DataPointUnit[Sample],
      pointUnit: DataPoint.DataPointUnit[Point],
      dbConfig: DatabaseConfig[PostgresProfile],
      parentBehavior: PartialFunction[Dispatcher.Command, Behavior[
        Dispatcher.Command
      ]],
      ctx: ActorContext[Dispatcher.Command]
  )(implicit
      node: SelfUniqueAddress
  ): PartialFunction[Dispatcher.Command, Behavior[Dispatcher.Command]] = {
    val t: PartialFunction[Dispatcher.Command, Behavior[Dispatcher.Command]] = {
      case msg @ Dispatcher.Stop(replyTo) =>
        ctx.log.debug("Received Dispatcher.Stop message. Closing database connection")
        dbConfig.db.close()
        parentBehavior(msg)
      case msg @ WorkersStopped(e) =>
        ctx.log.debug("Received WorkersStopped message, updating map and forwarding")
        completedReplicator.askUpdate(
          Replicator.Update(
            completedMapKey,
            ORMap.empty,
            Replicator.WriteAll(5.seconds),
            _
          )(_.put(node, node.toString, Flag.Enabled)),
          UpdateCompletedResponse(_)
        )
        ctx.log.info("As far as I know, I updated the map")
        //import ctx.executionContext
        //given FiniteDuration = 20.seconds
        //ctx.pipeToSelf(PostgresSQLDataPoint.drain())(_ => DatabaseQueueDrained(msg))
        parentBehavior(msg)
      //case msg @ DatabaseQueueDrained(workersStoppedMsg) =>
        //ctx.log.debug("Database drained")
        //parentBehavior(workersStoppedMsg)
      case GetDataResponse(
            resp @ Replicator.GetSuccess(`prospectSetKey`),
            replyTo
          ) =>
        ctx.log.info("Got data from replicator {}", resp.toString)
        resp.get(prospectSetKey).elements.foreach(x => ctx.log.debug("value: {}", x.value))
        replyTo ! Dispatcher.RequestedPoints(resp.get(prospectSetKey).elements)
        Behaviors.same
      case GetDataResponse(
          resp @ Replicator.GetDataDeleted(`prospectSetKey`),
          replyTo
        ) => 
        ctx.log.info("I think this means the data was requested, but it was deleted and not replaced by an empty set. I'm not sure about the difference.")
        replyTo ! Dispatcher.RequestedPoints(Set())
        Behaviors.same
      case GetDataResponse(
        resp @ Replicator.NotFound(`prospectSetKey`), replyTo) => 
          ctx.log.info("NotFound the prospect set. Not sure what to do about that")
          replyTo ! Dispatcher.RequestedPoints(Set())
          Behaviors.same
      case UpdateProspectsResponse(
            resp @ Replicator.UpdateSuccess(`prospectSetKey`)
          ) =>
        ctx.log.info(
          "Got an update response and don't know what to do with {}",
          resp.toString
        )
        Behaviors.same
      case UpdateProspectsResponse(resp) => 
        ctx.log.info("Updated the prospects data in a way I either don't care about or don't know what to do with. {}", resp.toString())
        Behaviors.same
      case UpdateCompletedResponse(resp @ Replicator.UpdateSuccess(completedMapKey)) => 
        ctx.log.info("Updated map data {}. Don't really do anything with it.", resp)
        Behaviors.same
      case UpdateCompletedResponse(resp @ Replicator.UpdateTimeout(completedMapKey)) =>
        ctx.log.info("Received a timeout error updating the completed map. Don't know how to handle that. {}", resp)
        Behaviors.same
      case Dispatcher.AddProspect(point, delayMs) =>
        ctx.log.info("Adding point {}", point.value)
        setReplicator.askUpdate(
          Replicator
            .Update(prospectSetKey, ORSet.empty, Replicator.WriteLocal, _)(
              _ :+ point
            ),
          UpdateProspectsResponse(_)
        )
        ctx.scheduleOnce(
          delayMs.milliseconds,
          ctx.self,
          Dispatcher.RemoveProspect(point)
        )
        Behaviors.same
      case Dispatcher.RemoveProspect(point) =>
        ctx.log.info("Removing point {}", point)
        setReplicator.askUpdate(
          Replicator.Update(
            prospectSetKey,
            ORSet.empty,
            Replicator.WriteLocal,
            _
          )(x => x.remove(point)),
          UpdateProspectsResponse(_)
        )
        Behaviors.same
      case Dispatcher.RequestPoints(replyTo) =>
        ctx.log.info("Requesting points")
        setReplicator.askGet(
          Replicator.Get(prospectSetKey, Replicator.ReadLocal, _),
          GetDataResponse(_, replyTo)
        )
        Behaviors.same
    }
    t `orElse` parentBehavior
  }
}
