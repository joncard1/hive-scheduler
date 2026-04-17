import sbt._

object Dependencies {
  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.2.19"

  val pekkoVersion = "1.1.3"
  lazy val pekkoActor       = "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion
  lazy val pekkoStream      = "org.apache.pekko" %% "pekko-stream"       % pekkoVersion
  lazy val pekkoActorTestkit = "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion
}
