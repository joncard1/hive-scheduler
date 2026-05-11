package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.ActorSystem
import scala.concurrent.duration.DurationInt
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.DurationConverters._
import scala.jdk.CollectionConverters._
import com.typesafe.config.ConfigFactory
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import org.apache.pekko.util.Timeout
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import com.typesafe.config.Config
import org.slf4j.MDC
import scala.concurrent.Future
import eusocialcooperation.scheduler.charter.JFreeCharter
import org.jfree.chart3d.`export`.ExportUtils
import org.jfree.chart.ChartUtils
import scalafx.application.Platform
import org.jfree.chart.JFreeChart
import org.jfree.chart3d.Chart3D
import scala.util.Using
import eusocialcooperation.scheduler.archiver.Archiver

/** The main entry point of the application. When this is started, the system is
  * constructed in 2 parts: the UI and the processing thread. The UI is
  * initialized in an initial state where it indicates the data is being
  * generated, and then when the processing thread completes, it passes the data
  * generated through a utility class {@see Charter} to create the charts to
  * display and then passes those charts to the UI through a JavaFX Property.
  * The charts are passed in rather than the data because the system can be run
  * in headless mode to save the charts to PNG files and then leave, enabling
  * the running of multiple experiments serially.
  *
  * All data, including the logs, are placed in the experiment path which is
  * provided on the command line. The format of the command line is:
  * `Demo <experimentPath> [--headless=true|false] [--runs=N] [--parent=path]`
  *
  * When running with a GUI, the JavaFX application is launched via [[GUIApp]],
  * which retrieves the parsed parameters and configuration from this singleton.
  * In headless mode the experiment runs directly without launching a GUI.
  */
object Demo extends LoggingComponent {

  /** Parsed command-line parameters.
    *
    * @param experimentPath
    *   The path to the experiment directory.
    * @param runs
    *   The number of runs to execute.
    * @param headless
    *   Whether to run in headless mode (no GUI).
    * @param parentPath
    *   The optional parent experiment path for configuration fallback.
    */
  case class CommandLineParams(
      experimentPath: String,
      runs: Int,
      headless: Boolean,
      parentPath: Option[String]
  )

  private[scheduler] val durationConfigKey = "duration"
  private[scheduler] val mdcKey = "experiment"
  private[scheduler] val headlessModeFxmlFileName = "/headless-mode.fxml"
  val mainLayoutFxmlFileName = "/main-layout.fxml"

  /** Shared state read by [[GUIApp]] after [[main]] has populated it. */
  @volatile private[scheduler] var commandLineParams: CommandLineParams = _
  @volatile private[scheduler] var config: Config = _

  /** Holds a reference to the currently running actor system so that
    * [[GUIApp.stop]] can cancel it when the window closes.
    */
  private[scheduler] val currentActorSystem
      : AtomicReference[Option[ActorSystem[Dispatcher.Command]]] =
    new AtomicReference(None)

  def parseRunsParam(namedParameters: Map[String, String]): Int = {
    namedParameters.get("runs") match {
      case None => 1
      case Some(rawRuns) =>
        try {
          val runs = rawRuns.toInt
          if (runs < 1) {
            throw new IllegalArgumentException(
              s"--runs must be at least 1, but got $runs"
            )
          }
          runs
        } catch {
          case _: NumberFormatException =>
            throw new IllegalArgumentException(
              s"--runs must be an integer, but got '$rawRuns'"
            )
        }
    }
  }

  def runOutputPath(experimentPath: String, runNumber: Int, runs: Int): String = {
    if (runs > 1) {
      s"${experimentPath}run_${runNumber.formatted("%03d")}/"
    } else {
      experimentPath
    }
  }

  def effectiveHeadless(requestedHeadless: Boolean, runs: Int): Boolean =
    requestedHeadless || runs > 1

  def fxmlFileName(headless: Boolean): String =
    if (headless) headlessModeFxmlFileName else mainLayoutFxmlFileName

  /** Parses raw command-line arguments into a [[CommandLineParams]] instance.
    *
    * Named arguments use the format `--key=value`; a bare flag `--key` is
    * treated as `--key=true`. The first positional argument is the experiment
    * path; if omitted, `"testconf/"` is used as a default.
    *
    * @param args
    *   Raw command-line arguments.
    * @return
    *   Parsed [[CommandLineParams]].
    */
  def parseCommandLineParams(args: Array[String]): CommandLineParams = {
    val (namedArgs, unnamedArgs) = args.partition(_.startsWith("--"))
    val namedParameters: Map[String, String] = namedArgs.map { arg =>
      arg.stripPrefix("--").split("=", 2) match {
        case Array(k, v) => k -> v
        case Array(k)    => k -> "true"
      }
    }.toMap

    val experimentPath = unnamedArgs.headOption match {
      case None => "testconf/"
      case Some(path) if path.isEmpty() =>
        throw new IllegalArgumentException("Experiment path must be non-empty.")
      case Some(path) if !path.endsWith("/") => path + "/"
      case Some(path)                        => path
    } match {
      case path if !File(path).exists() =>
        throw new IllegalArgumentException(
          s"Experiment path '$path' does not exist."
        )
      case path => path
    }

    val runs = parseRunsParam(namedParameters)
    val requestedHeadless = namedParameters.get("headless").exists(_.toBoolean)
    val headless = effectiveHeadless(requestedHeadless, runs)

    val parentPath = namedParameters.get("parent") match {
      case None => None
      case Some(path) if path.isEmpty() =>
        throw new IllegalArgumentException("Parent path must be non-empty.")
      case Some(path) if !path.endsWith("/") => Some(path + "/")
      case Some(path)                        => Some(path)
    } match {
      case None => None
      case Some(path) if !File(path).exists() =>
        throw new IllegalArgumentException(
          s"Parent path '$path' does not exist."
        )
      case Some(path) => Some(path)
    }

    CommandLineParams(experimentPath, runs, headless, parentPath)
  }

  /** Loads the experiment configuration for the given parameters.
    *
    * Puts the `<experimentPath>/config` folder on the classpath to allow
    * per-experiment `experiment.conf` files to be discovered. When a
    * `parentPath` is provided its configuration is used as a fallback.
    *
    * @param params
    *   Parsed command-line parameters.
    * @return
    *   Loaded [[Config]] scoped to this application's package.
    */
  def loadConfig(params: CommandLineParams): Config = {
    val currentLoader = Thread.currentThread().getContextClassLoader
    def getConfigLoader(path: String): URLClassLoader = {
      val configFile = new File(s"${path}$experimentConfigPath")
      val folderUrl: URL = configFile.toURI.toURL
      new URLClassLoader(Array(folderUrl), currentLoader)
    }
    def getConfigFromPath(path: String) =
      ConfigFactory.load(getConfigLoader(path), experimentConfigurationFileName)

    params.parentPath
      .fold(getConfigFromPath(params.experimentPath)) { parent =>
        getConfigFromPath(params.experimentPath)
          .withFallback(getConfigFromPath(parent))
      }
      .getConfig(Demo.getClass.getPackage.getName)
  }

  /** Cancels the currently running actor system, if any, by terminating it. */
  def cancelCurrentExperiment(): Unit = {
    currentActorSystem.get().foreach { system =>
      if (!system.whenTerminated.isCompleted) {
        system.terminate()
      }
    }
  }

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
    * @param controller
    *   Optional UI controller to receive generated charts (non-headless only).
    * @param ec
    *   Implicit execution context used to schedule the Future.
    * @return
    *   A [[Future]] that completes when all runs have finished.
    */
  def runExperiment(
      params: CommandLineParams,
      appConfig: Config,
      controller: Option[MainLayoutController]
  )(implicit ec: scala.concurrent.ExecutionContext): Future[Unit] = {
    given Config = appConfig

    MDC.put(mdcKey, params.experimentPath)
    given Map[String, String] = MDC.getCopyOfContextMap().asScala.toMap

    val durationMs = {
      appConfig.getDuration(durationConfigKey) match {
        case ms if ms.toMillis > 0 => ms.toScala
        case ms =>
          throw new IllegalArgumentException(
            s"${durationConfigKey} must be positive, but got $ms"
          )
      }
    }

    Future {
      MDC.put(mdcKey, params.experimentPath)

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

      def runSingleExperiment(runNumber: Int): Unit = {
        val outputPath =
          runOutputPath(params.experimentPath, runNumber, params.runs)
        new java.io.File(outputPath).mkdirs()
        new java.io.File(s"${outputPath}logs").mkdirs()
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
              Dispatcher(points, prospects),
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

          dispatcher.scheduler.scheduleOnce(
            durationMs,
            () => {
              given Timeout = 5.seconds
              given Scheduler = dispatcher.scheduler
              queueSampler.cancel()
              Await.result(
                dispatcher.ask(Dispatcher.Stop(_)),
                5.seconds
              )
              dispatcher.terminate()
            }
          )
          try {
            Await.result(dispatcher.whenTerminated, durationMs.plus(10.seconds))
          } catch {
            case e: Exception =>
              logger.error(
                "Error while waiting for system termination: {}",
                e.getMessage
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

      (1 to params.runs).foreach(runSingleExperiment)
      MDC.put(mdcKey, params.experimentPath)
    }.andThen {
      case scala.util.Success(_) =>
        logger.info("All runs completed successfully.")
      case scala.util.Failure(exception) =>
        logger.error("Error in processing thread: {}", exception.getMessage)
    }
  }

  /** The main entry point of the application.
    *
    * Parses command-line arguments, loads configuration, stores them in the
    * singleton for [[GUIApp]] to consume, then either launches the JavaFX GUI
    * (non-headless) or runs the experiment directly (headless).
    *
    * @param args
    *   Command-line arguments:
    *   `[experimentPath] [--headless=true|false] [--runs=N] [--parent=path]`
    */
  def main(args: Array[String]): Unit = {
    val params = parseCommandLineParams(args)
    commandLineParams = params
    config = loadConfig(params)
    MDC.put(mdcKey, params.experimentPath)

    if (!params.headless) {
      javafx.application.Platform.setImplicitExit(true)
      javafx.application.Application.launch(classOf[GUIApp], args: _*)
    } else {
      implicit val ec: scala.concurrent.ExecutionContext =
        scala.concurrent.ExecutionContext.global
      Await.result(runExperiment(params, config, None), Duration.Inf)
    }
  }
}
// TODO list:
// 1. With a low exploration radius and a low weight per prospect, I would have thought the low areas would be well-explored, but it seems not. I would have thought there would be more low-threshold points when submitting the prospects, so the number of exploiters would be high. Which it may be; that would show up as duplicates, not density. I would have to re-introduce some randomness around the prospect to do that.
// 7. Do the main chart with a colorbar legend with the color determined by the sequence #.
// 10. Change the behavior of the explorer to only explore a maximum number of prospects, rather than having to find the edge.
// 14. JFree seems to take longer, but I think I generate a lot more data now.
// 16. I think there needs to be a listener so that when the window closes, the processing thread is interrupted.
