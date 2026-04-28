package eusocialcooperation.scheduler.distributions

import scala.util.Random
import com.typesafe.config.Config

/** The DistributionStrategy object is responsible for creating instances of
  * different distribution strategies. Distribution strategies represent
  * different distributions of preferences in the worker population. Changing
  * the distribution strategy should change the responsiveness of the system to
  * prospects.
  */
object DistributionStrategy {

  /** The configuration key for the distribution configuration.
    */
  val distributionConfigKey = "distribution"

  /** The configuration key for the type of the distribution to use. The full
    * key name is {@see #distributionConfigKey}.{@see
    * #distributionTypeConfigKey}. Allowed values are the names of the
    * {@see DistributionType} enum values.
    */
  val distributionTypeConfigKey = "type"

  /** The mean of the normal distribution to use if the normal distribution
    * strategy is chosen. The full key name is
    * {@see #distributionConfigKey}.{@see #normalMeanConfigKey}.
    */
  val normalMeanConfigKey = "mean"

  /** The standard deviation of the normal distribution to use if the normal
    * distribution strategy is chosen. The full key name is
    * {@see #distributionConfigKey}.{@see #normalStddevConfigKey}.
    */
  val normalStddevConfigKey = "stddev"

  val scaleConfigKey = "scale"
  val shapeConfigKey = "shape"

  /** The types of distribution that can be created by this factory.
    */
  enum DistributionType {
    case uniform, normal, pareto
  }

  /** Factory method for creating a distribution strategy based on the
    * configuration.
    */
  def apply()(implicit config: Config): DistributionStrategy = {
    val distributionConfig = config.getConfig("distribution")
    val distributionType =
      DistributionType.valueOf(distributionConfig.getString("type"))

    distributionType match {
      case DistributionType.uniform => new UniformDistributionStrategy()
      case DistributionType.normal  =>
        new NormalDistributionStrategy(
          distributionConfig.getDouble("mean"),
          distributionConfig.getDouble("stddev")
        )
      case DistributionType.pareto =>
        new ParetoDistributionStrategy(
          distributionConfig.getDouble("scale") match {
            case s if s > 0 => BigDecimal(s)
            case s => throw new IllegalArgumentException(
                s"Scale must be positive, but got $s"
              )
          },
          distributionConfig.getDouble("shape") match {
            case s if s > 0 => BigDecimal(s)
            case s => throw new IllegalArgumentException(
                s"Shape must be positive, but got $s"
              )
          }
        )
    }
  }
}

/** Implementations of this trait define different strategies of generating the
  * distribution of the preferences of the workers.
  */
trait DistributionStrategy {
  def apply(): BigDecimal
}

/** A strategy to generate preferences according to a uniform distribution.
  */
class UniformDistributionStrategy extends DistributionStrategy {
  override def apply(): BigDecimal = {
    BigDecimal(Random.nextDouble())
  }
}

/** A strategy to generate preferences according to a normal distribution. It
  * uses the Box-Muller transform.
  *
  * The impact of using a normal distribution strategy is not completely clear.
  * The low probability of workers with low preferences may cause a negative
  * response curve in the low-value prospects. Also, it may assign excessively
  * large numbers of workers to work moderately promising tasks, which may
  * starve the system of explorers.
  *
  * @param mean
  *   The mean of the normal distribution to use when generating preferences.
  * @param stddev
  *   The standard deviation of the normal distribution to use when generating
  *   preferences.
  */
class NormalDistributionStrategy(mean: BigDecimal, stddev: BigDecimal)
    extends DistributionStrategy {
  override def apply(): BigDecimal = {
    val u1 = Random.nextDouble()
    val u2 = Random.nextDouble()
    // The (partial) Box-Muller transform (https://en.wikipedia.org/wiki/Normal_distribution#Generating_values_from_normal_distribution).
    val z0 = math.sqrt(-2.0 * math.log(u1)) * math.cos(2.0 * math.Pi * u2)
    mean + stddev * BigDecimal(z0)
  }
}

/** A strategy to generate preferences according to a Pareto distribution.
  *
  * My hope is that a Pareto distribution will boost the responsiveness of the
  * system to prevent a negative response curve in the low value range.
  *
  * @param scale
  * The minimum value of the distribution. Should default to 0.
  * @param shape
  * A positive number that determines the steepness of the distribution.
  */
  // TODO: Implement this strategy and test it.
class ParetoDistributionStrategy(scale: BigDecimal, shape: BigDecimal)
    extends DistributionStrategy {
  override def apply(): BigDecimal = {
    val u = Random.nextDouble()
    // Not sure I see the point of subtracting from 1, but Copilot suggested it and I can't see any reason why it's a problem.
    scale / math.pow(1 - u, 1 / shape.toDouble)
  }
}