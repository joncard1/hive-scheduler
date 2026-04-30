import Dependencies._

ThisBuild / scalaVersion     := "3.7.4"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "eusocialcooperation.scheduler"
ThisBuild / organizationName := "Eusocial Cooperation"

lazy val root = (project in file("."))
  .settings(
    resolvers += ("jzy3d" at "http://maven.jzy3d.org/releases").withAllowInsecureProtocol(true),
    name := "hive-scheduler",
    coverageEnabled := true,
    coverageExcludedPackages := "eusocialcooperation\\.scheduler\\.Demo;eusocialcooperation\\.scheduler\\.charter\\.JFreeCharter;eusocialcooperation\\.scheduler\\.MainLayoutController",
    libraryDependencies ++= Seq(
      scalatest % Test
      , pekkoActor
      , pekkoStream
      //, "org.slf4j" % "slf4j-simple" % "2.0.16"
      , "ch.qos.logback" % "logback-classic" % "1.5.32"
      //, "org.jzy3d" % "jzy3d" % "0.9" from("http://www.jzy3d.org/release/0.9/org.jzy3d-0.9.jar", true)
      //, "org.jzy3d" % "jzy3d-emul-gl-awt" % "2.1.0"
      //, "org.jzy3d" % "jzy3d-native-jogl-javafx" % "2.2.1"
      , "com.typesafe" % "config" % "1.4.3"
      , "org.scalafx" %% "scalafx" % "25.0.2-R37"
      , "org.jfree" % "org.jfree.chart.fx" % "2.0.2"
      , "org.jfree" % "org.jfree.chart3d.fx" % "2.1.1"
      , "org.jfree" % "org.jfree.chart3d" % "2.1.1"
      , pekkoActorTestkit % Test
      , scalamock % Test
    )
  )

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", _*) => MergeStrategy.discard
  case PathList("reference.conf") => MergeStrategy.concat
  case _                        => MergeStrategy.first
}

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
