package eusocialcooperation.scheduler.dispatcher

import org.apache.pekko.cluster.ddata.typed.scaladsl.Replicator
import org.apache.pekko.cluster.ddata.ORMap
import org.apache.pekko.cluster.ddata.Flag
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.ddata.typed.scaladsl.DistributedData
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.cluster.ddata.typed.scaladsl.ReplicatorMessageAdapter
import scala.concurrent.duration.DurationInt
import org.apache.pekko.cluster.ddata.ORSet
import eusocialcooperation.scheduler._
import org.apache.pekko.cluster.ddata.SelfUniqueAddress

object ClusterMonitor {
  sealed trait Command
  case class CompletedDataSubscribe(msg: Replicator.SubscribeResponse[ORMap[String, Flag]]) extends Command
  case class DispatchersUpdated(listing: Receptionist.Listing) extends Command
  case class ProspectsDataUpdated(msg: Replicator.UpdateResponse[ORSet[DataPointP]]) extends Command
  case class CompletedDataUpdated(msg: Replicator.UpdateResponse[ORMap[String, Flag]]) extends Command

    def apply() = Behaviors.setup[Command] { ctx =>
        DistributedData.withReplicatorMessageAdapter[Command, ORSet[DataPointP]] { pointsReplicator =>
            DistributedData.withReplicatorMessageAdapter[Command, ORMap[String, Flag]] { completedReplicator => 
                given SelfUniqueAddress = DistributedData(ctx.system).selfUniqueAddress
                ctx.system.receptionist ! Receptionist.Subscribe(ClusterDispatcher.DispatcherServiceKey, ctx.messageAdapter(DispatchersUpdated(_)))
                completedReplicator.subscribe(ClusterDispatcher.completedMapKey, CompletedDataSubscribe(_))

                activeState(Set.empty, completedReplicator, pointsReplicator)
            }
        }
    }

    def activeState(
        setDispatchers: Set[ActorRef[Dispatcher.Command]]
        , completedReplicator: ReplicatorMessageAdapter[Command, ORMap[String, Flag]]
        , pointsReplicator: ReplicatorMessageAdapter[Command, ORSet[DataPointP]]
    )(using addr: SelfUniqueAddress): Behavior[Command] = Behaviors.receiveMessage[Command] {
        case DispatchersUpdated(listing) =>
            val services = listing.serviceInstances(ClusterDispatcher.DispatcherServiceKey)
            activeState(listing.serviceInstances(ClusterDispatcher.DispatcherServiceKey), completedReplicator, pointsReplicator)
        case CompletedDataSubscribe(msg @ Replicator.Changed(ClusterDispatcher.completedMapKey)) =>
            if (msg.dataValue.entries.values.foldLeft(true)((x, y) => x && y.enabled)) {
                completedReplicator.askUpdate(Replicator.Update(ClusterDispatcher.completedMapKey, ORMap.empty, Replicator.WriteAll(5.seconds))((data: ORMap[String,Flag]) => data.entries.keys.foldLeft(data)((map, key) => map.remove(key))), CompletedDataUpdated(_))
                Behaviors.same
            } else {
                Behaviors.same
            }
        case CompletedDataSubscribe(msg @ Replicator.Deleted(ClusterDispatcher.completedMapKey)) =>
            Behaviors.same
        case CompletedDataUpdated(Replicator.UpdateSuccess(ClusterDispatcher.completedMapKey)) =>
            pointsReplicator.askUpdate(Replicator.Update(ClusterDispatcher.prospectSetKey, ORSet.empty, Replicator.WriteAll(5.seconds))(_.clear(addr)), ProspectsDataUpdated(_))
            Behaviors.same
        case CompletedDataUpdated(Replicator.UpdateFailure(ClusterDispatcher.completedMapKey)) =>
            Behaviors.unhandled
        case ProspectsDataUpdated(Replicator.UpdateSuccess(ClusterDispatcher.prospectSetKey)) =>
            setDispatchers.foreach(dispatcher => dispatcher ! ClusterDispatcher.DataReset())
            Behaviors.same
    }
}
