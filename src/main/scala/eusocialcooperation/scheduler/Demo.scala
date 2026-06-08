package eusocialcooperation.scheduler

import com.typesafe.config.ConfigFactory
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import com.typesafe.config.Config
import org.slf4j.MDC
import scala.compiletime.uninitialized
import eusocialcooperation.scheduler.processor.DefaultProcessor
import eusocialcooperation.scheduler.processor.ClusterProcessor
import eusocialcooperation.scheduler.processor.Processor

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
    * @param experimentsPath
    *   The path to a directory that contains multiple experiments, one sub-directory for each experiment.
    * @param runs
    *   The number of runs to execute for each experiment.
    * @param headless
    *   Whether to run in headless mode (no GUI).
    * @param parentPath
    *   The optional parent experiment path for configuration fallback.
    * @param specifiedOutputPath
    *   A directory to contain the data created during the experiment that will mirror the structure of the configuration data.
    */
  case class CommandLineParams(
      experimentPath: Option[String],
      experimentsPath: Option[String],
      runs: Int,
      headless: Boolean,
      parentPath: Option[String],
      specifiedOutputPath: Option[String]
  )

  private[scheduler] val durationConfigKey = "duration"
  private[scheduler] val mdcKey = "experiment"
  private[scheduler] val headlessModeFxmlFileName = "/headless-mode.fxml"
  private[scheduler] val mainLayoutFxmlFileName = "/main-layout.fxml"

  /** Shared state read by [[GUIApp]] after [[main]] has populated it. */
  @volatile private[scheduler] var commandLineParams: CommandLineParams = uninitialized
  @volatile private[scheduler] var config: Config = uninitialized

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

  def runOutputPath(params: CommandLineParams, runNumber: Int): String = {
    require(params.experimentPath.isDefined, "The method runOutputPath requires an experimentPath be set. If one was not provided by the command-line, a copy of CommandLineParams with the path set should have been provided by the caller.")
    val experimentPath = params.experimentPath.get
    val runs = params.runs
    val specifiedOutputPath = params.specifiedOutputPath
    val pathPrefix = specifiedOutputPath.fold(experimentPath)(sp => {
      params.experimentsPath.fold(s"${sp}")(ep => s"${sp}${experimentPath.replaceAllLiterally(ep, "")}")
    })
    if (runs > 1) {
      s"${pathPrefix}run_${runNumber.formatted("%03d")}/"
    } else {
      pathPrefix
    }
  }

  def effectiveHeadless(requestedHeadless: Boolean, runs: Int, experimentPath: Option[String]): Boolean =
    requestedHeadless || runs > 1 || experimentPath.isEmpty

  def fxmlFileName(headless: Boolean): String =
    if (headless) headlessModeFxmlFileName else mainLayoutFxmlFileName

  /** Parses raw command-line arguments into a [[CommandLineParams]] instance.
    *
    * Named arguments use the format `--key=value`; a bare flag `--key` is
    * treated as `--key=true`. The first positional argument is the experiment
    * path.
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
      case None => Option.empty[String]
      case Some(path) if path.isEmpty() =>
        throw new IllegalArgumentException("Experiment path must be non-empty.")
      case Some(path) if !path.endsWith("/") => Some(path + "/")
      case Some(path)                        => Some(path)
    } match {
      case None => None
      case Some(path) if !File(path).exists() =>
        throw new IllegalArgumentException(
          s"Experiment path '$path' does not exist."
        )
      case path => path
    }

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

    val experimentsPath = namedParameters.get("experimentsPath") match {
      case None => None
      case Some(path) if path.isEmpty() =>
        throw new IllegalArgumentException("Experiments path must be non-empty.")
      case Some(path) if !path.endsWith("/") => Some(path + "/")
      case x @ Some(path)                        => x
    } match {
      case None => None
      case x @ Some(path) =>
         val experimentsDir = File(path)
         if !experimentsDir.exists() then
           throw new IllegalArgumentException(
             s"Experiments path '$path' does not exist."
           )
         if !experimentsDir.isDirectory() then
           throw new IllegalArgumentException(
             s"Experiments path '$path' must be a directory."
           )
         val subdirs =
           Option(experimentsDir.listFiles())
             .getOrElse(Array.empty[File])
             .filter(_.isDirectory)
             .filter(f => (f.getName != "config") && (f.getName != "logs") && (parentPath.fold(true)(pp => f.getPath != pp.stripSuffix("/"))))
             .map(_.getName)
         if subdirs.isEmpty then
           throw new IllegalArgumentException(
             s"Experiments path '$path' must contain at least one subdirectory representing an experiment."
           )
        x
    }

    if experimentPath.isEmpty && experimentsPath.isEmpty then
      throw new IllegalArgumentException(
        "Experiment path is required if --experimentsPath is not set."
      )

    if experimentPath.isDefined && experimentsPath.isDefined then
      throw new IllegalArgumentException(
        "Cannot set both experimentPath and experimentsPath; only one may be set."
      )

    val runs = parseRunsParam(namedParameters)
    val requestedHeadless = namedParameters.get("headless").exists(_.toBoolean)
    val headless = effectiveHeadless(requestedHeadless, runs, experimentPath)

    val outputPath = namedParameters.get("outputPath").map { path =>
      if path.nonEmpty && !path.endsWith("/") then s"$path/" else path
    }
    CommandLineParams(experimentPath, experimentsPath, runs, headless, parentPath, outputPath)
  }

  private[scheduler] def getConfigFromPath(path: String) = {
    val currentLoader = Thread.currentThread().getContextClassLoader

    def getConfigLoader(path: String): URLClassLoader = {
      val configFile = new File(s"${path}$experimentConfigPath")
      val folderUrl: URL = configFile.toURI.toURL
      new URLClassLoader(Array(folderUrl), currentLoader)
    }
    ConfigFactory.load(getConfigLoader(path), experimentConfigurationFileName)
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
  def loadConfig(params: CommandLineParams, defaultApplicationConfig: Config = ConfigFactory.defaultApplication()
): Config = {
    val parentConfig = params.parentPath.fold(defaultApplicationConfig) { parent =>
      getConfigFromPath(parent).withFallback(defaultApplicationConfig)
    }
    params.experimentPath.fold(parentConfig) { experimentPath =>
      getConfigFromPath(experimentPath).withFallback(parentConfig)
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
  def runGuiMode(args: Array[String], params: CommandLineParams): Unit =
    MDC.put(mdcKey, params.experimentPath.get)

    javafx.application.Platform.setImplicitExit(true)
    javafx.application.Application.launch(classOf[GUIApp], args*)

  def main(args: Array[String]): Unit = {
    val params = parseCommandLineParams(args)
    commandLineParams = params
    config = loadConfig(params)

    if (!params.headless) {
      // TODO: I don't like how the global Demo.config is shared with the GUI, but it seems to be hard to get it in there. I think I'd have to create a controller factory? I vaguely remember that being a thing.
      runGuiMode(args, params)
    } else if (config.hasPath("pekko.actor.provider") && config.getString("pekko.actor.provider").equals("cluster")) {
      runClusterMode(params, config)
    } else if (params.experimentsPath.isDefined) {
      runMultipleExperimentsMode(params, config)
    } else if (params.experimentPath.isDefined) {  
      runSingleExperimentMode(params, config)    
    } else {
      throw new IllegalArgumentException(
        "Either experimentPath or experimentsPath must be provided. This should have been enforced by this point; check the command-line arguments parsing logic."
      )
    }
  }

  def runClusterMode(params: CommandLineParams, config: Config) = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global

    val processor = new ClusterProcessor(config)

    runMultipleExperimentsMode(params, config, processor)
  }

  def runSingleExperimentMode(params: CommandLineParams, config: Config) = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global
    val processor = new DefaultProcessor(mdcKey, None)

    processor.runExperiment(params, config)
  }

  def runMultipleExperimentsMode(params: CommandLineParams, config: Config, processor: Processor = new DefaultProcessor(mdcKey, None)) = {
    implicit val ec: scala.concurrent.ExecutionContext =
        scala.concurrent.ExecutionContext.global

      // This effectively makes --experimentsPath greater precedent than experimentPath, but prohibiting setting both should have been enforced by this point.
      // TODO: Pass something a factory for the correct processor so this can be mocked.
      // TODO: Actually, I should/could include a closer here so that the processor can be shut down, now that the cluster processor keeps the cluster up the whole time instead of shutting down each iteration.

      // I think this is the situation where the cluster processor should be created.
      val experimentsFolder = new File(params.experimentsPath.get)
      experimentsFolder.listFiles().filter(_.isDirectory).filter(f => (f.getName != "config") && (f.getName != "logs") && (params.parentPath.fold(true)(pp => f.getPath != pp.stripSuffix("/")))).sortBy(_.getName).foreach { experimentDir =>
        val experimentParams = params.copy(experimentPath = Some(experimentDir.getPath + "/"))
        val experimentConfig = getConfigFromPath(experimentParams.experimentPath.get).withFallback(config)
        processor.runExperiment(experimentParams, experimentConfig)
      }
  }
}
// TODO list:
// 7. Do the main chart with a colorbar legend with the color determined by the sequence #.
// 10. Change the behavior of the explorer to only explore a maximum number of prospects, rather than having to find the edge.
// 14. JFree seems to take longer, but I think I generate a lot more data now.
// 16. I think there needs to be a listener so that when the window closes, the processing thread is interrupted.
// 17. I think the GUI version of the app works ok, but the way I use the headless mode is that I want to supply a folder full of experiments, and each of those should be run multiple times; not running a single folder multiple times and then running the application again for the next folder. This implies:
//     a. The experiments themselves should be added as part of the Kubernetes configuration. I'm not sure how to do that.
//     b. The number of pods in the Kubernetes cluster should make it into the pekko configuration, because I want the all to start when all of the pods have joined.
//     c. The iteration of the folders should happen in Demo, not in a bash script. Which means figuring out how the command-line arguments should work differently.
// 18. Make experimentPath as optional. If it is not headless, then it should be required. Otherwise, --parent is required. If not supplied, the run experiment on every directory in parent except "config".