package eusocialcooperation

package object scheduler {
    /**
     * The kernel function used to demonstrate the search space.
     */
  def kernel(x: BigDecimal, y: BigDecimal): BigDecimal = {
    Math.pow(Math.sin(2 * Math.PI * x.toDouble), 2) * Math.pow(Math.sin(2 * Math.PI * y.toDouble), 2)
  }

  type Sample = (BigDecimal, BigDecimal, BigDecimal)
}
