package eusocialcooperation.scheduler

import slick.jdbc.PostgresProfile
import slick.basic.DatabaseConfig
import eusocialcooperation.scheduler.datapoint.PostgresMetadataTable
import eusocialcooperation.scheduler.datapoint.PostgresSampleTable
import eusocialcooperation.scheduler.datapoint.PostgresProspectTable
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object Init {
  def main(args: Array[String]) = {
    val dbConfig = DatabaseConfig.forConfig[PostgresProfile]("eusocialcooperation.scheduler.postgres_db")
    import dbConfig.profile.api._

    val metadataTable = TableQuery[PostgresMetadataTable]
    val sampleTable = TableQuery[PostgresSampleTable]
    val prospectTable = TableQuery[PostgresProspectTable]
    Await.result(
        dbConfig.db.run(
            DBIO.seq(
                prospectTable.schema.createIfNotExists
                , sampleTable.schema.createIfNotExists
                , metadataTable.schema.createIfNotExists
            ).transactionally
        )
        , 5.seconds
    )
  }
}
