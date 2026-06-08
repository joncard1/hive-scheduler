package eusocialcooperation.scheduler.datapoint

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import com.typesafe.config.ConfigFactory
import scala.jdk.CollectionConverters._
import org.scalatest.BeforeAndAfterEach
import scala.util.Using
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import eusocialcooperation.scheduler.Sample
import eusocialcooperation.scheduler.Point
import slick.jdbc.JdbcProfile
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

class DataPointITSpec
    extends AnyFunSuite
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with Matchers {

  given ExecutionContext = ExecutionContext.global

  val profileConfigKey = "eusocialcooperation.scheduler.postgres_db"

  override protected def beforeEach(): Unit = {
    // Set up the database before tests
    val dbConfig = DatabaseConfig.forConfig[PostgresProfile](profileConfigKey)
    import dbConfig.profile.api._
    val db = dbConfig.db
    Await.result(
      db.run(
        DBIO.seq(
          TableQuery[PostgresSampleTable].schema.dropIfExists,
          TableQuery[PostgresMetadataTable].schema.dropIfExists,
          TableQuery[PostgresProspectTable].schema.dropIfExists
        )
      ),
      5.seconds
    )
  }

  override def afterEach(): Unit = {
    // Clean up the database after tests
    val dbConfig = DatabaseConfig.forConfig[PostgresProfile](profileConfigKey)
    import dbConfig.profile.api._
    val db = dbConfig.db
    Await.result(
      db.run(
        DBIO.seq(
          TableQuery[PostgresSampleTable].schema.dropIfExists,
          TableQuery[PostgresMetadataTable].schema.dropIfExists,
          TableQuery[PostgresProspectTable].schema.dropIfExists
        )
      ),
      5.seconds
    )
  }

  test("DataPoint[Sample] can be created in a database") {
    val run = 1
    val experimentName = "testExperiment"
    val dbConfig = DatabaseConfig.forConfig[PostgresProfile](profileConfigKey)
    given actorName: String = "actor1"
    given phase: DataPoint.Phase = DataPoint.Phase.Explorer
    given parent: Option[DataPoint[?]] = None
    val dataUnit = PostgresSQLDataPoint.getDBSampleUnit(run, experimentName, dbConfig)
    val sample: Sample = (BigDecimal(1.0), BigDecimal(1.5), BigDecimal(2.0))
    val dp = dataUnit(sample)
    dp.sequenceNumber `should` be > 0L
    dp.timestamp `should` be > 0L
    dp.actorName `shouldEqual` "actor1"
    dp.phase `shouldEqual` DataPoint.Phase.Explorer
    dp.value `shouldEqual` sample
    dp.parent `shouldEqual` None
    val db = dbConfig.db
    import dbConfig.profile.api._
    val metadataTable = TableQuery[PostgresMetadataTable]
    Await.result(
      db.run(
        metadataTable.result.map(metadata => {
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

  test("DataPoint[Point] can be created in a database") {
    val run = 1
    val experimentName = "testExperiment"
    given actorName: String = "actor1"
    given phase: DataPoint.Phase = DataPoint.Phase.Explorer
    given parent: Option[DataPoint[?]] = None
    val dbConfig = DatabaseConfig.forConfig[PostgresProfile](profileConfigKey)
    val dataUnit = PostgresSQLDataPoint.getDBProspectUnit(run, experimentName, dbConfig)
    val point: Point = (BigDecimal(1.0), BigDecimal(1.5))
    val dp = dataUnit(point)
    dp.sequenceNumber `should` be > 0L
    dp.timestamp `should` be > 0L
    dp.actorName `shouldEqual` "actor1"
    dp.phase `shouldEqual` DataPoint.Phase.Explorer
    dp.value `shouldEqual` point
    dp.parent `shouldEqual` None
    val db = dbConfig.db
    import dbConfig.profile.api._
    val metadataTable = TableQuery[PostgresMetadataTable]

    Await.result(
      db.run(
        metadataTable.result.map(metadata => {
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
