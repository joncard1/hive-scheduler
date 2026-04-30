package eusocialcooperation.scheduler.distributions

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import com.typesafe.config.Config

class ParetoDistributionStrategySpec
    extends AnyFunSuite
    with Matchers
    with MockFactory {

  // ---------------------------------------------------------------------------
  // Factory creation via DistributionStrategy
  // ---------------------------------------------------------------------------

  test("DistributionStrategy factory creates a ParetoDistributionStrategy") {
    given config: Config = stub[Config]
    val distributionConfig = stub[Config]
    (config.getConfig).when(DistributionStrategy.distributionConfigKey).returning(distributionConfig)
    (distributionConfig.getString).when("type").returning("pareto")
    (distributionConfig.getDouble).when("scale").returning(1.0)
    (distributionConfig.getDouble).when("shape").returning(2.0)

    val strategy = DistributionStrategy()
    strategy shouldBe a[ParetoDistributionStrategy]
  }

  test("DistributionStrategy factory throws when pareto scale is zero") {
    given config: Config = stub[Config]
    val distributionConfig = stub[Config]
    (config.getConfig).when(DistributionStrategy.distributionConfigKey).returning(distributionConfig)
    (distributionConfig.getString).when("type").returning("pareto")
    (distributionConfig.getDouble).when("scale").returning(0.0)
    (distributionConfig.getDouble).when("shape").returning(2.0)

    an[IllegalArgumentException] should be thrownBy DistributionStrategy()
  }

  test("DistributionStrategy factory throws when pareto scale is negative") {
    given config: Config = stub[Config]
    val distributionConfig = stub[Config]
    (config.getConfig).when(DistributionStrategy.distributionConfigKey).returning(distributionConfig)
    (distributionConfig.getString).when("type").returning("pareto")
    (distributionConfig.getDouble).when("scale").returning(-1.0)
    (distributionConfig.getDouble).when("shape").returning(2.0)

    an[IllegalArgumentException] should be thrownBy DistributionStrategy()
  }

  test("DistributionStrategy factory throws when pareto shape is zero") {
    given config: Config = stub[Config]
    val distributionConfig = stub[Config]
    (config.getConfig).when(DistributionStrategy.distributionConfigKey).returning(distributionConfig)
    (distributionConfig.getString).when("type").returning("pareto")
    (distributionConfig.getDouble).when("scale").returning(1.0)
    (distributionConfig.getDouble).when("shape").returning(0.0)

    an[IllegalArgumentException] should be thrownBy DistributionStrategy()
  }

  test("DistributionStrategy factory throws when pareto shape is negative") {
    given config: Config = stub[Config]
    val distributionConfig = stub[Config]
    (config.getConfig).when(DistributionStrategy.distributionConfigKey).returning(distributionConfig)
    (distributionConfig.getString).when("type").returning("pareto")
    (distributionConfig.getDouble).when("scale").returning(1.0)
    (distributionConfig.getDouble).when("shape").returning(-3.0)

    an[IllegalArgumentException] should be thrownBy DistributionStrategy()
  }

  // ---------------------------------------------------------------------------
  // Output range
  // ---------------------------------------------------------------------------

  test("ParetoDistributionStrategy output is always >= scale") {
    val scale = BigDecimal(1.0)
    val strategy = new ParetoDistributionStrategy(scale, shape = BigDecimal(2.0))
    for (_ <- 1 to 10_000) {
      strategy() should be >= scale
    }
  }

  test("ParetoDistributionStrategy output is always positive when scale > 0") {
    val strategy = new ParetoDistributionStrategy(
      scale = BigDecimal(0.5),
      shape = BigDecimal(1.5)
    )
    for (_ <- 1 to 10_000) {
      strategy() should be > BigDecimal(0)
    }
  }

  test("ParetoDistributionStrategy output scales proportionally with scale parameter") {
    // With the same shape, doubling scale should double every generated value.
    // We verify this by fixing the seed indirectly: both strategies share the
    // same shape, so we just confirm the ratio holds statistically via means.
    val n      = 10_000
    val shape  = BigDecimal(3.0)
    val s1     = new ParetoDistributionStrategy(BigDecimal(1.0), shape)
    val s2     = new ParetoDistributionStrategy(BigDecimal(2.0), shape)

    val mean1 = (1 to n).map(_ => s1()).sum / n
    val mean2 = (1 to n).map(_ => s2()).sum / n

    // mean ∝ scale, so mean2 / mean1 ≈ 2
    (mean2 / mean1).toDouble should be(2.0 +- 0.15)
  }

  // ---------------------------------------------------------------------------
  // Statistical properties
  //
  // For a Pareto(scale, shape) distribution the theoretical mean is
  //   shape * scale / (shape - 1)   (defined only when shape > 1).
  // ---------------------------------------------------------------------------

  test("ParetoDistributionStrategy sample mean approximates theoretical mean (shape=2)") {
    val scale  = BigDecimal(1.0)
    val shape  = BigDecimal(2.0)
    // Theoretical mean = 2 * 1 / (2 - 1) = 2.0
    val expectedMean = (shape * scale / (shape - 1)).toDouble

    val strategy = new ParetoDistributionStrategy(scale, shape)
    val samples  = (1 to 50_000).map(_ => strategy().toDouble)
    val mean     = samples.sum / samples.size

    mean should be(expectedMean +- 0.15)
  }

  test("ParetoDistributionStrategy sample mean approximates theoretical mean (shape=3, scale=2)") {
    val scale  = BigDecimal(2.0)
    val shape  = BigDecimal(3.0)
    // Theoretical mean = 3 * 2 / (3 - 1) = 3.0
    val expectedMean = (shape * scale / (shape - 1)).toDouble

    val strategy = new ParetoDistributionStrategy(scale, shape)
    val samples  = (1 to 50_000).map(_ => strategy().toDouble)
    val mean     = samples.sum / samples.size

    mean should be(expectedMean +- 0.15)
  }

  test("ParetoDistributionStrategy with large shape concentrates output near scale") {
    // With shape → ∞, the theoretical mean → scale and variance → 0.
    val scale    = BigDecimal(1.0)
    val strategy = new ParetoDistributionStrategy(scale, shape = BigDecimal(100.0))
    val samples  = (1 to 1_000).map(_ => strategy())

    val mean = samples.sum / samples.size
    mean should be(scale +- BigDecimal(0.05))
  }

  // ---------------------------------------------------------------------------
  // Heavy-tail property: the distribution has no finite mean when shape <= 1
  // (the formula still returns values, but they grow without bound).
  // We simply verify that output remains >= scale regardless of shape.
  // ---------------------------------------------------------------------------

  test("ParetoDistributionStrategy output is >= scale even when shape <= 1") {
    val scale    = BigDecimal(1.0)
    val strategy = new ParetoDistributionStrategy(scale, shape = BigDecimal(0.5))
    for (_ <- 1 to 1_000) {
      strategy() should be >= scale
    }
  }
}
