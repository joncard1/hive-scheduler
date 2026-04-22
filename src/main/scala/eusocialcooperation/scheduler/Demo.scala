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

// The factoring of this is bad.
@main def Demo(): Unit = {
    // TODO: Move this to an environment variable or a command-line argument, and make it more flexible (e.g. allow specifying the kernel function, the exploration radius, the worker preference, etc.) so that the application.conf and the output path are tied together somehow to keep experiment output and the experiment parameters together.
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

    val experimentPath: String = "experiment/"
    // TODO: Want to load "experiment.conf" from "${experimentPath}experiment.conf"
    implicit val config = ConfigFactory.load().getConfig("eusocialcooperation.scheduler")

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
        system.terminate()
    })
    try {
        Await.result(system.whenTerminated, durationMs.plus(5.seconds))
    } catch {
        case e: Exception => logger.error("Error while waiting for system termination: {}", e.getMessage)
    }
    val pointsData = points.get()
    //println("Final points: " + pointsData.map(p => s"(${p.value._1}, ${p.value._2}, ${p.value._3}), ${p.actorName}, ${p.sequenceNumber}, ${p.phase}").mkString(", "))

    val exploiterPoints = pointsData.filter(_.phase == DataPoint.Phase.Exploiter)
    logger.info("Exploiter points count: {}", exploiterPoints.size)
    val scatter = new Scatter(exploiterPoints.map(p => new org.jzy3d.maths.Coord3d(p.value._1.toDouble, p.value._2.toDouble, p.value._3.toDouble)).toArray)
    scatter.setColor(new org.jzy3d.colors.Color(0, 0, 0))
    scatter.setWidth(5.0f)

    val prospectsData = prospects.get().map(p => new Coord3d(p.value._1.toDouble, p.value._2.toDouble, 0)).toArray
    logger.info("Prospects count: {}", prospectsData.length)
    val prospectsScatter = new Scatter(prospectsData)
    prospectsScatter.setColor(new org.jzy3d.colors.Color(0, 255, 0))
    prospectsScatter.setWidth(5.0f)

    val explorerPoints = pointsData.filter(_.phase == DataPoint.Phase.Explorer).groupBy(_.actorName)
    
    //val quality = Quality.Advanced

    val factory = new EmulGLChartFactory()
    val mainChart = factory.newChart()
    mainChart.add(scatter)
    mainChart.add(prospectsScatter)
    /*
    for ((actorName, pts) <- explorerPoints) {
        //println(s"Actor $actorName has ${pts.size} explorer points.")
        new LineStrip()
        val lineStrip = new LineStrip(pts.toSeq.sortBy(_.sequenceNumber).map(p => new org.jzy3d.maths.Coord3d(p.value._1.toDouble, p.value._2.toDouble, p.value._3.toDouble)).toSeq.asJava)
        lineStrip.setShowSymbols(true)
        //lineStrip.setWidth(5.0f)
        lineStrip.setColor(new org.jzy3d.colors.Color((Random.nextFloat() * 0.5f) + 0.5f, (Random.nextFloat() * 0.5f) + 0.5f, (Random.nextFloat() * 0.5f) + 0.5f))
        chart.add(lineStrip)
    }
    */

    val clusterThingMap = pointsData
        .filter(p => p.parent.isDefined && (p.phase == DataPoint.Phase.Exploiter))
        .groupBy(_.parent.get)
        .map((t) => {
            val (p, pts) = t
            val average = pts.foldLeft(BigDecimal(0.0))((acc, p) => acc + p.value._3) / pts.size
            (p -> (pts, average))
        })
        .map((t) => {
            val (p, (pts, average)) = t
            (p -> (pts.size, (pts.foldLeft(BigDecimal(0.0))((acc, p) => {
                acc + (p.value._3 - average).pow(2)
            }) / pts.size)))
        })
        .map((t) => {
            val (p, (count, variance)) = t
            (p -> new Coord3d(count.toDouble, variance.toDouble, 0))
        })
    
    clusterThingMap.foreach((t) => {
        val (p, coord) = t
        logger.info(s"Parent point (${p.asInstanceOf[DataPoint[Point]].value._1}, ${p.asInstanceOf[DataPoint[Point]].value._2}) has ${coord.x} exploiter children and variance ${coord.y}.")
    })
    val clusterThingData = clusterThingMap.values.toArray
    val clusterThingScatter = new Scatter(clusterThingData)
    clusterThingScatter.setWidth(5.0f)
    clusterThingScatter.setColor(new org.jzy3d.colors.Color(0, 0, 0))
    val secondFactory = new EmulGLChartFactory()
    val clusterChart = secondFactory.newChart()
    clusterChart.add(clusterThingScatter)

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
    skin.getCanvas().setProfileDisplayMethod(true);

    // Should create a images where the color is over a continuum by the timestamp, and another where the points are color-coded by the worker.
}

// TODO list:
// 1. With a low exploration radius and a low weight per prospect, I would have thought the low areas would be well-explored, but it seems not. I would have thought there would be more low-threshold points when submitting the prospects, so the number of exploiters would be high. Which it may be; that would show up as duplicates, not density. I would have to re-introduce some randomness around the prospect to do that.
// 2. I should have a maximum number of points to for the explorer to check. It's introducing a bias for points near the boundary of the threshold.