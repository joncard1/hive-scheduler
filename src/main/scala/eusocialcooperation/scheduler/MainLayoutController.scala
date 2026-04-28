package eusocialcooperation.scheduler
import javafx.fxml.FXML
import scalafx.scene.layout.VBox
import javafx.scene.{layout => jfxl}
import javafx.scene.{control => jfxc}
import scala.compiletime.uninitialized
import javafx.fxml.Initializable
import java.net.URL
import java.{util => ju}
import scalafx.beans.property.ObjectProperty
import ju.concurrent.atomic.AtomicReference
import org.apache.pekko.actor.typed.ActorSystem
import scala.concurrent.Await
import scalafx.beans.property.LongProperty
import scala.concurrent.duration.FiniteDuration
import com.typesafe.config.Config
import scala.concurrent.duration.DurationInt
import org.apache.pekko.util.Timeout
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import scalafx.scene.control.Label
import scalafx.application.Platform
import org.jfree.chart.ChartUtils
import org.jfree.chart3d.fx.Chart3DViewer
import org.jfree.chart.fx.ChartViewer
import scalafx.beans.property.StringProperty
import org.jfree.chart3d.`export`.ExportUtils
import scalafx.scene.input.KeyCode.J
import scalafx.beans.binding.Bindings
import org.jfree.chart3d.Chart3D
import org.jfree.chart.JFreeChart

/** The principle controller for the application. The application shows several
  * tabs, each containing a chart. Mostly, the window shows nothing until the
  * chart is generated and passed in through various properties from the
  * computation thread.
  */
class MainLayoutController extends Initializable with LoggingComponent {

  /** The VBox containing the main chart on the first tab.
    */
  @FXML
  private var jfxPointsVBox: jfxl.VBox = uninitialized

  /** The ScalaFX VBox containing the main chart on the first tab.
    */
  private lazy val pointsVBox: VBox = VBox(jfxPointsVBox)

  /** The VBox containing the 2D points chart on the second tab. */
  @FXML
  private var jfxPoints2DVBox: jfxl.VBox = uninitialized

  /** The ScalaFX VBox containing the 2D points chart on the second tab.
    */
  private lazy val points2DVBox: VBox = VBox(jfxPoints2DVBox)

  /** The VBox containing the cluster analysis chart on the third tab.
    */
  @FXML
  private var jfxClusterAnalysisVBox: jfxl.VBox = uninitialized

  /** The ScalaFX VBox containing the cluster analysis chart on the third tab.
    */
  private lazy val clusterAnalysisVBox: VBox = VBox(jfxClusterAnalysisVBox)

  /** The VBox containing the length samples chart on the fourth tab.
    */
  @FXML
  private var jfxLengthSamplesVBox: jfxl.VBox = uninitialized

  /** The ScalaFX VBox containing the length samples chart on the fourth tab.
    */
  private lazy val lengthSamplesVBox: VBox = VBox(jfxLengthSamplesVBox)

  /** The Label containing the experiment path.
    */
  @FXML var jfxExperimentPathLabel: jfxc.Label = uninitialized

  /** The ScalaFX label showing the path to the experiment.
    */
  private lazy val experimentPathLabel: Label = new Label(
    jfxExperimentPathLabel
  )

  /** The Label labelling the points chart on the first tab.
    */
  @FXML
  private var jfxPointsLabel: jfxc.Label = uninitialized

  /** The ScalaFX label labelling the points chart on the first tab.
    */
  private lazy val pointsLabel: Label = new Label(jfxPointsLabel)

  /** The Label labelling the 2D points chart on the second tab.
    */
  @FXML
  private var jfxPoints2DLabel: jfxc.Label = uninitialized

  /** The ScalaFX label labelling the 2D points chart on the second tab.
    */
  private lazy val points2DLabel: Label = new Label(jfxPoints2DLabel)

  /** The Label labelling the cluster analysis chart on the third tab.
    */
  @FXML
  private var jfxClusterAnalysisLabel: jfxc.Label = uninitialized

  /** The ScalaFX label labelling the cluster analysis chart on the third tab.
    */
  private lazy val clusterAnalysisLabel: Label = new Label(
    jfxClusterAnalysisLabel
  )

  /** The Label labelling the length samples chart on the fourth tab.
    */
  @FXML var jfxLengthSamplesLabel: jfxc.Label = uninitialized

  /** The ScalaFX label labelling the length samples chart on the fourth tab.
    */
  private lazy val lengthSamplesLabel: Label = new Label(jfxLengthSamplesLabel)

  /** A property allowing other elements of the system to set the path to the
    * experiment.
    */
  val experimentPathProperty: StringProperty = StringProperty("")

  /** A propperty allowing other elements of the system to set the main points
    * chart.
    */
  val pointsChartProperty: ObjectProperty[Option[Chart3D]] = ObjectProperty(
    None
  )

  /** A property allowing other elements of the system to set the 2D points
    * chart.
    */
  val points2DChartProperty: ObjectProperty[Option[JFreeChart]] =
    ObjectProperty(None)

  /** A property allowing other elements of the system to set the cluster
    * analysis chart.
    */
  val clusterAnalysisChartProperty: ObjectProperty[Option[JFreeChart]] =
    ObjectProperty(None)

    /** A property allowing other elements of the system to set the length
      * samples chart.
      */
  val lengthSamplesChartProperty: ObjectProperty[Option[JFreeChart]] =
    ObjectProperty(None)

  /**
    * Initializes the controller. This method is called by the JavaFX framework when the FXML file is loaded.
    *
    * @param location
    * @param resources
    */
  def initialize(location: URL, resources: ju.ResourceBundle): Unit = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global

    experimentPathLabel.text <== Bindings.createStringBinding(
      () => s"Experiment Path: ${experimentPathProperty()}",
      experimentPathProperty
    )

    pointsChartProperty.onChange { (_, _, newValue) =>
      newValue.map(chart => {
        val pointsViewer = new Chart3DViewer(chart)
        pointsViewer.setPrefSize(800, 600)

        this.pointsVBox.children.clear()
        this.pointsVBox.children.add(pointsViewer)
        this.pointsVBox.children.add(pointsLabel)
      })
    }
    points2DChartProperty.onChange { (_, _, newValue) =>
      newValue.map(chart => {
        val points2DViewer = new ChartViewer(chart)
        points2DViewer.setPrefSize(800, 600)

        this.points2DVBox.children.clear()
        this.points2DVBox.children.add(points2DViewer)
        this.points2DVBox.children.add(points2DLabel)
      })
    }
    clusterAnalysisChartProperty.onChange { (_, _, newValue) =>
      newValue.map(chart => {
        val clusterChartViewer = new ChartViewer(chart)
        clusterChartViewer.setPrefSize(800, 600)

        this.clusterAnalysisVBox.children.clear()
        this.clusterAnalysisVBox.children.add(clusterChartViewer)
        this.clusterAnalysisVBox.children.add(clusterAnalysisLabel)
      })
    }
    lengthSamplesChartProperty.onChange { (_, _, newValue) =>
      newValue.map(chart => {
        val lengthSamplesChartViewer = new ChartViewer(chart)
        lengthSamplesChartViewer.setPrefSize(800, 600)

        this.lengthSamplesVBox.children.clear()
        this.lengthSamplesVBox.children.add(lengthSamplesChartViewer)
        this.lengthSamplesVBox.children.add(lengthSamplesLabel)
      })
    }
  }
}
