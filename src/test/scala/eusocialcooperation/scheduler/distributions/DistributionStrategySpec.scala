package eusocialcooperation.scheduler.distributions

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import com.typesafe.config.Config

class DistributionStrategySpec extends AnyFunSuite with Matchers with MockFactory {

    test("Creates a UniformDistributionStrategy when config type is uniform") {
        given config: Config = stub[com.typesafe.config.Config]
        val distributionConfig = stub[com.typesafe.config.Config]
        (config.getConfig).when(DistributionStrategy.distributionConfigKey).returning(distributionConfig)
        (distributionConfig.getString).when("type").returning("uniform")

        val strategy = DistributionStrategy()
        strategy shouldBe a [UniformDistributionStrategy]
    }

    test("Creates a NormalDistributionStrategy when config type is normal") {
        given config: Config = stub[com.typesafe.config.Config]
        val distributionConfig = stub[com.typesafe.config.Config]
        (config.getConfig).when(DistributionStrategy.distributionConfigKey).returning(distributionConfig)
        (distributionConfig.getString).when("type").returning("normal")
        (distributionConfig.getDouble).when("mean").returning(0.5)
        (distributionConfig.getDouble).when("stddev").returning(0.2)

        val strategy = DistributionStrategy()
        strategy shouldBe a [NormalDistributionStrategy]
    }

    test("UniformDistributionStrategy generates values between 0 and 1") {
        val strategy = new UniformDistributionStrategy()
        for (_ <- 1 to 1000) {
        val value = strategy()
        value should be >= BigDecimal(0)
        value should be < BigDecimal(1)
        }
    }

    test("NormalDistributionStrategy generates values with the correct mean and stddev") {
        val mean = BigDecimal(5)
        val stddev = BigDecimal(2)
        val strategy = new NormalDistributionStrategy(mean, stddev)

        // Generate a large number of samples and check that their mean and stddev are close to the expected values.
        val samples = (1 to 10000).map(_ => strategy())
        val sampleMean = samples.sum / samples.size
        val sampleStddev = math.sqrt(samples.map(x => math.pow((x - sampleMean).toDouble, 2)).sum / samples.size)

        sampleMean should be(mean +- 0.1)
        sampleStddev should be(stddev.toDouble +- 0.1)
    }
  
}
