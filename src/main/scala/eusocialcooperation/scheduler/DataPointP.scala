package eusocialcooperation.scheduler

import eusocialcooperation.scheduler.datapoint.DataPoint

/**
  * This is simply a complicated alias for DataPoint[Point] that is required for the Pekko serializers to properly deserialize DataPoint[Point] because of type erasure.
  *
  * @param sequenceNumber
  * @param timestamp
  * @param actorName
  * @param phase
  * @param value
  * @param parent
  */
class DataPointP(sequenceNumber: Long, timestamp: Long, actorName: String, phase: DataPoint.Phase, value: Point, parent: Option[DataPoint[?]] = None) extends DataPoint[Point](sequenceNumber, timestamp, actorName, phase, value, parent)

object DataPointP {
  implicit def convertDataPointP(dp: DataPoint[Point]): DataPointP = new DataPointP(dp.sequenceNumber, dp.timestamp, dp.actorName, dp.phase, dp.value, dp.parent)
}