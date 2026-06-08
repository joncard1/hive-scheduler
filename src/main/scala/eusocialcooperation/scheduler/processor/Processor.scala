package eusocialcooperation.scheduler.processor

import eusocialcooperation.scheduler.Demo
import scala.concurrent.duration.FiniteDuration
import com.typesafe.config.Config
import scala.concurrent.ExecutionContext

trait Processor {

  /** Run an entire experiment configuration, possibly including multiple runs.
   * 
   */
  def runExperiment(
    params: Demo.CommandLineParams,
    appConfig: Config
  )(implicit ec: scala.concurrent.ExecutionContext): Unit
  /**
   * Called for each run. Each run should be a repetition of an experiment configuration.
   */
  protected def runSingleExperiment(params: Demo.CommandLineParams, runNumber: Int, durationMs: FiniteDuration)(using ExecutionContext, Config): Unit
}
