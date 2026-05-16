package eusocialcooperation.scheduler.datapoint

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.ExecutionContext
import com.typesafe.config.ConfigFactory
import scala.jdk.CollectionConverters._
import org.scalatest.BeforeAndAfterEach
import scala.util.Using
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import eusocialcooperation.scheduler.Sample
import eusocialcooperation.scheduler.Point

class DataPointITSpec
    extends AnyFunSuite
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with Matchers {

  given ExecutionContext = ExecutionContext.global

  override protected def beforeEach(): Unit = {
    // Set up the database before tests
    val config = ConfigFactory.load()
    Using.resource(Database.forConfig("postgres_db")) { db =>
      Await.result(
        db.run(
          DBIO.seq(
            TableQuery[SampleTable].schema.dropIfExists,
            TableQuery[MetadataTable].schema.dropIfExists,
            TableQuery[ProspectTable].schema.dropIfExists
          )
        ),
        2.seconds
      )
    }
  }

  override def afterEach(): Unit = {
    // Clean up the database after tests
    val config = ConfigFactory.load()
    Using.resource(Database.forConfig("postgres_db")) { db =>
      Await.result(
        db.run(
          DBIO.seq(
            TableQuery[SampleTable].schema.dropIfExists,
            TableQuery[MetadataTable].schema.dropIfExists,
            TableQuery[ProspectTable].schema.dropIfExists
          )
        ),
        2.seconds
      )
    }
  }

  test("DataPoint[Sample] can be created in a database") {
    val run = 1
    val experimentName = "testExperiment"
    val db = Database.forConfig("postgres_db")
    given actorName: String = "actor1"
    given phase: DataPoint.Phase = DataPoint.Phase.Explorer
    given parent: Option[DataPoint[?]] = None
    val dataBind = PostgresSQLDataPoint.getDBSampleBind(run, experimentName, db)
    val sample: Sample = (1.0, 1.5, 2.0)
    val dp = dataBind(sample)
    dp.sequenceNumber `should` be > 0L
    dp.timestamp `should` be > 0L
    dp.actorName `shouldEqual` "actor1"
    dp.phase `shouldEqual` DataPoint.Phase.Explorer
    dp.value `shouldEqual` sample
    dp.parent `shouldEqual` None

    PostgresSQLDataPoint.metadataTable.result.map(metadata => {
      metadata.size shouldEqual 1
      val (
        actualSeqNum: Long,
        actualRun: Int,
        actualExpName: String,
        actualType: String,
        actualTimestamp: Long,
        actualActorName: String,
        actualPhase: String,
        actualParent: Option[Long]
      ) = metadata.head
      actualSeqNum `shouldEqual` dp.sequenceNumber
      actualRun shouldEqual run
      actualExpName shouldEqual experimentName
      actualType shouldEqual "Tuple3"
      actualTimestamp shouldEqual dp.timestamp
      actualActorName shouldEqual "actor1"
      actualPhase shouldEqual "Explorer"
      actualParent shouldEqual None
    })
  }

  test("DataPoint[Point] can be created in a database") {
    val run = 1
    val experimentName = "testExperiment"
    val db = Database.forConfig("postgres_db")
    given actorName: String = "actor1"
    given phase: DataPoint.Phase = DataPoint.Phase.Explorer
    given parent: Option[DataPoint[?]] = None
    val dataBind = PostgresSQLDataPoint.getDBProspectBind(run, experimentName, db)
    val point: Point = (1.0, 1.5)
    val dp = dataBind(point)
    dp.sequenceNumber `should` be > 0L
    dp.timestamp `should` be > 0L
    dp.actorName `shouldEqual` "actor1"
    dp.phase `shouldEqual` DataPoint.Phase.Explorer
    dp.value `shouldEqual` point
    dp.parent `shouldEqual` None

    PostgresSQLDataPoint.metadataTable.result.map(metadata => {
      metadata.size shouldEqual 1
      val (
        actualSeqNum: Long,
        actualRun: Int,
        actualExpName: String,
        actualType: String,
        actualTimestamp: Long,
        actualActorName: String,
        actualPhase: String,
        actualParent: Option[Long]
      ) = metadata.head
      actualSeqNum `shouldEqual` dp.sequenceNumber
      actualRun shouldEqual run
      actualExpName shouldEqual experimentName
      actualType shouldEqual "Tuple2"
      actualTimestamp shouldEqual dp.timestamp
      actualActorName shouldEqual "actor1"
      actualPhase shouldEqual "Explorer"
      actualParent shouldEqual None
    })
  }
}
