package eusocialcooperation.scheduler.archiver

import eusocialcooperation.scheduler.datapoint.DataPoint

import eusocialcooperation.scheduler.Point

import eusocialcooperation.scheduler.Sample
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile
import com.typesafe.config.Config
import scala.util.Using.Releasable
import eusocialcooperation.scheduler.datapoint._
import scala.reflect.ClassTag

object PostgresArchiver {
    given Releasable[PostgresArchiver] = new Releasable[PostgresArchiver] {

      override def release(resource: PostgresArchiver): Unit = ???


    }
}

class PostgresArchiver(config: Config, experimentName: String, run: Int) extends Archiver[PostgresArchiver] {

    val dbConfig = DatabaseConfig.forConfig[PostgresProfile]("postgres_db", config)
    import dbConfig.profile.api._
    
    val metadataTable = TableQuery[PostgresMetadataTable]
    val sampleTable = TableQuery[PostgresSampleTable]
    val prospectTable = TableQuery[PostgresProspectTable]

    override def archivePointsData(pointsData: Seq[DataPoint[Sample]]): Unit = {
        // TODO: Address the execution context source
        implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

        val archive = for {
            point <- pointsData
            insertProspect <- Seq(prospectTable += (point.hostName, experimentName, run, point.sequenceNumber, point.value._1, point.value._2))
            insertMetadata <- Seq(metadataTable += (point.hostName, experimentName, run, point.sequenceNumber, implicitly[ClassTag[Point]].runtimeClass.getSimpleName, System.currentTimeMillis(), point.actorName, point.phase.toString(), point.parent.map(p => p.sequenceNumber)))
        } yield DBIO.seq(insertProspect, insertMetadata).transactionally
        val transaction = dbConfig.db.run(DBIO.sequence(archive))
    }

    override def archiveProspectsData(prospects: Seq[DataPoint[Point]]): Unit = ???

    override def archiveQueueLengthData(queueLengths: Seq[(Long, Int)]): Unit = ???

  
}
