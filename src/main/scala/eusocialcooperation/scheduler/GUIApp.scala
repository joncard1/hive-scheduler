package eusocialcooperation.scheduler

import javafx.application.Application
import javafx.stage.{Stage => JStage}
import javafx.fxml.FXMLLoader
import javafx.scene.{layout => jfxl}
import scalafx.scene.Scene
import scalafx.scene.layout.GridPane
import scalafx.stage.Stage

/** JavaFX application class responsible for initialising and displaying the
  * primary stage.
  *
  * This class is launched by [[Demo.main]] via
  * `Application.launch(classOf[GUIApp], ...)` when the application is not
  * running in headless mode. It retrieves the pre-parsed [[Demo.CommandLineParams]]
  * and [[Demo.config]] from the [[Demo]] singleton so that the command-line
  * arguments and configuration are available without re-parsing.
  *
  * `Platform.implicitExit` is set to `true` in [[Demo.main]] before launching,
  * so closing the last window will terminate the JavaFX runtime automatically.
  * The [[stop]] method is called by the JavaFX runtime on shutdown and cancels
  * any in-progress actor system.
  */
class GUIApp extends Application with LoggingComponent {

  /** Initialises the primary stage and starts the experiment processing thread.
    *
    * Retrieves the [[Demo.CommandLineParams]] and configuration from the
    * [[Demo]] singleton, loads the appropriate FXML layout, configures the
    * primary stage, and then calls [[Demo.runExperiment]] to start the Pekko
    * actor system in a background thread.
    *
    * @param primaryStage
    *   The primary stage provided by the JavaFX runtime.
    */
  override def start(primaryStage: JStage): Unit = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global

    val params = Demo.commandLineParams
    implicit val appConfig = Demo.config

    // Initializes the UI. I have a strong preference for FXML files rather than programmatic UI; I wish JavaFX was as good as Adobe Flex was.
    val fxmlUrl = getClass.getResource(Demo.fxmlFileName(params.headless))
    val loader = new FXMLLoader(fxmlUrl)
    loader.load()

    val controller: Option[MainLayoutController] = if (!params.headless) {
      logger.info("UI initialized, starting processing thread.")
      val ctrl = loader.getController[MainLayoutController]()
      ctrl.experimentPathProperty() = params.experimentPath
      Some(ctrl)
    } else {
      None
    }

    val sfxStage = new Stage(primaryStage)
    val root = loader.getRoot[jfxl.GridPane]()
    sfxStage.scene = new Scene(new GridPane(root))
    sfxStage.title = "Eusocial Cooperation Scheduler Demo"
    sfxStage.show()

    // Start the processing thread. Can't be launched in the thread running "start" or it will block the launching of the window.
    Demo.runExperiment(params, appConfig, controller)
  }

  /** Cancels the current experiment actor system if it is still running.
    *
    * Called automatically by the JavaFX runtime when the application shuts
    * down (i.e. when the last window is closed, given that
    * `Platform.implicitExit` is `true`).
    */
  override def stop(): Unit = {
    Demo.cancelCurrentExperiment()
  }
}
