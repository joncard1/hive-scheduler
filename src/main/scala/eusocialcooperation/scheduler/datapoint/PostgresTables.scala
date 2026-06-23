package eusocialcooperation.scheduler.datapoint

import slick.jdbc.PostgresProfile.api._

class PostgresMetadataTable(tag: Tag)
    extends Table[
      (String, String, Int, Long, String, Long, String, String, Option[Long])
    ](tag, "metadata") {

  def hostname = column[String]("hostname")
  def experimentName = column[String]("experiment_name")
  def run = column[Int]("run")
  def sequenceNumber = column[Long]("sequence_number")
  def typ = column[String]("type")
  def timestamp = column[Long]("timestamp")
  def actorName = column[String]("actor_name")
  def phase = column[String]("phase")
  def parentSequenceNumber = column[Option[Long]]("parent_sequence_number")

  def pk = primaryKey("pk_metadata", (hostname, experimentName, run, sequenceNumber, typ))

  override def * = (
    hostname,
    experimentName,
    run,
    sequenceNumber,
    typ,
    timestamp,
    actorName,
    phase,
    parentSequenceNumber
  )
}

class PostgresSampleTable(tag: Tag)
    extends Table[(String, String, Int, Long, BigDecimal, BigDecimal, BigDecimal)](tag, "points") {
  def hostname = column[String]("hostname")
  def experimentName = column[String]("experiment_name")
  def run = column[Int]("run")
  def sequenceNumber = column[Long]("sequence_number")
  def x = column[BigDecimal]("x")
  def y = column[BigDecimal]("y")
  def z = column[BigDecimal]("z")

  def pk = primaryKey("pk_points", (hostname, experimentName, run, sequenceNumber))

  override def * = (hostname, experimentName, run, sequenceNumber, x, y, z)
}

class PostgresProspectTable(tag: Tag)
    extends Table[(String, String, Int, Long, BigDecimal, BigDecimal)](tag, "prospects") {
  def hostname = column[String]("hostname")
  def experimentName = column[String]("experiment_name")
  def run = column[Int]("run")
  def sequenceNumber = column[Long]("sequence_number")
  def x = column[BigDecimal]("x")
  def y = column[BigDecimal]("y")

  def pk = primaryKey("pk_prospects", (hostname, experimentName, run, sequenceNumber))

  override def * = (hostname, experimentName, run, sequenceNumber, x, y)
}

class PostgresQueueLengthTable(tag: Tag) extends Table[(String, String, Int, Long, Int)](tag, "queue_lenths") {
  def hostname = column[String]("hostname")
  def experimentName = column[String]("experimentName")
  def run = column[Int]("run")
  def timestamp = column[Long]("timestamp")
  def length = column[Int]("length")

  def pk = primaryKey("pk_prospects", (hostname, experimentName, run))

  override def * = (hostname, experimentName, run, timestamp, length)
}
