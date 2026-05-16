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

  def getDBProspectBind(run: Int, experimentName: String, db: Database)(using ec: ExecutionContext): DataPoint.DataPointBind[Point] = {
    Await.result(db.run(DBIO.seq(
      prospectTable.schema.createIfNotExists
      , metadataTable.schema.createIfNotExists)), 5.seconds)
    (value) => (phase, actorName, parent) ?=> {
      val prospectInsert = prospectTable returning prospectTable.map(_.sequenceNumber) into ((prospect, sequenceNumber) => (sequenceNumber, prospect._2, prospect._3, prospect._4, prospect._5)) += (
        0,
        run,
        experimentName,
        value._1.toDouble,
        value._2.toDouble
      )
      Await.result(db.run(prospectInsert).flatMap(
        (sequenceNumber, run, experimentName, x, y) => {
          val metadataInsert = metadataTable returning metadataTable.map(_.sequenceNumber) into ((metadata, sequenceNumber) => (sequenceNumber, metadata._2, metadata._3, metadata._4, metadata._5, metadata._6, metadata._7, metadata._8)) += (
            sequenceNumber,
            run,
            experimentName,
            implicitly[ClassTag[Point]].runtimeClass.getSimpleName,
            System.currentTimeMillis(),
            actorName,
            phase.toString,
            parent.map(_.sequenceNumber)
          ) 
          db.run(metadataInsert).map(
            (metadataSequenceNumber, run, experimentName, typ, timestamp, actorName, phase, parentSequenceNumber) => {
              new DataPoint[Point](
                metadataSequenceNumber,
                timestamp,
                actorName,
                DataPoint.Phase.valueOf(phase),
                (x, y),
                parent
              )
            }
          )
        }
      ), 5.seconds)
    }
  }

  def getDBSampleBind(run: Int, experimentName: String, db: Database)(using ec: ExecutionContext): DataPoint.DataPointBind[Sample] = {
    Await.result(db.run(DBIO.seq(
      sampleTable.schema.createIfNotExists
      , metadataTable.schema.createIfNotExists)), 5.seconds)
    (value) => (phase, actorName, parent) ?=> {
      val sampleInsert = sampleTable returning sampleTable.map(_.sequenceNumber) into ((sample, sequenceNumber) => (sequenceNumber, sample._2, sample._3, sample._4, sample._5, sample._6)) += (
        0, // This is a placeholder value for the sequence number, which will be replaced by the database's auto-incrementing primary key.
        run,
        experimentName,
        value._1.toDouble,
        value._2.toDouble,
        value._3.toDouble
      )
      Await.result(db.run(sampleInsert).flatMap(
        (sequenceNumber, run, experimentName, x, y, z) => {
          val metadataInsert = metadataTable returning metadataTable.map(_.sequenceNumber) into ((metadata, sequenceNumber) => (sequenceNumber, metadata._2, metadata._3, metadata._4, metadata._5, metadata._6, metadata._7, metadata._8)) += (
            sequenceNumber,
            run,
            experimentName,
            implicitly[ClassTag[Sample]].runtimeClass.getSimpleName,
            System.currentTimeMillis(),
            actorName,
            phase.toString,
            parent.map(_.sequenceNumber)
          ) 
          db.run(metadataInsert).map(
            (metadataSequenceNumber, run, experimentName, typ, timestamp, actorName, phase, parentSequenceNumber) => {
              new DataPoint[Sample](
                metadataSequenceNumber,
                timestamp,
                actorName,
                DataPoint.Phase.valueOf(phase),
                (x, y, z),
                parent
              )
            }
          )
        }
      ), 5.seconds)
    }
  }
}
