import Dependencies._

ThisBuild / scalaVersion     := "3.8.3"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "eusocialcooperation.scheduler"
ThisBuild / organizationName := "Eusocial Cooperation"

lazy val root = (project in file("."))
  .settings(
    resolvers += ("jzy3d" at "http://maven.jzy3d.org/releases").withAllowInsecureProtocol(true),
    name := "hive-scheduler",
    coverageEnabled := true,
    libraryDependencies ++= Seq(
      scalatest % Test
      , pekkoActor
      , pekkoStream
      //, "org.slf4j" % "slf4j-simple" % "2.0.16"
      , "ch.qos.logback" % "logback-classic" % "1.5.32"
      //, "org.jzy3d" % "jzy3d" % "0.9" from("http://www.jzy3d.org/release/0.9/org.jzy3d-0.9.jar", true)
      , "org.jzy3d" % "jzy3d-emul-gl-awt" % "2.1.0"
      , "com.typesafe" % "config" % "1.4.3"
      , pekkoActorTestkit % Test
      , scalamock % Test
    )
  )

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
