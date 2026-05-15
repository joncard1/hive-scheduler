package eusocialcooperation.scheduler.archiver

import eusocialcooperation.scheduler._
import com.typesafe.config.Config
import eusocialcooperation.scheduler.datapoint.DataPoint

object Archiver {
  def apply(experimentPath: String)/*(implicit config: Config)*/: Archiver = new DefaultArchiver(experimentPath)
}

trait Archiver {
  def archivePointsData(pointsData: Seq[DataPoint[Sample]]): Unit
  def archiveProspectsData(prospects: Seq[DataPoint[Point]]): Unit
  def archiveQueueLengthData(queueLengths: Seq[(Long, Int)]): Unit
}
