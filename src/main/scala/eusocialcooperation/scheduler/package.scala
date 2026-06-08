package eusocialcooperation

import eusocialcooperation.scheduler.datapoint.DataPoint

package object scheduler {

  /** The hard-coded file name containing the experiment configuration.
    */
  val experimentConfigurationFileName = "experiment.conf"

  /** The hard-coded path within the experiment directory where the experiment
    * configuration file is expected to be located.
    */
  val experimentConfigPath = "config/"

  /** The kernel function used to demonstrate the search space.
    */
  def kernel(x: BigDecimal, y: BigDecimal): BigDecimal = {
    Math.pow(Math.sin(2 * Math.PI * x.toDouble), 2) * Math.pow(
      Math.sin(2 * Math.PI * y.toDouble),
      2
    )
  }

  /** Type alias for a 2D point.
    */
  type Point = (BigDecimal, BigDecimal)

  /** Type alias for a sample, which consists of a 3D point.
    */
  type Sample = (BigDecimal, BigDecimal, BigDecimal)
  // TODO: This actually was never used, or never required. Review for whether it should just be removed.
  class DataPointS(sequenceNumber: Long, timestamp: Long, actorName: String, phase: DataPoint.Phase, value: Sample, parent: Option[DataPoint[?]] = None) extends DataPoint[Sample](sequenceNumber, timestamp, actorName, phase, value, parent)
}
