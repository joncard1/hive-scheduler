package eusocialcooperation.scheduler.archiver

import eusocialcooperation.scheduler._
import scala.util.Using
import java.io.PrintWriter
import java.io.Writer
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.FileOutputStream
import eusocialcooperation.scheduler.datapoint.DataPoint

object DefaultArchiver {
  private[archiver] def constructWriter(path: String, append: Boolean = false): PrintWriter = {
    new PrintWriter(
      new BufferedWriter(
        new OutputStreamWriter(
          new FileOutputStream(path, append)
        )
      )
    )
  }
}

class DefaultArchiver private[archiver] (
    experimentPath: String,
    pointsWriterFactory: () => Writer,
    prospectsWriterFactory: () => Writer,
    queueLengthWriterFactory: () => Writer,
    metadataWriterFactory: () => Writer
)
    extends Archiver
    with LoggingComponent {

  def this(experimentPath: String) /*(using Config)*/ = {

    this(
      experimentPath,
      () => DefaultArchiver.constructWriter(s"$experimentPath/pointsData.csv"),
      () => DefaultArchiver.constructWriter(s"$experimentPath/prospectsData.csv"),
      () => DefaultArchiver.constructWriter(s"$experimentPath/queueLengths.csv"),
      () => DefaultArchiver.constructWriter(s"$experimentPath/metadata.csv", true)
    )
  }

  private[archiver] def writeMetadata(typ: String, dp: DataPoint[?], writer: Writer): Unit = {
      writer.write(
        s"${dp.sequenceNumber}\t${typ}\t${dp.timestamp}\t${dp.actorName}\t${dp.phase}\t${dp.parent.fold("N/A")(_.sequenceNumber.toString())}\n"
      )
    }

  override def archivePointsData(
      pointsData: Seq[DataPoint[Sample]]
  ): Unit = {
    // Save points data
    Using.resources(
      pointsWriterFactory(),
      metadataWriterFactory()
    ) { (pointsWriter, metadataWriter) =>
      pointsData.foreach { dp =>
        pointsWriter.write(
          s"${dp.sequenceNumber}\t${dp.value._1.toDouble}\t${dp.value._2.toDouble}\t${dp.value._3.toDouble}\n"
        )
        writeMetadata("points", dp, metadataWriter)
      }
    }
  }

  def archiveProspectsData(prospects: Seq[DataPoint[Point]]): Unit = {

    Using.resources(
      prospectsWriterFactory(),
      metadataWriterFactory()
    ) { (prospectsWriter, metadataWriter) =>
      prospects.foreach { dp =>
        prospectsWriter.write(
          s"${dp.sequenceNumber}\t${dp.value._1.toDouble}\t${dp.value._2.toDouble}\n"
        )
        writeMetadata("prospects", dp, metadataWriter)
      }
    }
  }

  def archiveQueueLengthData(queueLengths: Seq[(Long, Int)]): Unit = {

    Using.resources(
      queueLengthWriterFactory(),
      metadataWriterFactory()
    ) { (queueLengthWriter, metadataWriter) =>
      queueLengths.foreach { dp =>
        queueLengthWriter.write(
          s"${dp._1}\t${dp._2}\n"
        )
      }
    }
  }
}
