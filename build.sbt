import Dependencies._

ThisBuild / scalaVersion     := "3.8.3"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "eusocialcooperation.scheduler"
ThisBuild / organizationName := "Eusocial Cooperation"

lazy val root = (project in file("."))
  .settings(
    name := "hive-scheduler",
    libraryDependencies ++= Seq(
      scalatest % Test
      , pekkoActor
      , pekkoStream
      , pekkoActorTestkit % Test
      , scalamock % Test
    )
  )

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
