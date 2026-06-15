import Dependencies._

ThisBuild / scalaVersion     := "3.7.4"
ThisBuild / organization     := "eusocialcooperation.scheduler"
ThisBuild / organizationName := "Eusocial Cooperation"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

val slickVersion = "3.6.1"

lazy val root = (project in file("."))
  .configs(MultiJvm)
  .settings(
    resolvers += ("jzy3d" at "http://maven.jzy3d.org/releases").withAllowInsecureProtocol(true),
    name := "hive-scheduler",
    coverageExcludedPackages := "eusocialcooperation\\.scheduler\\.processor\\.*;eusocialcooperation\\.scheduler\\.datapoint\\.Postgres.*;eusocialcooperation\\.scheduler\\.dispatcher\\.ClusterDispatcher;eusocialcooperation\\.scheduler\\.GUIApp;eusocialcooperation\\.scheduler\\.charter\\.JFreeCharter;eusocialcooperation\\.scheduler\\.MainLayoutController",
    libraryDependencies ++= Seq(
      scalatest % Test
      , pekkoActor
      , pekkoCluster
      , pekkoStream
      , pekkoDiscovery
      , pekkoSerialization
      , pekkoManagement
      , pekkoK8sDisc
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
      , pekkoMultiNodeTesting % Test
    )
    , scalacOptions += {
     if (scalaVersion.value.startsWith("2.12"))
       "-Ywarn-unused-import"
     else
       "-Wunused:imports"
   },
  )
  .enablePlugins(DockerPlugin, AssemblyPlugin, MultiJvmPlugin)

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", _*) => MergeStrategy.discard
  case PathList("reference.conf") => MergeStrategy.concat
  case _                        => MergeStrategy.first
}
Test / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "src" / "multi-jvm" / "scala"

lazy val it = (project in file("it"))
  .settings(
    name := "hive-scheduler-integration-tests",
    libraryDependencies ++= Seq(
      scalatest % Test
      , "com.typesafe.slick" %% "slick-hikaricp" % slickVersion % Test
      , "com.typesafe.slick" %% "slick" % slickVersion % Test
      , "org.postgresql" % "postgresql" % "42.5.0" % Test
      , pekkoActorTestkit % Test
    )
  )
  .dependsOn(root % "test->test;compile->compile")

ThisBuild / dynverSeparator := "-"

// Docker configuration

Docker / maintainer := "joncard93@hotmail.com"
dockerBaseImage := "eclipse-temurin:25"
dockerUpdateLatest := true
val pekkoClusterPort = 7355
dockerExposedPorts := Seq(pekkoClusterPort)
Docker / mappings ++= Seq(
  (Compile / assembly).value -> s"${(Docker/defaultLinuxInstallLocation).value}/hive-scheduler.jar",
  (Compile / sourceDirectory).value / "docker" / "application.conf" -> s"${(Docker/defaultLinuxInstallLocation).value}/etc/application.conf",
  (Compile / sourceDirectory).value / "docker" / "logback.xml" -> s"${(Docker/defaultLinuxInstallLocation).value}/etc/logback.xml"
)
dockerExposedVolumes := Seq("/opt/experiments")


// TODO: Adding /etc/hive-scheduler in anticipation of the application.conf I'll probably need to activate clustering and which I may want to add separately. experiment.conf and the parent experiment.conf I may want to make more configurable for Kubernetes; we'll see how that works.
// TODO: I'm apparently hard-coding the parent experiment to /experiments, and I forgot that this should probably be run the way I am running the experiments now, which is to to loop over a folder. Suggesting that I didn't design this for how I use it.
// TODO: I'm guessing that this will need to get runs, experimentsPath, and parent from the environment, or something, so that it can be specified in a Kubernetes Job.
dockerEntrypoint := Seq("java", "-Xmx5g", "-classpath", s"${(Docker/defaultLinuxInstallLocation).value}/etc:${(Docker/defaultLinuxInstallLocation).value}/hive-scheduler.jar")
dockerCmd := Seq("--experimentsPath=/opt/experiments", "--parent=/opt/experiments", "--runs=10")
// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.

lazy val init = (project)
  .settings(
    Docker / mappings ++= Seq(
      (root / Compile / assembly).value -> s"${(Docker/defaultLinuxInstallLocation).value}/hive-scheduler.jar"
      , (root / Compile / sourceDirectory).value / "docker" / "application.conf" -> s"${(Docker/defaultLinuxInstallLocation).value}/etc/application.conf"
      , (root / Compile / sourceDirectory).value / "docker" / "logback.xml" -> s"${(Docker/defaultLinuxInstallLocation).value}/etc/logback.xml"
    )
    , dockerEntrypoint := Seq("java", "-classpath", s"${(Docker/defaultLinuxInstallLocation).value}/hive-scheduler.jar:${(Docker/defaultLinuxInstallLocation).value}/etc")
    , dockerCmd := Seq("eusocialcooperation.scheduler.Init")
    , dockerUpdateLatest := true
    , dockerBaseImage := "eclipse-temurin:25"
    , Docker / maintainer := "joncard93@hotmail.com"
  ).dependsOn(root)
  .enablePlugins(DockerPlugin)