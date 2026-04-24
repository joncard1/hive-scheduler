package eusocialcooperation

package object scheduler {
  val experimentConfigurationFileName = "experiment.conf"
  val experimentConfigPath = "config/"

    /**
     * The kernel function used to demonstrate the search space.
     */
  def kernel(x: BigDecimal, y: BigDecimal): BigDecimal = {
    Math.pow(Math.sin(2 * Math.PI * x.toDouble), 2) * Math.pow(Math.sin(2 * Math.PI * y.toDouble), 2)
  }

  type Point = (BigDecimal, BigDecimal)
  type Sample = (BigDecimal, BigDecimal, BigDecimal)
}
