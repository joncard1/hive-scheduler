package eusocialcooperation.scheduler.datapoint

import slick.jdbc.PostgresProfile.api._
import slick.lifted.PrimaryKey
import slick.lifted.Shape
import slick.lifted.ProvenShape

class MetadataTable(tag: Tag)
    extends Table[
      (Long, Int, String, String, Long, String, String, Option[Long])
    ](tag, "metadata") {

  def sequenceNumber = column[Long]("sequence_number")
  def run = column[Int]("run")
  def experimentName = column[String]("experiment_name")
  def typ = column[String]("type")
  def timestamp = column[Long]("timestamp")
  def actorName = column[String]("actor_name")
  def phase = column[String]("phase")
  def parentSequenceNumber = column[Option[Long]]("parent_sequence_number")

  def pk = primaryKey("pk_metadata", (sequenceNumber, run, experimentName, typ))

  override def * = (
    sequenceNumber,
    run,
    experimentName,
    typ,
    timestamp,
    actorName,
    phase,
    parentSequenceNumber
  )
}

class SampleTable(tag: Tag)
    extends Table[(Long, Int, String, Double, Double, Double)](tag, "points") {
  def sequenceNumber = column[Long]("sequence_number", O.AutoInc)
  def run = column[Int]("run")
  def experimentName = column[String]("experiment_name")
  def x = column[Double]("x")
  def y = column[Double]("y")
  def z = column[Double]("z")

  def pk = primaryKey("pk_points", (sequenceNumber, run, experimentName))

  override def * = (sequenceNumber, run, experimentName, x, y, z)
}

class ProspectTable(tag: Tag)
    extends Table[(Long, Int, String, Double, Double)](tag, "prospects") {
  def sequenceNumber = column[Long]("sequence_number", O.AutoInc)
  def run = column[Int]("run")
  def experimentName = column[String]("experiment_name")
  def x = column[Double]("x")
  def y = column[Double]("y")

  def pk = primaryKey("pk_prospects", (sequenceNumber, run, experimentName))

  override def * = (sequenceNumber, run, experimentName, x, y)
}
