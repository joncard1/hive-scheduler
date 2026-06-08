package eusocialcooperation.scheduler.processor

import org.apache.pekko.remote.testkit.MultiNodeConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.apache.pekko.remote.testkit.MultiNodeSpec
import org.apache.pekko.remote.testkit.MultiNodeSpecCallbacks
import org.scalatest.wordspec.AnyWordSpecLike
import org.apache.pekko.testkit.ImplicitSender
import org.apache.pekko.actor.typed.ActorSystem
import scala.concurrent.duration.DurationInt
import org.apache.pekko.actor.typed.scaladsl.adapter._
import eusocialcooperation.scheduler.processor.ClusterProcessor
import eusocialcooperation.scheduler.Demo
import scala.concurrent.ExecutionContext
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.ClusterEvent.CurrentClusterState
import org.apache.pekko.cluster.ClusterEvent.MemberUp
import eusocialcooperation.scheduler.dispatcher.Dispatcher
import org.apache.pekko.actor.typed.ActorRef
import scala.compiletime.uninitialized
import eusocialcooperation.scheduler.dispatcher.ClusterDispatcher
import eusocialcooperation.scheduler.Worker
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.util.Success

// TODO: Consider another MultiJvm test that is not a MultiNodeSpec to test creating the ActorSystem itself.

// Remember 'sudo ifconfig lo0 alias 127.0.0.2 up' to run this
object ClusterProcessorConfig extends MultiNodeConfig {
    val node1 = role("node1")
    val node2 = role("node2")

    // TODO: There is a bug to file here. The default service name in the MultiNodeConfig system is (in this case) ClusterProcessorSpec, but it appears that the service name gets normalized to all lowercase and then doesn't match. One has to provide a service name (in this case clusterprocessorspec).

    // TODO: A lot of this was made unnecessary by injecting dummy dispatchers and workers
    val theConfig = ConfigFactory.parseString("""
    eusocialcooperation.scheduler {
        duration = 3 s
        dispatcher.numWorkers = 2
        workers {
            distribution.type = uniform
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
                connectionPool = HikariCP
                dataSourceClass = org.postgresql.ds.PGSimpleDataSource
                properties {
                    serverName = "localhost"
                    portNumber = "5432"
                    databaseName = test
                }
                numThreads = 10
            }
        }
    }

    pekko {
        coordinated-shutdown.exit-jvm = on
        cluster {
            shutdown-after-unsuccessful-join-seed-nodes = 60s
            min-nr-of-members = 2
            downing-provider-class = org.apache.pekko.cluster.sbr.SplitBrainResolverProvider
        }
        extensions = ["org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap"]

        actor {
            provider = cluster
            serialization-bindings {
                "eusocialcooperation.scheduler.datapoint.DataPoint" = jackson-cbor
            }
        }

        management {
            cluster.bootstrap {
                contact-point-discovery {
                    discovery-method = config
                    service-name = clusterprocessorspec
                }
            }
        }

        discovery.method = config
        discovery.config.services {
            clusterprocessorspec {
                endpoints = [
                    {
                        host = "127.0.0.2"
                        port = 7626
                    },
                    {
                        host = "127.0.0.3"
                        port = 7626
                    }
                ]
            }
        }
    }
    """).withFallback(ConfigFactory.load())

    val appConfig = theConfig.getConfig("eusocialcooperation.scheduler")

    commonConfig(theConfig)

    this.nodeConfig(node1)(ConfigFactory.parseString("""
    pekko {
        remote.artery.canonical.hostname = "127.0.0.2"
        management.http.hostname = "127.0.0.2"
    }
    """))
    this.nodeConfig(node2)(ConfigFactory.parseString("""
    pekko {
        remote.artery.canonical.hostname = "127.0.0.3"
        management.http.hostname = "127.0.0.3"
    }
    """))
}

class ClusterProcessorSpecMultiJvmNode1 extends ClusterProcessorSpec
class ClusterProcessorSpecMultiJvmNode2 extends ClusterProcessorSpec

abstract class ClusterProcessorSpec extends MultiNodeSpec(ClusterProcessorConfig) with MultiNodeSpecCallbacks with AnyWordSpecLike with ImplicitSender with BeforeAndAfterAll with Matchers with MockFactory {
    import ClusterProcessorConfig._

    implicit val typedSystem: ActorSystem[Nothing] = system.toTyped

    def initialParticipants = roles.size

    override protected def beforeAll() = multiNodeSpecBeforeAll()
    override protected def afterAll() = multiNodeSpecAfterAll()

    var processor: ClusterProcessor = uninitialized
    val params = Demo.CommandLineParams(
        Some("testname")
        , None
        , 2
        , true
        , None
        , None
    )


    "ClusterDispatcher" must {
        "start properly" in within(10.seconds) {
            given ExecutionContext = typedSystem.executionContext
            given Map[String, String] = Map()

            Cluster(system).subscribe(testActor, classOf[MemberUp])
            system.spawn(ClusterDispatcher((duration, context, run, sampleUnit, prospectUnit) => {
                context.spawn(Behaviors.setup[Worker.Command] { ctx =>
                    ctx.scheduleOnce(duration, ctx.self, Worker.Stop(context.self))
                    Behaviors.receiveMessage {
                        case Worker.Stop(replyTo) =>
                            replyTo ! Dispatcher.WorkerStopped(ctx.self, Success(()))
                            Behaviors.stopped
                        case _ =>
                            Behaviors.same
                    }
            }, s"worker-${run}")})(using config = appConfig), "dispatcher")
            expectMsgClass(classOf[CurrentClusterState])

            // TODO: Better thought needs to be made whether we are expecting the Pekko configuration to be mixed in with the experiment configuration.
            processor = new ClusterProcessor(theConfig, Some(typedSystem))

            val firstAddress = node(node1).address
            val secondAddress = node(node2).address

            receiveN(2).collect { case MemberUp(m) => m.address }.toSet should be(
                Set(firstAddress, secondAddress))

            Cluster(system).unsubscribe(testActor)
        }

        "perform an experiment" in within(25.seconds) {
            processor.runExperiment(params, theConfig)(using ec = typedSystem.executionContext)
            // TODO: Would prefer better confirmation than just read the logs. Perhaps inject a dispatcher behavior rather than workerFactory. Or inject workerFactory and verify that the ClusterDispatcher is getting set up.

        }
    }
}
