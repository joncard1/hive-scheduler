package eusocialcooperation.scheduler.datapoint

import slick.jdbc.PostgresProfile.api._

class PostgresMetadataTable(tag: Tag)
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

class PostgresSampleTable(tag: Tag)
    extends Table[(Long, Int, String, BigDecimal, BigDecimal, BigDecimal)](tag, "points") {
  def sequenceNumber = column[Long]("sequence_number", O.AutoInc)
  def run = column[Int]("run")
  def experimentName = column[String]("experiment_name")
  def x = column[BigDecimal]("x")
  def y = column[BigDecimal]("y")
  def z = column[BigDecimal]("z")

  def pk = primaryKey("pk_points", (sequenceNumber, run, experimentName))

  override def * = (sequenceNumber, run, experimentName, x, y, z)
}

class PostgresProspectTable(tag: Tag)
    extends Table[(Long, Int, String, BigDecimal, BigDecimal)](tag, "prospects") {
  def sequenceNumber = column[Long]("sequence_number", O.AutoInc)
  def run = column[Int]("run")
  def experimentName = column[String]("experiment_name")
  def x = column[BigDecimal]("x")
  def y = column[BigDecimal]("y")

  def pk = primaryKey("pk_prospects", (sequenceNumber, run, experimentName))

  override def * = (sequenceNumber, run, experimentName, x, y)
}
