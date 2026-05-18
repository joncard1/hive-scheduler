package eusocialcooperation.scheduler.datapoint

import scala.concurrent.ExecutionContext
import eusocialcooperation.scheduler.Point
import scala.concurrent.Await

import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag
import eusocialcooperation.scheduler.Sample
import slick.jdbc.PostgresProfile.api._

object PostgresSQLDataPoint {

  val metadataTable = TableQuery[MetadataTable]
  val sampleTable = TableQuery[SampleTable]
  val prospectTable = TableQuery[ProspectTable]
  
  def getDBProspectUnit(run: Int, experimentName: String, db: Database)(using ec: ExecutionContext): DataPoint.DataPointUnit[Point] = {
    Await.result(db.run(DBIO.seq(
      prospectTable.schema.createIfNotExists
      , metadataTable.schema.createIfNotExists)), 5.seconds)
    (value) => (phase, actorName, parent) ?=> {
      val prospectInsertQuery = prospectTable.map(p => (p.run, p.experimentName, p.x, p.y)) returning prospectTable.map(_.sequenceNumber) into ((prospect, sequenceNumber) => (sequenceNumber, prospect._1, prospect._2, prospect._3, prospect._4))
      val metadataInsert = metadataTable returning metadataTable.map(_.sequenceNumber) into ((metadata, sequenceNumber) => (sequenceNumber, metadata._2, metadata._3, metadata._4, metadata._5, metadata._6, metadata._7, metadata._8))
      val result = (for {
        (sequenceNumber, run, experimentName, x, y) <- prospectInsertQuery += (
            run,
            experimentName,
            value._1.toDouble,
            value._2.toDouble
          )
        (sequenceNumber, run, experimentName, typ, timestamp, actorName, phase, parentSequenceNumber) <- metadataInsert += (
            sequenceNumber,
            run,
            experimentName,
            implicitly[ClassTag[Point]].runtimeClass.getSimpleName,
            System.currentTimeMillis(),
            actorName,
            phase.toString,
            parent.map(_.sequenceNumber)
          )
        } yield new DataPoint[Point](
          sequenceNumber,
          timestamp,
          actorName,
          DataPoint.Phase.valueOf(phase),
          (x, y),
          parent
        )).transactionally
      Await.result(db.run(result), 5.seconds)
    }
  }

  def getDBSampleUnit(run: Int, experimentName: String, db: Database)(using ec: ExecutionContext): DataPoint.DataPointUnit[Sample] = {
    Await.result(db.run(DBIO.seq(
      sampleTable.schema.createIfNotExists
      , metadataTable.schema.createIfNotExists)), 5.seconds)
    (value) => (phase, actorName, parent) ?=> {
      val sampleInsert = sampleTable.map(t => (t.run, t.experimentName, t.x, t.y, t.z)) returning sampleTable.map(_.sequenceNumber) into ((sample, sequenceNumber) => (sequenceNumber, sample._1, sample._2, sample._3, sample._4, sample._5))
      val metadataInsert = metadataTable returning metadataTable.map(_.sequenceNumber) into ((metadata, sequenceNumber) => (sequenceNumber, metadata._2, metadata._3, metadata._4, metadata._5, metadata._6, metadata._7, metadata._8))
      val result = (for {
        (sequenceNumber, run, experimentName, x, y, z) <- sampleInsert += (
          run,
          experimentName,
          value._1.toDouble,
          value._2.toDouble,
          value._3.toDouble
        )
        (sequenceNumber, run, experimentName, typ, timestamp, actorName, phase, parentSequenceNumber) <- metadataInsert += (
          sequenceNumber,
          run,
          experimentName,
          implicitly[ClassTag[Sample]].runtimeClass.getSimpleName,
          System.currentTimeMillis(),
          actorName,
          phase.toString,
          parent.map(_.sequenceNumber)
        )
      } yield new DataPoint[Sample](sequenceNumber, timestamp, actorName, DataPoint.Phase.valueOf(phase), (x, y, z), parent)).transactionally
      Await.result(
        db.run(result)
        , 5.seconds
      )
    }
  }
}
