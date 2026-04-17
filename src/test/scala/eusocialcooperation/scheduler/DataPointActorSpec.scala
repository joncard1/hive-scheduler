package eusocialcooperation.scheduler

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalatest.matchers.should.Matchers

class DataPointActorSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers {

  val testKit: ActorTestKit = ActorTestKit()

  implicit val timeout: Timeout = Timeout(3.seconds)
  implicit lazy val scheduler: org.apache.pekko.actor.typed.Scheduler = testKit.system.scheduler

  override def afterAll(): Unit = testKit.shutdownTestKit()

  test("DataPointActor replies with a DataPoint wrapping the given value") {
    val actor = testKit.spawn(DataPointActor[String]())

    val result = Await.result(
      actor.ask[DataPoint[String]](DataPointActor.Create("hello", _)),
      3.seconds
    )
    result.value shouldEqual "hello"
  }

  test("DataPointActor works with a non-String type") {
    val actor = testKit.spawn(DataPointActor[Int]())

    val result = Await.result(
      actor.ask[DataPoint[Int]](DataPointActor.Create(42, _)),
      3.seconds
    )

    result.value shouldEqual 42
  }

  test ("DataPointActor increments sequence number") {
    val actor = testKit.spawn(DataPointActor[String]())

    val result1 = Await.result(
      actor.ask[DataPoint[String]](DataPointActor.Create("first", _)),
      3.seconds
    )
    result1.sequenceNumber shouldEqual 0

    val result2 = Await.result(
      actor.ask[DataPoint[String]](DataPointActor.Create("second", _)),
      3.seconds
    )
    result2.sequenceNumber shouldEqual 1
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
