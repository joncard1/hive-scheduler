package eusocialcooperation.scheduler.processor

import eusocialcooperation.scheduler.Demo
import org.slf4j.MDC
import scala.util.Using
import org.apache.pekko.util.Timeout
import org.apache.pekko.actor.typed.Scheduler
import eusocialcooperation.scheduler.dispatcher.Dispatcher
import eusocialcooperation.scheduler.LoggingComponent
import org.apache.pekko.actor.typed.ActorSystem
import eusocialcooperation.scheduler.dispatcher.PekkoDispatcher
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import eusocialcooperation.scheduler.datapoint.DataPoint
import eusocialcooperation.scheduler.Sample
import eusocialcooperation.scheduler.Point
import scala.concurrent.Await
import scalafx.application.Platform
import eusocialcooperation.scheduler.archiver.Archiver
import scala.concurrent.duration.FiniteDuration
import org.jfree.chart3d.Chart3D
import org.jfree.chart.JFreeChart
import eusocialcooperation.scheduler.charter.JFreeCharter
import org.jfree.chart3d.`export`.ExportUtils
import org.jfree.chart.ChartUtils
import eusocialcooperation.scheduler.MainLayoutController
import com.typesafe.config.Config
import scala.jdk.DurationConverters.JavaDurationOps
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import scala.concurrent.ExecutionContext

// TODO: This could probably be further refactored to the headless and non-headless versions.
class DefaultProcessor(mdcKey: String, controller: Option[MainLayoutController]) extends Processor with LoggingComponent {

  val currentActorSystem: AtomicReference[Option[ActorSystem[Dispatcher.Command]]] = AtomicReference(None)

  /** Runs the experiment asynchronously.
    *
    * Creates the Pekko actor system for each run, samples queue lengths,
    * generates charts, and archives data. When running with a GUI the
    * `controller` is updated with the generated charts via
    * `Platform.runLater`.
    *
    * @param params
    *   Parsed command-line parameters.
    * @param appConfig
    *   Loaded experiment configuration.
    * @param ec
    *   Implicit execution context used to schedule the Future.
    * @return
    *   A [[Future]] that completes when all runs have finished.
    */
  def runExperiment(
      params: Demo.CommandLineParams,
      config: Config
  )(implicit ec: scala.concurrent.ExecutionContext): Unit = {
    // TODO: Refactor this to a constant
    given appConfig: Config = config.getConfig("eusocialcooperation.scheduler")

    require(params.experimentPath.isDefined, "The method runExperiment requires an experimentPath be set. If one was not provided by the command-line, a copy of CommandLineParams with the path set should have been provided by the caller.")
    // TODO: The outputPath may not be necessarily be based on experiment path.
    MDC.put(mdcKey, params.experimentPath.get)
    given Map[String, String] = MDC.getCopyOfContextMap().asScala.toMap

    // TODO: This is extracted here and in the dispatcher, so that suggests this is factored wrong.
    val durationMs = {
      appConfig.getDuration(Demo.durationConfigKey) match {
        case ms if ms.toMillis > 0 => ms.toScala
        case ms =>
          throw new IllegalArgumentException(
            s"${Demo.durationConfigKey} must be positive, but got $ms"
          )
      }
    }

    (1 to params.runs).foreach(runSingleExperiment(params, _, durationMs))
    // TODO: Double-check this
    MDC.put(mdcKey, params.experimentPath.get)
  }

  def cancelCurrentExperiment(): Unit = {
    // TODO: This probably still has concurrency problems.
    currentActorSystem.getAndUpdate(x => {
      x.foreach { system =>
        if (!system.whenTerminated.isCompleted) {
          system.terminate()
        }
      }
      x
    })
  }


  override protected def runSingleExperiment(params: Demo.CommandLineParams, runNumber: Int, durationMs: FiniteDuration)(using ExecutionContext, Config): Unit = {
        // TODO: This should move to Processor.scala
        val outputPath =
          Demo.runOutputPath(params, runNumber)
        new java.io.File(outputPath).mkdirs() // TODO: I think this failing doesn't cause a failure, which it probably should. I think that happened in the cluster.
        new java.io.File(s"${outputPath}logs").mkdirs() // TODO: I think this failing doesn't cause a failure, which it probably should. I think that happened in the cluster.
        // TODO: This is side-effect-ful. It correctly sets the MDC for the current thread, but it clears out the prior value.
        val mdcCloseable = MDC.putCloseable(mdcKey, outputPath)
        Using(mdcCloseable) { _ =>
          given Map[String, String] = MDC.getCopyOfContextMap().asScala.toMap

          val points = AtomicReference(Set.empty[DataPoint[Sample]])
          val prospects = AtomicReference(Set.empty[DataPoint[Point]])

          logger.trace(
            "Creating dispatcher actor system for run {}.",
            runNumber
          )

          val dispatcher = try {
            ActorSystem(
              PekkoDispatcher(points, prospects),
              s"DispatcherSystem-${runNumber.formatted("%03d")}"
            )
          } catch {
            case e: Exception =>
              logger.error(
                "Error while creating dispatcher actor system: {}",
                e.getMessage
              )
              throw e
          }
          currentActorSystem.set(Some(dispatcher))
          logger.trace("Got dispatcher reference")

          logger.trace("Started queue sampler")
          val queueLengths = AtomicReference(List[(Long, Int)]())
          val startTime = System.currentTimeMillis()
          val queueSampler = dispatcher.scheduler.scheduleAtFixedRate(
            100.milliseconds,
            100.milliseconds
          ) { () =>
            given Timeout = 1.second
            given Scheduler = dispatcher.scheduler
            dispatcher
              .ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_))
              .map { points =>
                queueLengths.updateAndGet(x =>
                  (System.currentTimeMillis() - startTime, points.points.size) :: x
                )
              }
          }
          logger.trace("Started queue sampler")

          // TODO: Shouldn't schedule this. However, should send a StartExperiment and wait for the answer for at least durationMs.
          //dispatcher.scheduler.scheduleOnce(
            //durationMs,
            //() => {
          given Timeout = durationMs.plus(1.second)
          given Scheduler = dispatcher.scheduler
          Await.result(dispatcher.ask(Dispatcher.StartRun(params.experimentPath.get, 0, durationMs, _)).map {
              case Dispatcher.RunCompleted() =>
                queueSampler.cancel()
                dispatcher.terminate()
            },
            durationMs.plus(3.seconds)
          )
          try {
            Await.result(dispatcher.whenTerminated, 3.seconds)
          } catch {
            case e: Exception =>
              logger.error(
                "Error while waiting for system termination:",
                e
              )
          }
          currentActorSystem.set(None)

          logger.trace("Processing thread finished for run {}.", runNumber)

          // Not sure why, but the chart creation works fine outside the Platform thread, but if then try to add those charts to the UI, it doesn't work.
          if (!params.headless) {
            Platform.runLater(() => {
              val (
                pointsChart,
                points2DChart,
                clusterAnalysisChart,
                lengthSamplesChart
              ) = createAndSaveCharts(points, prospects, queueLengths, outputPath)
              controller.foreach { ctrl =>
                ctrl.pointsChartProperty() = Option(pointsChart)
                ctrl.points2DChartProperty() = Option(points2DChart)
                ctrl.clusterAnalysisChartProperty() =
                  Option(clusterAnalysisChart)
                ctrl.lengthSamplesChartProperty() = Option(lengthSamplesChart)
              }
            })
          } else {
            createAndSaveCharts(points, prospects, queueLengths, outputPath)
          }

          val archiver = Archiver(outputPath)
          archiver.archivePointsData(points.get().toSeq)
          archiver.archiveProspectsData(prospects.get().toSeq)
          archiver.archiveQueueLengthData(queueLengths.get())
        }
      }

    def createAndSaveCharts(
          points: AtomicReference[Set[DataPoint[Sample]]],
          prospects: AtomicReference[Set[DataPoint[Point]]],
          queueLengths: AtomicReference[List[(Long, Int)]],
          outputPath: String
      ): (Chart3D, JFreeChart, JFreeChart, JFreeChart) = {
        try {
          logger.info("Creating charts")
          val charter = new JFreeCharter()
          val pointsChart = charter.getMainChart(points.get(), prospects.get())
          val points2DChart =
            charter.getPoints2DChart(points.get(), prospects.get())
          val clusterAnalysisChart = charter.getClusterChart(points.get())
          val lengthSamplesChart =
            charter.getLengthSamplesChart(queueLengths.get().reverse)
          logger.info("Saving charts")
          ExportUtils.writeAsPNG(
            pointsChart,
            800,
            600,
            new java.io.File(s"${outputPath}main-chart.png")
          )
          logger.info(
            "Main chart created at {}",
            s"${outputPath}main-chart.png"
          )
          ChartUtils.saveChartAsPNG(
            new java.io.File(s"${outputPath}points2D.png"),
            points2DChart,
            800,
            600
          )
          ChartUtils.saveChartAsPNG(
            new java.io.File(s"${outputPath}cluster_chart.png"),
            clusterAnalysisChart,
            800,
            600
          )
          ChartUtils.saveChartAsPNG(
            new java.io.File(s"${outputPath}length_samples_chart.png"),
            lengthSamplesChart,
            800,
            600
          )
          logger.info("Charts created and saved to disk.")
          (pointsChart, points2DChart, clusterAnalysisChart, lengthSamplesChart)
        } catch {
          case e: Exception =>
            logger.error(s"Error while creating or saving charts: ${e}")
            throw e
        }
      }

}
