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

@main def Demo(): Unit = {
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

    val points = AtomicReference(Set.empty[DataPoint[Sample]])
    val system = ActorSystem(Dispatcher(points), "DispatcherSystem")
    val dispatcher = system
    val future = system.scheduler.scheduleOnce(10.second, () => {
        system.terminate()
    })
    try {
        Await.result(system.whenTerminated, 45.seconds)
    } catch {
        case e: Exception => println("Error while waiting for system termination: " + e.getMessage)
    }
    println("Final points: " + points.get().size)

    val scatter = new Scatter(points.get().map(p => new org.jzy3d.maths.Coord3d(p.value._1.toDouble, p.value._2.toDouble, p.value._3.toDouble)).toArray)
    val explorerPoints = points.get().filter(_.phase == DataPoint.Phase.Explorer).groupBy(_.actorName)
    
    //val quality = Quality.Advanced

    val factory = new EmulGLChartFactory()
    val chart = factory.newChart()
    //chart.add(scatter)
    for ((actorName, pts) <- explorerPoints) {
        println(s"Actor $actorName has ${pts.size} explorer points.")
        new LineStrip()
        val lineStrip = new LineStrip(pts.toSeq.sortBy(_.sequenceNumber).map(p => new org.jzy3d.maths.Coord3d(p.value._1.toDouble, p.value._2.toDouble, p.value._3.toDouble)).toSeq.asJava)
        lineStrip.setShowSymbols(true)
        lineStrip.setWidth(5.0f)
        lineStrip.setColor(new org.jzy3d.colors.Color((Random.nextFloat() * 0.5f) + 0.5f, (Random.nextFloat() * 0.5f) + 0.5f, (Random.nextFloat() * 0.5f) + 0.5f))
        chart.add(lineStrip)
    }
    chart.open("Data Points", 800, 600)
    val skin = EmulGLSkin.on(chart);
    chart.addMouseCameraController();

    skin.getCanvas().setProfileDisplayMethod(true);

    // Creating the dispatcher should create some workers.
    // Possibly set a schedule to generate a series of images of the data points?
    // Should create a images where the color is over a continuum by the timestamp, and another where the points are color-coded by the worker.
    // Possibly have an end-state where so-many points have been found, or possibly on a time limit.
}

// TODO list:
// 1. The toroidal navigation strategy is broken. I think the issue is that when it goes off one edge, it doesn't properly wrap around to the other side. I need to make sure that when a worker calculates a new point to explore, it wraps around the edges of the search space correctly.