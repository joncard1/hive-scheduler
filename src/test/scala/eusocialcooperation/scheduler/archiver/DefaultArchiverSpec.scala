package eusocialcooperation.scheduler.archiver

import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuite
import org.scalamock.scalatest.MockFactory
import java.io.StringWriter
import eusocialcooperation.scheduler._
import eusocialcooperation.scheduler.datapoint.DataPoint

class DefaultArchiverSpec extends AnyFunSuite with Matchers with MockFactory {
  test("DefaultArchiver can be instantiated with a path") {
    val archiver = new DefaultArchiver("testPath")
    archiver shouldBe a[DefaultArchiver]
  }

  test("DefaultArchiver will write a collection of DataPoints to the specified path") {
    val pointsWriter = StringWriter()
    val prospectsWriter = StringWriter()
    val metadataWriter = StringWriter()
    val queueLengthsWriter = StringWriter()

    val archiver = new DefaultArchiver("testPath", () => pointsWriter, () => prospectsWriter, () => queueLengthsWriter, () => metadataWriter)
    val point1 = (BigDecimal(1.0), BigDecimal(2.0), BigDecimal(3.0))
    val prospect1 = (BigDecimal(4.0), BigDecimal(5.0))
    val dpProspect = new DataPoint(2, 100L, "actor2", DataPoint.Phase.Explorer, prospect1, None)
    val pointsData = Seq(
      new DataPoint(1, 0L, "actor1", DataPoint.Phase.Exploiter, point1, Some(dpProspect))
    )
    val prospects = Seq(
      dpProspect
    )
    val queue_lengths = Seq(
      (0L, 5),
      (100L, 3)
    )

    archiver.archivePointsData(pointsData)
    pointsWriter.toString should include("1\t1.0\t2.0\t3.0")

    archiver.archiveProspectsData(prospects)
    prospectsWriter.toString should include("2\t4.0\t5.0")

    metadataWriter.toString should include("1\tpoints\t0\tactor1\tExploiter\t2")
    metadataWriter.toString should include("2\tprospects\t100\tactor2\tExplorer\tN/A") 

    archiver.archiveQueueLengthData(queue_lengths)  
    queueLengthsWriter.toString should include("0\t5")
    queueLengthsWriter.toString should include("100\t3")
  }
}
