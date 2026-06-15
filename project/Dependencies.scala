import sbt._

object Dependencies {
  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.2.19"

  val PekkoVersion = "1.1.5"
  val PekkoManagementVersion = "1.2.1"
  val scalamockVersion = "7.5.5"
  lazy val pekkoActor         = "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion
  lazy val pekkoStream        = "org.apache.pekko" %% "pekko-stream"       % PekkoVersion
  lazy val pekkoActorTestkit  = "org.apache.pekko" %% "pekko-actor-testkit-typed" % PekkoVersion
  lazy val pekkoCluster       = "org.apache.pekko" %% "pekko-cluster-typed" % PekkoVersion
  lazy val pekkoSerialization = "org.apache.pekko" %% "pekko-serialization-jackson" % PekkoVersion
  lazy val pekkoDiscovery     = "org.apache.pekko" %% "pekko-discovery" % PekkoVersion
  lazy val pekkoManagement    = "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % PekkoManagementVersion
  lazy val pekkoK8sDisc       = "org.apache.pekko" %% "pekko-discovery-kubernetes-api" % PekkoManagementVersion

  lazy val scalamock = "org.scalamock" %% "scalamock" % scalamockVersion

  lazy val pekkoMultiNodeTesting = "org.apache.pekko" %% "pekko-multi-node-testkit" % PekkoVersion
}
