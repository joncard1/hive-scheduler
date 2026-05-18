import Dependencies._

ThisBuild / scalaVersion     := "3.7.4"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "eusocialcooperation.scheduler"
ThisBuild / organizationName := "Eusocial Cooperation"

val slickVersion = "3.6.1"

lazy val root = (project in file("."))
  .settings(
    resolvers += ("jzy3d" at "http://maven.jzy3d.org/releases").withAllowInsecureProtocol(true),
    name := "hive-scheduler",
    coverageExcludedPackages := "eusocialcooperation\\.scheduler\\.datapoint\\.MetadataTable;eusocialcooperation\\.scheduler\\.datapoint\\.SampleTable;eusocialcooperation\\.scheduler\\.datapoint\\.ProspectTable;eusocialcooperation\\.scheduler\\.datapoint\\.PostgresSQLDataPoint;eusocialcooperation\\.scheduler\\.GUIApp;eusocialcooperation\\.scheduler\\.charter\\.JFreeCharter;eusocialcooperation\\.scheduler\\.MainLayoutController",
    libraryDependencies ++= Seq(
      scalatest % Test
      , pekkoActor
      , pekkoStream
      , "ch.qos.logback" % "logback-classic" % "1.5.32"
      , "com.typesafe" % "config" % "1.4.3"
      , "org.scalafx" %% "scalafx" % "25.0.2-R37"
      , "org.jfree" % "org.jfree.chart.fx" % "2.0.2"
      , "org.jfree" % "org.jfree.chart3d.fx" % "2.1.1"
      , "org.jfree" % "org.jfree.chart3d" % "2.1.1"
      , "org.postgresql" % "postgresql" % "42.5.0"
      , "com.typesafe.slick" %% "slick-hikaricp" % slickVersion
      , "com.typesafe.slick" %% "slick" % slickVersion
      , pekkoActorTestkit % Test
      , scalamock % Test
    )
  )
  .enablePlugins(DockerPlugin, AssemblyPlugin)

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", _*) => MergeStrategy.discard
  case PathList("reference.conf") => MergeStrategy.concat
  case _                        => MergeStrategy.first
}

lazy val it = (project in file("it"))
  .settings(
    name := "hive-scheduler-integration-tests",
    libraryDependencies ++= Seq(
      scalatest % Test
      , "com.typesafe.slick" %% "slick-hikaricp" % slickVersion % Test
      , "com.typesafe.slick" %% "slick" % slickVersion % Test
      , "org.postgresql" % "postgresql" % "42.5.0" % Test
    )
  )
  .dependsOn(root)

// Docker configuration

Docker / maintainer := "joncard93@hotmail.com"
dockerBaseImage := "eclipse-temurin:25"
val pekkoClusterPort = 7355
dockerExposedPorts := Seq(pekkoClusterPort)
Docker / mappings ++= Seq(
  (Compile / assembly).value -> s"${(Docker/defaultLinuxInstallLocation).value}/hive-scheduler.jar",
  (Compile / sourceDirectory).value / "docker" / "application.conf" -> s"${(Docker/defaultLinuxInstallLocation).value}/etc/application.conf"
)
dockerExposedVolumes := Seq("/opt/experiments")


// TODO: Adding /etc/hive-scheduler in anticipation of the application.conf I'll probably need to activate clustering and which I may want to add separately. experiment.conf and the parent experiment.conf I may want to make more configurable for Kubernetes; we'll see how that works.
// TODO: I'm apparently hard-coding the parent experiment to /experiments, and I forgot that this should probably be run the way I am running the experiments now, which is to to loop over a folder. Suggesting that I didn't design this for how I use it.
// TODO: I'm guessing that this will need to get runs, experimentsPath, and parent from the environment, or something, so that it can be specified in a Kubernetes Job.
dockerEntrypoint := Seq("java", "-Xmx5g", "-classpath", s"${(Docker/defaultLinuxInstallLocation).value}/etc", "-jar", s"${(Docker/defaultLinuxInstallLocation).value}/hive-scheduler.jar")
dockerCmd := Seq("--experimentsPath=/opt/experiments", "--parent=/opt/experiments", "--runs=10")
// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.