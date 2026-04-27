package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.ActorSystem
import scala.concurrent.duration.DurationInt
import scala.concurrent.Await
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.DurationConverters._
//import org.jzy3d.plot3d.primitives.Scatter
//import org.jzy3d.plot3d.primitives.LineStrip
//import org.jzy3d.plot3d.rendering.canvas.Quality
//import org.jzy3d.chart.factories.AbstractDrawableFactory
//import org.jzy3d.chart.factories.EmulGLChartFactory
//import org.jzy3d.chart.EmulGLSkin
import scala.jdk.CollectionConverters._
import scala.util.Random
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
//import org.jzy3d.maths.Coord3d
import java.io.File
//import org.jzy3d.maths.Coord2d
import java.net.URL
import java.net.URLClassLoader
//import org.jzy3d.plot3d.rendering.legends.AWTLegend
//import org.jzy3d.colors.Color
//import org.jzy3d.plot3d.rendering.legends.colorbars.AWTColorbarLegend
import org.apache.pekko.util.Timeout
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import com.typesafe.config.Config
import org.slf4j.MDC
import scalafx.application.JFXApp3
import scalafx.scene._
import scalafx._
import scalafx.scene.control._
import javafx.fxml.FXMLLoader
import scalafx.scene.Parent
import javafx.{scene => jfxs}
import javafx.scene.{layout => jfxl}
import org.apache.pekko.Main
import scalafx.scene.layout.GridPane
import scala.concurrent.Future
import eusocialcooperation.scheduler.charter.JFreeCharter
import org.jfree.chart3d.`export`.ExportUtils
import org.jfree.chart.ChartUtils
import scalafx.application.Platform

object Demo extends JFXApp3 {

    val durationConfigKey = "duration"
    val mdcKey = "experiment"

    override def start(): Unit = {
        implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

        val experimentPath = this.parameters.unnamed.headOption match {
            // TODO: This is to enable the use of the code lens. It really should be provided either on the command line or as an environment variable.
            //case None => throw new IllegalArgumentException("Experiment path must be provided as the first argument.")
            case None => "testconf/"
            case Some(path) if path.isEmpty() => throw new IllegalArgumentException("Experiment path must be non-empty.")
            case Some(path) if !path.endsWith("/") => path + "/"
            case Some(path) => path
        }
        val headless = this.parameters.named.get("headless").exists(_.toBoolean)

        MDC.put(mdcKey, experimentPath) // This will be used in the logback configuration to determine where to write logs for this experiment.
        given mdc: Map[String, String] = MDC.getCopyOfContextMap().asScala.toMap
        val logger = LoggerFactory.getLogger(s"${this.getClass.getPackage.getName}.Demo")

        val configFile = new File(s"${experimentPath}$experimentConfigPath")
        val folderUrl: URL = configFile.toURI.toURL
        val currentLoader = Thread.currentThread().getContextClassLoader
        val arr: Array[URL] = Array(folderUrl)
        val configLoader = new URLClassLoader(arr, currentLoader)
        
        given config: Config = ConfigFactory.load(configLoader, experimentConfigurationFileName).getConfig(this.getClass().getPackage().getName())

        val durationMs = {
            config.getDuration(durationConfigKey) match {
                case ms if ms.toMillis > 0 => ms.toScala
                case ms => throw new IllegalArgumentException(s"${durationConfigKey} must be positive, but got $ms")
            }
        }
        //logger.debug("Starting demo with duration: {}", durationMs)

        val fxmlUrl = this.getClass().getResource("/main-layout.fxml")
        val loader = new FXMLLoader(fxmlUrl)
        loader.load()
        val controller = loader.getController[MainLayoutController]()
        controller.experimentPathProperty() = experimentPath
        stage = new JFXApp3.PrimaryStage {
            if (!headless) {
                val root = loader.getRoot[jfxl.GridPane]()
                scene = new Scene(new GridPane(root))
                title = "Eusocial Cooperation Scheduler Demo"
            }
        }

        // Start the processing thread. Can't be launched in the thread running "start" or it will block the launching of the window.
        Future {
            MDC.setContextMap(mdc.asJava)
            logger.trace("Starting processing thread.")
            val points = AtomicReference(Set.empty[DataPoint[Sample]])
            val prospects = AtomicReference(Set.empty[DataPoint[Point]])

            logger.trace("Creating dispatcher actor system.")
            
            val dispatcher = try {
                ActorSystem(Dispatcher(points, prospects), "DispatcherSystem")
            } catch {
                case e: Exception => 
                    logger.error("Error while creating dispatcher actor system: {}", e.getMessage)
                    throw e
            }
            logger.trace("Got dispatcher reference")

            val queueLengths = AtomicReference(List[(Long, Int)]())
            val startTime = System.currentTimeMillis()
            val queueSampler = dispatcher.scheduler.scheduleAtFixedRate(100.milliseconds, 100.milliseconds) { () =>
                given Timeout = 1.second
                given Scheduler = dispatcher.scheduler
                dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)).map { points => 
                    queueLengths.updateAndGet(x => (System.currentTimeMillis() - startTime, points.points.size) :: x) 
                }
            }
            logger.trace("Starting queue sampler")
            val scheduledTask = dispatcher.scheduler.scheduleOnce(durationMs, () => {
                // Send a stop message so it can stop things better.
                given Timeout = 5.seconds
                given Scheduler = dispatcher.scheduler
                Await.result(dispatcher.ask(Dispatcher.Stop(_)).map(_ => logger.debug("Dispatcher stopped successfully.")), 5.seconds)
                queueSampler.cancel()
                dispatcher.terminate()
            })
            try {
                Await.result(dispatcher.whenTerminated, durationMs.plus(10.seconds))
            } catch {
                case e: Exception => logger.error("Error while waiting for system termination: {}", e.getMessage)
            }
            val charter = new JFreeCharter()
            val pointsChart = charter.getMainChart(points.get(), prospects.get())
            val points2DChart = charter.getPoints2DChart(points.get(), prospects.get())
            val clusterAnalysisChart = charter.getClusterChart(points.get())
            val lengthSamplesChart = charter.getLengthSamplesChart(queueLengths.get().reverse)
            logger.info("Creating charts")
            ExportUtils.writeAsPNG(pointsChart, 800, 600, new java.io.File(s"${experimentPath}main-chart.png"))
            ChartUtils.saveChartAsPNG(new java.io.File(s"${experimentPath}points2D.png"), points2DChart, 800, 600)
            ChartUtils.saveChartAsPNG(new java.io.File(s"${experimentPath}cluster_chart.png"), clusterAnalysisChart, 800, 600)
            ChartUtils.saveChartAsPNG(new java.io.File(s"${experimentPath}length_samples_chart.png"), lengthSamplesChart, 800, 600)
            logger.info("Charts created and saved to disk.")

            logger.trace("Processing thread finished.")
            if (!headless) {
                controller.pointsChartProperty() = Option(pointsChart)
                controller.points2DChartProperty() = Option(points2DChart)
                controller.clusterAnalysisChartProperty() = Option(clusterAnalysisChart)
                controller.lengthSamplesChartProperty() = Option(lengthSamplesChart)
            } else {
                Platform.runLater {
                    this.stopApp()
                }
            }
        }
    }
}
// TODO list:
// 1. With a low exploration radius and a low weight per prospect, I would have thought the low areas would be well-explored, but it seems not. I would have thought there would be more low-threshold points when submitting the prospects, so the number of exploiters would be high. Which it may be; that would show up as duplicates, not density. I would have to re-introduce some randomness around the prospect to do that.
// 7. Do the main chart with a colorbar legend with the color determined by the sequence #.
// 10. Change the behavior of the explorer to only explore a maximum number of prospects, rather than having to find the edge.
// 14. JFree seems to take longer, but I think I generate a lot more data now.
// 15. Might be nice to say "run this whole family of experiments" and it loads a main configuration file and then, for each folder, runs the experiment inside, merging the configuration file. Or just "run this experiment, and get the parent configuration from above it"
// 9. Make the kernel function configurable?