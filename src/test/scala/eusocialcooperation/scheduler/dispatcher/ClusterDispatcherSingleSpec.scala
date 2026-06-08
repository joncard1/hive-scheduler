package eusocialcooperation.scheduler.dispatcher

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.typed.Cluster
import scala.util.Using.Releasable
import org.apache.pekko.actor.typed.ActorRef
import com.typesafe.config.Config
import org.scalatest.BeforeAndAfterEach
import org.apache.pekko.cluster.ddata.typed.scaladsl.Replicator
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.cluster.ddata.typed.scaladsl.DistributedData
import scala.concurrent.duration.DurationInt
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import org.apache.pekko.cluster.ddata.ORMap
import org.apache.pekko.cluster.ddata.ORSet
import org.apache.pekko.cluster.ddata.Flag
import org.apache.pekko.cluster.ddata.SelfUniqueAddress
import org.apache.pekko.cluster.ddata.Replicator.GetSuccess
import org.apache.pekko.cluster.ddata.Replicator.GetDataDeleted
import org.apache.pekko.cluster.ddata.Replicator.GetFailure
import org.apache.pekko.cluster.ddata.Replicator.NotFound
import org.apache.pekko.cluster.ddata.Replicator.Changed
import org.apache.pekko.cluster.ddata.Replicator.SubscribeResponse
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.util.Using
import eusocialcooperation.scheduler.DataPointP

class ClusterDispatcherSingleSpec extends DispatcherSpec with BeforeAndAfterEach {
    val config: Config = ConfigFactory.parseString("""
    pekko.actor.provider=cluster
    eusocialcooperation.scheduler {
        dispatcher.numWorkers = 1
        postgres_db {
            profile = "slick.jdbc.PostgresProfile$"
            db {
                connectionPool = "HikariCP"
                dataSourceClass = "org.postgresql.ds.PGSimpleDataSource"
                properties = {
                    serverName = "localhost"
                    portNumber = "5432"
                    databaseName = "test"
                }
                numThreads = 10
            }
        }
    }
    """)
    given dispatcherConfig: Config = config.getConfig("eusocialcooperation.scheduler")
    override val testKit = ActorTestKit(config)
    val cluster = Cluster(testKit.system)

    given Releasable[ActorRef[?]] = new Releasable {
        def release(resource: ActorRef[?]): Unit = {
            testKit.stop(resource)
        }
    }

    override def makeDispatcher(workerFactory: Dispatcher.WorkerFactory = noOpWorkerFactory) = {
        testKit.spawn(ClusterDispatcher(workerFactory))
    }

    override protected def afterEach(): Unit = {
        given ExecutionContext = testKit.system.executionContext
        val dd = DistributedData(testKit.system)
        given addr: SelfUniqueAddress = dd.selfUniqueAddress
        val updateMap = for {
            completedMap <- dd.replicator.ask(Replicator.Get(ClusterDispatcher.completedMapKey, Replicator.ReadAll(5.seconds)))
            x <- completedMap match {
                case resp @ GetSuccess(ClusterDispatcher.completedMapKey, _) => 
                    dd.replicator.ask[Replicator.UpdateResponse[ORMap[String, Flag]]](Replicator.Update(ClusterDispatcher.completedMapKey, ORMap.empty, Replicator.WriteAll(5.seconds), _) { ormap => 
                        ormap.entries.keys.foldLeft(ormap)(_.remove(_))
                    })
                case resp @ (GetSuccess(_, _)) =>
                    throw new Exception(s"Found the wrong things. I don't know what to do. Just give up. ${resp.toString()}")
                case e @ (GetDataDeleted(_, _) | GetFailure(_, _)) => 
                    throw new Exception(s"I don't know what happened here. Just give up. ${e.toString()}")
                case NotFound(_, _) => Future.successful(())
            }
        } yield x
        val updatePoints = for{
            set <- dd.replicator.ask(Replicator.Get(ClusterDispatcher.prospectSetKey, Replicator.ReadAll(5.seconds)))
            x <- set match {
                case e @ (GetDataDeleted(_, _) | GetFailure(_, _)) => 
                    throw new Exception(s"I don't know what happened here. Just give up. ${e.toString()}")
                case resp @ GetSuccess(ClusterDispatcher.prospectSetKey, _) => 
                    dd.replicator.ask[Replicator.UpdateResponse[ORSet[DataPointP]]](Replicator.Update(ClusterDispatcher.prospectSetKey, ORSet.empty, Replicator.WriteAll(5.seconds))(_.clear(addr)))
                case resp @ GetSuccess(_, _) => 
                    throw new Exception(s"Found the wrong things. I don't know what to do. Just give up. ${resp.toString()}")
                case NotFound(key, _) => Future.successful(())
            }
        } yield x
        Await.result(
            Future.sequence(Seq(updateMap, updatePoints)),
            5.seconds
        )
    }

    // TODO: Not sure how to test this
    // I think I've decided to test this in the multi-jvm tests.
    //test("ClusterDispatcher should get a ClusterMonitor when it starts up") { ??? }

    test("ClusterDispatcher should update the completed map when it receives the WorkersStopped message") {
        val dd = DistributedData(testKit.system)
        val subscribeProbe = testKit.createTestProbe[SubscribeResponse[ORMap[String, Flag]]]()
        val endProbe = testKit.createTestProbe[Dispatcher.Response]()        
        
        val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()
        val duration = 1.seconds
        Using.resource(testKit.spawn(Behaviors.monitor(dispatcherProbe.ref, ClusterDispatcher(noOpWorkerFactory)))) { dispatcher =>
            dispatcher ! Dispatcher.StartRun("test", 1, duration, endProbe.ref)
            dd.replicator ! Replicator.Subscribe(ClusterDispatcher.completedMapKey, subscribeProbe.ref)
            subscribeProbe.receiveMessage() match {
                case resp @ Changed(ClusterDispatcher.completedMapKey) => 
                    resp.get(ClusterDispatcher.completedMapKey).entries.get(dd.selfUniqueAddress.toString()) match {
                        case None => println("none")
                        case Some(Flag(x)) => x shouldBe false
                    }
            }

            dispatcherProbe.expectMessageType[Dispatcher.StartRun]
            dispatcherProbe.expectMessageType[ClusterDispatcher.UpdateCompletedResponse]
            dispatcherProbe.expectMessageType[Dispatcher.WorkerStopped](6.seconds)
            dispatcherProbe.expectMessageType[Dispatcher.WorkersStopped | ClusterDispatcher.UpdateCompletedResponse]
            dispatcherProbe.expectMessageType[Dispatcher.WorkersStopped | ClusterDispatcher.UpdateCompletedResponse]
            subscribeProbe.receiveMessage() match {
                case resp @ Changed(ClusterDispatcher.completedMapKey) => 
                    resp.get(ClusterDispatcher.completedMapKey).entries.get(dd.selfUniqueAddress.toString()) match {
                        case None => println("none")
                        case Some(Flag(x)) => x shouldBe true
                    }
            }
        }
    }

}
