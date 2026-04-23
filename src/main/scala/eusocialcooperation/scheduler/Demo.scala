package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.ActorSystem
import scala.concurrent.duration.DurationInt
import scala.concurrent.Await
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicReference
import org.jzy3d.plot3d.primitives.Scatter
import org.jzy3d.plot3d.primitives.LineStrip
import org.jzy3d.plot3d.rendering.canvas.Quality
import org.jzy3d.chart.factories.AbstractDrawableFactory
import org.jzy3d.chart.factories.EmulGLChartFactory
import org.jzy3d.chart.EmulGLSkin
import scala.jdk.CollectionConverters._
import scala.util.Random
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import org.jzy3d.maths.Coord3d
import java.io.File
import org.jzy3d.maths.Coord2d
import java.net.URL
import java.net.URLClassLoader
import org.jzy3d.plot3d.rendering.legends.AWTLegend
import org.jzy3d.colors.Color
import org.jzy3d.plot3d.rendering.legends.colorbars.AWTColorbarLegend
import org.apache.pekko.util.Timeout
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable

// The factoring of this is bad.
@main def Demo(): Unit = {
    val defaultExperimentPath = "experiment/"

    // TODO: Move this to an environment variable or a command-line argument, and make it more flexible (e.g. allow specifying the kernel function, the exploration radius, the worker preference, etc.) so that the application.conf and the output path are tied together somehow to keep experiment output and the experiment parameters together.
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

    val experimentPath = System.getProperties().asScala.getOrElse("experimentPath", defaultExperimentPath)
    if (!(experimentPath.endsWith("/"))) then {
        throw new IllegalArgumentException(s"experimentPath must end with '/', but got '$experimentPath'")
    }

    // TODO: Want to load "experiment.conf" from "${experimentPath}experiment.conf"
    val configFile = new File(s"${experimentPath}config/")
    val folderUrl: URL = configFile.toURI.toURL
    val currentLoader = Thread.currentThread().getContextClassLoader
    val arr: Array[URL] = Array(folderUrl)
    val configLoader = new URLClassLoader(arr, currentLoader)

    implicit val config = ConfigFactory.load(configLoader, "experiment.conf").getConfig("eusocialcooperation.scheduler")

    val logger = LoggerFactory.getLogger("eusocialcooperation.scheduler.Demo")

    val durationMs = {
        config.getInt("durationMs") match {
            case ms if ms > 0 => ms.milliseconds
            case ms => throw new IllegalArgumentException(s"durationMs must be positive, but got $ms")
        }
    }
    //logger.info("Starting demo with duration: {}", durationMs)

    val points = AtomicReference(Set.empty[DataPoint[Sample]])
    val prospects = AtomicReference(Set.empty[DataPoint[Point]])
    val system = ActorSystem(Dispatcher(points, prospects), "DispatcherSystem")
    val dispatcher = system
    val future = system.scheduler.scheduleOnce(durationMs, () => {
        // Send a stop message so it can stop things better.
        given Timeout = 5.seconds
        given Scheduler = system.scheduler
        Await.result(dispatcher.ask(Dispatcher.Stop(_)).map(_ => logger.info("Dispatcher stopped successfully.")), 5.seconds)
        system.terminate()
    })
    try {
        Await.result(system.whenTerminated, durationMs.plus(10.seconds))
    } catch {
        case e: Exception => logger.error("Error while waiting for system termination: {}", e.getMessage)
    }
    val pointsData = points.get()
    //println("Final points: " + pointsData.map(p => s"(${p.value._1}, ${p.value._2}, ${p.value._3}), ${p.actorName}, ${p.sequenceNumber}, ${p.phase}").mkString(", "))

    val mainFactory = new EmulGLChartFactory()
    val clusterFactory = new EmulGLChartFactory()
    val charter = new Charter()
    val mainChart = charter.getMainChart(pointsData, prospects.get(), mainFactory)
    val clusterChart = charter.getClusterChart(pointsData, clusterFactory)

    mainChart.open("Data Points", 800, 600)
    clusterChart.open("Cluster Data", 800, 600)
    // TODO: I don't know what this is.
    //val skin = EmulGLSkin.on(mainChart)
    val skin = EmulGLSkin.on(clusterChart)
    mainChart.screenshot(new File(experimentPath + "main_chart.png"))
    clusterChart.screenshot(new File(experimentPath + "cluster_chart.png"))
    mainChart.addMouseCameraController()
    clusterChart.addMouseCameraController()

    // TODO: I don't know what this is.

    //skin.getCanvas().setProfileDisplayMethod(true);

    // Should create a images where the color is over a continuum by the timestamp, and another where the points are color-coded by the worker.
}

// TODO list:
// 1. With a low exploration radius and a low weight per prospect, I would have thought the low areas would be well-explored, but it seems not. I would have thought there would be more low-threshold points when submitting the prospects, so the number of exploiters would be high. Which it may be; that would show up as duplicates, not density. I would have to re-introduce some randomness around the prospect to do that.
// 2. I should have a maximum number of points to for the explorer to check. It's introducing a bias for points near the boundary of the threshold.