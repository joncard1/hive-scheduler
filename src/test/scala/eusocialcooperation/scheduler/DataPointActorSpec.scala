package eusocialcooperation.scheduler

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalatest.matchers.should.Matchers
import java.util.concurrent.atomic.AtomicReference
import eusocialcooperation.scheduler.DataPoint.Phase

class DataPointActorSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers {

  val testKit: ActorTestKit = ActorTestKit()

  implicit val timeout: Timeout = Timeout(3.seconds)
  implicit lazy val scheduler: org.apache.pekko.actor.typed.Scheduler = testKit.system.scheduler

  // The MDC configuration specifying where to store the logging output.
  given Map[String, String] = Map.empty

  override def afterAll(): Unit = testKit.shutdownTestKit()

  test("DataPointActor replies with a DataPoint wrapping the given value") {
    val memory = new AtomicReference[Set[DataPoint[String]]](Set.empty)
    val actor = testKit.spawn(DataPointActor[String](memory))

    try {
      val result = Await.result(
        actor.ask[DataPoint[String]](DataPointActor.Create("hello", Phase.Explorer, "name", _)),
        3.seconds
      )
      result.value shouldEqual "hello"
    } finally {
      testKit.stop(actor)
    }
  }

  test("DataPointActor works with a non-String type") {
    val memory = new AtomicReference[Set[DataPoint[Int]]](Set.empty)
    val actor = testKit.spawn(DataPointActor[Int](memory))

    try {
      val result = Await.result(
        actor.ask[DataPoint[Int]](DataPointActor.Create(42, Phase.Explorer, "name", _)),
        3.seconds
      )

      result.value shouldEqual 42
    } finally {
      testKit.stop(actor)
    }
  }

  test ("DataPointActor increments sequence number") {
    val memory = new AtomicReference[Set[DataPoint[String]]](Set.empty)

    val actor = testKit.spawn(DataPointActor[String](memory))

    try {
      val result1 = Await.result(
        actor.ask[DataPoint[String]](DataPointActor.Create("first", Phase.Explorer, "name", _)),
        3.seconds
      )
      result1.sequenceNumber shouldEqual 0

      val result2 = Await.result(
        actor.ask[DataPoint[String]](DataPointActor.Create("second", Phase.Explorer, "name", _)),
        3.seconds
      )
      result2.sequenceNumber shouldEqual 1
    } finally {
      testKit.stop(actor)
    }
  }

  test("kernel returns 0.0 for inputs (0.0, 0.0)") {
    val result = kernel(BigDecimal(0.0), BigDecimal(0.0))
    result shouldEqual BigDecimal(0.0)
  }

  test("kernel returns 1.0 for inputs (0.25, 0.25)") {
    val result = kernel(BigDecimal(0.25), BigDecimal(0.25))
    result shouldEqual BigDecimal(1.0)
  }
}
