package eusocialcooperation.scheduler

import org.jzy3d.chart.Chart
import org.jzy3d.chart.factories.IChartFactory
import org.jzy3d.plot3d.primitives.Scatter
import org.jzy3d.maths.Coord3d
import org.jzy3d.maths.Coord2d
import org.jzy3d.plot2d.primitives.ScatterSerie2d
import org.jzy3d.maths.Coord2d
import org.jzy3d.plot2d.primitives.ScatterPointSerie2d
import scala.collection.JavaConverters._
import org.jzy3d.colors.colormaps.ColorMapGrayscale
import org.jzy3d.colors.Color

class Charter extends LoggingComponent {
  def getMainChart(pointsData: Set[DataPoint[Sample]], prospects: Set[DataPoint[Point]], factory: IChartFactory): Chart = {
    val exploiterPoints = pointsData.filter(_.phase == DataPoint.Phase.Exploiter)
    logger.info("Exploiter points count: {}", exploiterPoints.size)
    val scatter = new Scatter(exploiterPoints.map(p => new org.jzy3d.maths.Coord3d(p.value._1.toDouble, p.value._2.toDouble, p.value._3.toDouble)).toArray)
    scatter.setColor(new org.jzy3d.colors.Color(0, 0, 0))
    scatter.setWidth(5.0f)

    val prospectsData = prospects.map(p => new Coord3d(p.value._1.toDouble, p.value._2.toDouble, 0)).toArray
    logger.info("Prospects count: {}", prospectsData.length)
    val prospectsScatter = new Scatter(prospectsData)
    prospectsScatter.setColor(new org.jzy3d.colors.Color(0, 255, 0))
    prospectsScatter.setWidth(5.0f)
    //val quality = Quality.Advanced

    /*
    val explorerPoints = pointsData.filter(_.phase == DataPoint.Phase.Explorer).groupBy(_.actorName)
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
    
    val mainChart = factory.newChart()
    mainChart.add(scatter)
    mainChart.add(prospectsScatter)
    mainChart.open("Data Points", 800, 600)
    mainChart
  }

  def getClusterChart(pointsData: Set[DataPoint[Sample]], factory: IChartFactory): Chart = {
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
            (p -> new Coord2d(count.toDouble, variance.toDouble))
        })
    
    clusterThingMap.foreach((t) => {
        val (p, coord) = t
        logger.info(s"Parent point (${p.asInstanceOf[DataPoint[Point]].value._1}, ${p.asInstanceOf[DataPoint[Point]].value._2}) has ${coord.x} exploiter children and variance ${coord.y}.")
    })
    val clusterThingData = clusterThingMap.values.toArray
    println(s"Length ${clusterThingData.length}")
    //val clusterThingScatter = new ScatterPointSerie2d("Cluster Data")
    val clusterThingScatter = new Scatter(clusterThingData.map(c => new Coord3d(c.x, c.y, 0)))
    //clusterThingScatter.add(clusterThingData.toList.asJava)
    clusterThingScatter.setWidth(5)
    clusterThingScatter.setColor(Color.BLACK)
    val clusterChart = factory.newChart()
    clusterChart.add(clusterThingScatter)
    //clusterChart.view2d()
    clusterChart
  }
}
