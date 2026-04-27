package eusocialcooperation.scheduler.charter

import eusocialcooperation.scheduler.Charter
import eusocialcooperation.scheduler.DataPoint
import eusocialcooperation.scheduler.Sample
import eusocialcooperation.scheduler.Point
import scalafx.scene.Node
import org.jfree.chart3d.data.xyz.XYZSeries
import org.jfree.chart3d.Chart3DFactory
import org.jfree.chart3d.data.xyz.XYZSeriesCollection
import org.jfree.chart3d.Chart3DPanel
import java.awt.Dimension
import org.jfree.chart3d.graphics3d.swing.DisplayPanel3D
import org.jfree.chart.fx.ChartCanvas
import org.jfree.data.xy.XYSeries
import org.jfree.chart.ChartFactory
import org.jfree.data.xy.XYSeriesCollection
import org.jfree.chart.fx.ChartViewer
import scalafx.scene.layout.Region
import org.jfree.chart.ChartPanel
import scalafx.scene.layout.Pane
import org.jfree.chart3d.Chart3DFactory
import org.jfree.chart3d.fx.Chart3DViewer
import org.jfree.chart3d.graphics3d.ViewPoint3D
import org.jfree.chart3d.Chart3D
import org.jfree.chart.JFreeChart
import org.jfree.chart3d.`export`.ExportUtils
import org.jfree.data.category.CategoryDataset
import java.awt.geom.Line2D
import org.jfree.data.category.DefaultCategoryDataset
import org.jfree.data.statistics.DefaultMultiValueCategoryDataset
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer

class JFreeCharter extends Charter {
    override def getMainChart(pointsData: Set[DataPoint[Sample]], prospects: Set[DataPoint[Point]]): Chart3D = {
        val series = new XYZSeries("Point Data")
            
        pointsData
            .filter(_.phase == DataPoint.Phase.Exploiter)
            .foreach { p => series.add(p.value._1.toDouble, p.value._3.toDouble, p.value._2.toDouble) }
        
        val dataset = new XYZSeriesCollection[String]()
        dataset.add(series)
        val chart = Chart3DFactory.createScatterChart("Points", 
                "", dataset, "X", "Height", "Y")
        chart.setViewPoint(ViewPoint3D.createAboveRightViewPoint(40))
        chart
    }

    override def getPoints2DChart(pointsData: Set[DataPoint[Sample]], prospects: Set[DataPoint[Point]]): JFreeChart = {
        val pointsSeries = new XYSeries("Points")
        val prospectsSeries = new XYSeries("Prospects")
        pointsData
            .filter(_.phase == DataPoint.Phase.Exploiter)
            .foreach { p => pointsSeries.add(p.value._1.toDouble, p.value._2.toDouble) }
        prospects
            .foreach { p => prospectsSeries.add(p.value._1.toDouble, p.value._2.toDouble) }
        val collection = new XYSeriesCollection()
        collection.addSeries(prospectsSeries)
        collection.addSeries(pointsSeries)
        val chart = ChartFactory.createScatterPlot("Points", "X", "Y", collection)
        chart
    }
    
    override def getClusterChart(pointsData: Set[DataPoint[Sample]]): JFreeChart = {
        val series = new XYSeries("Cluster Analysis")
        pointsData
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
            .foreach((t) => {
                series.add(t._2._1.toDouble, t._2._2.toDouble)
            })
        val chart = ChartFactory.createScatterPlot("Cluster Analysis", "Count", "Variance", new XYSeriesCollection(series))
        chart
    }

    override def getLengthSamplesChart(lengthSamples: List[(Long, Int)]): JFreeChart = {
        val series = new XYSeries("Queue Length")
        lengthSamples.foreach { case (timestamp, length) => series.add(timestamp.toDouble, length.toDouble) }
        val renderer = new XYLineAndShapeRenderer(true, false)
        val chart = ChartFactory.createScatterPlot("Queue Length Over Time", "Time (ms)", "Queue Length", new XYSeriesCollection(series))
        chart.getXYPlot().setRenderer(renderer)
        chart
    }
}
