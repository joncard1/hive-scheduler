package eusocialcooperation.scheduler.dispatcher

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.typed.ClusterSingleton
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.cluster.ddata.typed.scaladsl.DistributedData
import org.apache.pekko.cluster.ddata.SelfUniqueAddress
import org.apache.pekko.cluster.ddata.typed.scaladsl.Replicator
import org.apache.pekko.cluster.ddata.ORMap
import scala.concurrent.duration.DurationInt
import org.apache.pekko.cluster.ddata.Flag
import scala.util.Using
import scala.util.Using.Releasable
import org.apache.pekko.actor.typed.ActorRef
import eusocialcooperation.scheduler.dispatcher.ClusterMonitor.Command
import org.apache.pekko.cluster.typed.Cluster
import com.typesafe.config.ConfigFactory

/** These are the tests to run on a single instance of the ClusterMonitor. It
  * will run in a cluster mode, but a cluster of only one member. Multiple JVM
  * testing is in the multi-jvm folder.
  */
class ClusterMonitorSingleSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {
    val config = ConfigFactory.parseString("""
    pekko.actor.provider=cluster
    """)
    val testKit = ActorTestKit(config)
    val cluster = Cluster(testKit.system)

    given Releasable[ActorRef[ClusterMonitor.Command]] = new Releasable {
        def release(resource: ActorRef[Command]): Unit = {
            testKit.stop(resource)
        }
    }

    override protected def afterAll() = testKit.shutdownTestKit()

    test("ClusterMonitor subscribes to the dispatchers") {
        val monitorProbe = testKit.createTestProbe[ClusterMonitor.Command]()
        Using.resource(testKit.spawn(Behaviors.monitor(monitorProbe.ref, ClusterMonitor()), "cluster-monitor")) { ref =>
            monitorProbe.expectMessageType[ClusterMonitor.DispatchersUpdated]
        }
    }

    test("ClusterMonitor subscribes to the completed map") {
        val monitorProbe = testKit.createTestProbe[ClusterMonitor.Command]()
        Using.resource(testKit.spawn(Behaviors.monitor(monitorProbe.ref, ClusterMonitor()), "cluster-monitor")) { ref =>
            val dd = DistributedData(testKit.system)
            given SelfUniqueAddress = dd.selfUniqueAddress
            dd.replicator ! Replicator.Update(
                ClusterDispatcher.completedMapKey,
                ORMap.empty,
                Replicator.WriteAll(5.seconds),
                testKit.system.ignoreRef
            )((x: ORMap[String, Flag]) => x :+ ("node1", Flag(false)))
            monitorProbe.expectMessageType[ClusterMonitor.DispatchersUpdated]
            monitorProbe.expectMessageType[ClusterMonitor.CompletedDataSubscribe]
        }
    }

    // TODO: This test has a bunch of scenarios that I don't know what to do with, like multiple, different types of notices that the data was deleted.
    test("ClusterMonitor notifies all of the dispatchers when it is notified that all systems shut down") {
        val singletonManager = ClusterSingleton(testKit.system)
        val dispatcherProbe1 = testKit.createTestProbe[Dispatcher.Command]()
        val dispatcherProbe2 = testKit.createTestProbe[Dispatcher.Command]()
        val monitorProbe = testKit.createTestProbe[ClusterMonitor.Command]()
        val monitor = testKit.spawn(Behaviors.monitor(monitorProbe.ref, ClusterMonitor()), "cluster-monitor")
        // Have to ignore the actual message received from the receptionist
        monitorProbe.expectMessageType[ClusterMonitor.DispatchersUpdated]
        monitor ! ClusterMonitor.DispatchersUpdated(Receptionist.Listing(ClusterDispatcher.DispatcherServiceKey, Set(dispatcherProbe1.ref, dispatcherProbe2.ref)))


        val dd = DistributedData(testKit.system)
        given SelfUniqueAddress = dd.selfUniqueAddress
        dd.replicator ! Replicator.Update(
                ClusterDispatcher.completedMapKey,
                ORMap.empty,
                Replicator.WriteAll(5.seconds),
                testKit.system.ignoreRef
            )((x: ORMap[String, Flag]) => x :+ ("node1", Flag(false)))
        dd.replicator ! Replicator.Update(
                ClusterDispatcher.completedMapKey,
                ORMap.empty,
                Replicator.WriteAll(5.seconds),
                testKit.system.ignoreRef
            )((x: ORMap[String, Flag]) => x :+ ("node2", Flag(false)))
        dd.replicator ! Replicator.Update(
                ClusterDispatcher.completedMapKey,
                ORMap.empty,
                Replicator.WriteAll(5.seconds),
                testKit.system.ignoreRef
            )((x: ORMap[String, Flag]) => x :+ ("node1", Flag(true)))
        dd.replicator ! Replicator.Update(
                ClusterDispatcher.completedMapKey,
                ORMap.empty,
                Replicator.WriteAll(5.seconds),
                testKit.system.ignoreRef
            )((x: ORMap[String, Flag]) => x :+ ("node2", Flag(true)))

        dispatcherProbe1.expectMessageType[ClusterDispatcher.DataReset]
        dispatcherProbe2.expectMessageType[ClusterDispatcher.DataReset]
    }
}
