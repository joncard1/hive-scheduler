package eusocialcooperation.scheduler.distributions

import scala.util.Random
import com.typesafe.config.Config

object DistributionStrategy {
    val distributionConfigKey = "distribution"
    val distributionTypeConfigKey = "type"
    val normalMeanConfigKey = "mean"
    val normalStddevConfigKey = "stddev"
    val uniformType = "uniform"
    val normalType = "normal"

    def apply()(implicit config: Config): DistributionStrategy = {
        val distributionConfig = config.getConfig("distribution")
        val distributionType = distributionConfig.getString("type")

        distributionType match {
            case x if x.equals(uniformType) => new UniformDistributionStrategy()
            case x if x.equals(normalType) => new NormalDistributionStrategy(
                distributionConfig.getDouble("mean"),
                distributionConfig.getDouble("stddev")
            )
            case _ => throw new IllegalArgumentException(s"Unknown distribution type: $distributionType")
        }
    }
}

/**
  * Implementations of this trait define different strategies of generating the distribution of the preferences of the workers.
*/
trait DistributionStrategy {
  def apply(): BigDecimal
}

class UniformDistributionStrategy extends DistributionStrategy {
  override def apply(): BigDecimal = {
    BigDecimal(Random.nextDouble())
  }
}

class NormalDistributionStrategy(mean: BigDecimal, stddev: BigDecimal) extends DistributionStrategy {
  override def apply(): BigDecimal = {
    val u1 = Random.nextDouble()
    val u2 = Random.nextDouble()
    // The (partial) Box-Muller transform (https://en.wikipedia.org/wiki/Normal_distribution#Generating_values_from_normal_distribution).
    val z0 = math.sqrt(-2.0 * math.log(u1)) * math.cos(2.0 * math.Pi * u2)
    mean + stddev * BigDecimal(z0)
  }
}