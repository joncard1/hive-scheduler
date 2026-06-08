package eusocialcooperation.scheduler

import org.scalatest.wordspec.AnyWordSpec
import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config
import scala.util.Using
import java.io.File
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import java.io.PrintWriter



class DemoSpecMultiJvmNode1 extends DemoSpecMultiJvm {
    override val nodeConfig = ConfigFactory.parseString("""
        pekko {
            remote.artery.canonical.hostname = "127.0.0.2"
            management.http.hostname = "127.0.0.2"
        }
    """).withFallback(appConfig)
}
class DemoSpecMultiJvmNode2 extends DemoSpecMultiJvm {
    override val nodeConfig = ConfigFactory.parseString("""
        pekko {
            remote.artery.canonical.hostname = "127.0.0.3"
            management.http.hostname = "127.0.0.3"
        }
    """).withFallback(appConfig)
}

trait DemoSpecMultiJvm extends AnyWordSpec with BeforeAndAfterAll with Matchers {
    val appConfig = ConfigFactory.parseString("""
    eusocialcooperation.scheduler {
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
                    service-name = hive
                }
            }
        }

        discovery.method = config
        discovery.config.services {
            hive {
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

    val nodeConfig: Config = appConfig
    val experimentsFolder = "target/demo-multijvm"

    override protected def beforeAll(): Unit = {
        if (File(experimentsFolder).mkdirs) {
            for(i <- 0 to 1) {
                val folderPath = s"${experimentsFolder}/exp${i}/config"
                if (File(folderPath).mkdirs) {
                    Using.resource(PrintWriter(s"${folderPath}/experiment.conf")) { writer =>
                        writer.write(s"eusocialcooperation.scheduler.duration = ${i * 2 + 1} s")
                    }
                }
            }
        }
    }
    override protected def afterAll(): Unit = {
        File(experimentsFolder).delete() // TODO: Won't actually work because the folder won't be empty.
    }

    "Demo" when {
        "run in a multi-JVM test" should {
            //"run a single experiment" in ???

            "run multiple experiments" in {
                val params = Demo.CommandLineParams(
                    None,
                    Some(experimentsFolder),
                    2,
                    true,
                    None,
                    None
                )

                Demo.runClusterMode(params, nodeConfig)
            }
        }
    } 
}
