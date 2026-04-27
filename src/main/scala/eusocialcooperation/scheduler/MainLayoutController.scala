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
//import org.jzy3d.javafx.offscreen.JavaFXOffscreenChartFactory
//import org.jzy3d.plot3d.rendering.canvas.Quality
//import org.jzy3d.chart.AWTChart
//import org.jzy3d.chart.AWTNativeChart
//import com.jogamp.opengl.GLProfile
//import org.jzy3d.chart.factories.NativePainterFactory
//import org.jzy3d.javafx.offscreen.JavaFXOffscreenPainterFactory
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
import eusocialcooperation.scheduler.charter.JFreeCharter
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

class MainLayoutController extends Initializable with LoggingComponent{
    @FXML
    private var jfxPointsVBox: jfxl.VBox = uninitialized
    private lazy val pointsVBox: VBox = VBox(jfxPointsVBox)

    @FXML
    private var jfxPoints2DVBox: jfxl.VBox = uninitialized
    private lazy val points2DVBox: VBox = VBox(jfxPoints2DVBox)

    @FXML
    private var jfxClusterAnalysisVBox: jfxl.VBox = uninitialized
    private lazy val clusterAnalysisVBox: VBox = VBox(jfxClusterAnalysisVBox)

    @FXML
    private var jfxLengthSamplesVBox: jfxl.VBox = uninitialized
    private lazy val lengthSamplesVBox: VBox = VBox(jfxLengthSamplesVBox)

    @FXML var jfxExperimentPathLabel: jfxc.Label = uninitialized
    private lazy val experimentPathLabel: Label = new Label(jfxExperimentPathLabel)

    @FXML
    private var jfxPointsLabel: jfxc.Label = uninitialized
    private lazy val pointsLabel: Label = new Label(jfxPointsLabel)

    @FXML
    private var jfxPoints2DLabel: jfxc.Label = uninitialized
    private lazy val points2DLabel: Label = new Label(jfxPoints2DLabel)

    @FXML
    private var jfxClusterAnalysisLabel: jfxc.Label = uninitialized
    private lazy val clusterAnalysisLabel: Label = new Label(jfxClusterAnalysisLabel)

    @FXML var jfxLengthSamplesLabel: jfxc.Label = uninitialized
    private lazy val lengthSamplesLabel: Label = new Label(jfxLengthSamplesLabel)

    val experimentPathProperty: StringProperty = StringProperty("")

    val pointsChartProperty: ObjectProperty[Option[Chart3D]] = ObjectProperty(None)
    val points2DChartProperty: ObjectProperty[Option[JFreeChart]] = ObjectProperty(None)
    val clusterAnalysisChartProperty: ObjectProperty[Option[JFreeChart]] = ObjectProperty(None) 
    val lengthSamplesChartProperty: ObjectProperty[Option[JFreeChart]] = ObjectProperty(None)

    val charter = new JFreeCharter()
    
    def initialize(location: URL, resources: ju.ResourceBundle): Unit = {
        implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

        experimentPathLabel.text <== Bindings.createStringBinding(() => s"Experiment Path: ${experimentPathProperty()}", experimentPathProperty)

        pointsChartProperty.onChange { (_, _, newValue) =>
            logger.info(s"Points updated: ${newValue.size} points")
            newValue.map(chart => {
                Platform.runLater(() => {
                    val pointsViewer = new Chart3DViewer(chart)
                    pointsViewer.setPrefSize(800, 600)

                    this.pointsVBox.children.clear()
                    this.pointsVBox.children.add(pointsViewer)
                    this.pointsVBox.children.add(pointsLabel)
                })
            })
        }
        points2DChartProperty.onChange { (_, _, newValue) =>
            newValue.map(chart => {
                Platform.runLater(() => {
                    val points2DViewer = new ChartViewer(chart)
                    points2DViewer.setPrefSize(800, 600)

                    this.points2DVBox.children.clear()
                    this.points2DVBox.children.add(points2DViewer)
                    this.points2DVBox.children.add(points2DLabel)
                })
            })
        }
        clusterAnalysisChartProperty.onChange { (_, _, newValue) =>
            newValue.map(chart => {
                Platform.runLater(() => {
                    val clusterChartViewer = new ChartViewer(chart)
                    clusterChartViewer.setPrefSize(800, 600)

                    this.clusterAnalysisVBox.children.clear()
                    this.clusterAnalysisVBox.children.add(clusterChartViewer)
                    this.clusterAnalysisVBox.children.add(clusterAnalysisLabel)
                })
            })
        }
        lengthSamplesChartProperty.onChange { (_, _, newValue) =>
            newValue.map(chart => {
                Platform.runLater(() => {
                    val lengthSamplesChartViewer = new ChartViewer(chart)
                    lengthSamplesChartViewer.setPrefSize(800, 600)
    
                    this.lengthSamplesVBox.children.clear()
                    this.lengthSamplesVBox.children.add(lengthSamplesChartViewer)
                    this.lengthSamplesVBox.children.add(lengthSamplesLabel)
                })
            })
        }
    }
}