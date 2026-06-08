package eusocialcooperation.scheduler.dispatcher

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.apache.pekko.remote.testkit.MultiNodeConfig
import org.apache.pekko.remote.testkit.MultiNodeSpec
import org.apache.pekko.remote.testkit.MultiNodeSpecCallbacks
import org.apache.pekko.testkit.ImplicitSender
import com.typesafe.config._
import scala.concurrent.duration.DurationInt
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.util.Timeout
import org.apache.pekko.actor.typed.Scheduler
import eusocialcooperation.scheduler.dispatcher.Dispatcher.RunCompleted
import scala.concurrent.ExecutionContext
import org.scalatest.wordspec.AnyWordSpecLike
import org.apache.pekko.cluster.ClusterEvent.CurrentClusterState
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.ClusterEvent.MemberUp
import scala.concurrent.Await

object ClusterDispatcherConfig extends MultiNodeConfig {
    val node1 = role("node1")
    val node2 = role("node2")

    val comConfig = ConfigFactory.parseString("""
    pekko.actor.provider = cluster
    pekko.cluster.roles = [compute]
    pekko {
        actor {
            provider = cluster
            serialization-bindings {
                "eusocialcooperation.scheduler.DataPointP" = jackson-cbor
            }
        }
        remote {
            artery {
                canonical.hostname = "127.0.0.1"
                canonical.port = 0
            }
        }
        cluster {
            seed-nodes = [
                "pekko://CusterSystem@127.0.0.1:17356",
                "pekko://ClusterSystem@127.0.0.1:17357"
            ]
            downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
        }
    }
    """).withFallback(ConfigFactory.load())
    commonConfig(comConfig)
    
    //pekko.cluster.min-nr-of-members = 2
}

class ClusterDispatcherSpecMultiJvmNode1 extends ClusterDispatcherSpec
class ClusterDispatcherSpecMultiJvmNode2 extends ClusterDispatcherSpec

abstract class ClusterDispatcherSpec extends MultiNodeSpec(ClusterDispatcherConfig) with MultiNodeSpecCallbacks with AnyWordSpecLike with ImplicitSender with BeforeAndAfterAll with Matchers with MockFactory {
    import ClusterDispatcherConfig._

    implicit val typedSystem: ActorSystem[Nothing] = system.toTyped
    //cluster.manager ! Join(cluster.selfMember.address)

    def initialParticipants = roles.size

    override protected def beforeAll() = multiNodeSpecBeforeAll()

    override protected def afterAll() = multiNodeSpecAfterAll()

    "ClusterDispatcher" must {
        "notify all of the dispatchers when it is notified that all systems shut down" in within(15.seconds) {
            val firstAddress = node(node1).address
            val secondAddress = node(node2).address

            Cluster(system).subscribe(testActor, classOf[MemberUp])
            expectMsgClass(classOf[CurrentClusterState])

            Cluster(system).join(firstAddress)

            receiveN(roles.size).collect {
                case MemberUp(m) => m.address
            }.toSet should be {
                Set(firstAddress, secondAddress)
            }

            Cluster(system).unsubscribe(testActor)

            testConductor.enter("all-up")
        }

        "send a start message to the dispatcher" in within(15.seconds) {

            given Map[String, String] = Map(("experiment" -> "logs"))
            given ExecutionContext = typedSystem.executionContext
            val duration = 10.seconds
            given Scheduler = typedSystem.scheduler
            given Timeout = Timeout(duration.plus(5.second))
            given config: Config = ConfigFactory.parseString("""
            eusocialcooperation.scheduler {
                duration = 10 s
                dispatcher {
                    numWorkers = 2
                }
                workers {
                    distribution {
                        type = "uniform"
                    }
                    explorer {
                        numPointsToExplore = 10
                        threshold = 0.05
                        explorationRadius = 0.01
                        delayPerProspect = 10 ms
                    }
                    exploiter {
                        increment = 0.001
                        fuzziness = 0.01
                    }
                }
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
            """).withFallback(ConfigFactory.load()).getConfig("eusocialcooperation.scheduler")
            val runs = 1
            val experimentName = "test"
            
            val dispatcher = system.spawn(ClusterDispatcher(), "dispatcher")
            for(i <- 1 to runs) {
                Await.result(dispatcher.ask[Dispatcher.Response](Dispatcher.StartRun("experiment", i, 10.seconds, _)).map {
                    case RunCompleted() => println("Done")
                    case e => fail(s"${e}")
                },
                15.seconds)
            }
        }
    }
}