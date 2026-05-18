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
    Using.resource(Database.forConfig("postgres_db")) { db =>
      Await.result(
        db.run(
          DBIO.seq(
            TableQuery[PostgresSampleTable].schema.dropIfExists,
            TableQuery[PostgresMetadataTable].schema.dropIfExists,
            TableQuery[PostgresProspectTable].schema.dropIfExists
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
            TableQuery[PostgresSampleTable].schema.dropIfExists,
            TableQuery[PostgresMetadataTable].schema.dropIfExists,
            TableQuery[PostgresProspectTable].schema.dropIfExists
          )
        ),
        2.seconds
      )
    }
  }

  test("DataPoint[Sample] can be created in a database") {
    val run = 1
    val experimentName = "testExperiment"
    Using.resource(Database.forConfig("postgres_db")) { db => 
      given actorName: String = "actor1"
      given phase: DataPoint.Phase = DataPoint.Phase.Explorer
      given parent: Option[DataPoint[?]] = None
      val dataUnit = PostgresSQLDataPoint.getDBSampleUnit(run, experimentName, db)
      val sample: Sample = (BigDecimal(1.0), BigDecimal(1.5), BigDecimal(2.0))
      val dp = dataUnit(sample)
      dp.sequenceNumber `should` be > 0L
      dp.timestamp `should` be > 0L
      dp.actorName `shouldEqual` "actor1"
      dp.phase `shouldEqual` DataPoint.Phase.Explorer
      dp.value `shouldEqual` sample
      dp.parent `shouldEqual` None

      Await.result(
        db.run(
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
        )
        , 3.seconds
      )
    }
  }

  test("DataPoint[Point] can be created in a database") {
    val run = 1
    val experimentName = "testExperiment"
    Using.resource(Database.forConfig("postgres_db")) { db =>
      given actorName: String = "actor1"
      given phase: DataPoint.Phase = DataPoint.Phase.Explorer
      given parent: Option[DataPoint[?]] = None
      val dataUnit = PostgresSQLDataPoint.getDBProspectUnit(run, experimentName, db)
      val point: Point = (BigDecimal(1.0), BigDecimal(1.5))
      val dp = dataUnit(point)
      dp.sequenceNumber `should` be > 0L
      dp.timestamp `should` be > 0L
      dp.actorName `shouldEqual` "actor1"
      dp.phase `shouldEqual` DataPoint.Phase.Explorer
      dp.value `shouldEqual` point
      dp.parent `shouldEqual` None

      Await.result(
        db.run(
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
        )
          , 3.seconds
      )
    }
  }
}
