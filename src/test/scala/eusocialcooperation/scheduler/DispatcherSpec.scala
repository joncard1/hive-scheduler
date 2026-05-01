package eusocialcooperation.scheduler

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicReference
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.Scheduler
import scala.util.Success

/** Tests for the Dispatcher actor.
  *
  * Tests use a no-op [[Dispatcher.WorkerFactory]] that spawns actors which
  * silently ignore all messages. This isolates the Dispatcher from Worker side
  * effects (Workers continuously call RequestPoints and AddProspect, which
  * would make tests checking exact prospect state racy) without relaxing the
  * numWorkers >= 1 validation or coupling the tests to the Worker
  * implementation.
  */
class DispatcherSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers {

  val testKit: ActorTestKit = ActorTestKit()

  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit lazy val scheduler: Scheduler = testKit.system.scheduler

  // Empty MDC: no per-experiment log routing needed in tests.
  given Map[String, String] = Map.empty

  // numWorkers must be >= 1 per the Dispatcher's validation; the no-op factory
  // ensures the spawned worker ignores all messages and never interferes.
  implicit val testConfig: com.typesafe.config.Config =
    ConfigFactory.parseString("dispatcher.numWorkers = 1")

  /** Worker factory that spawns a minimal stub actor. The stub replies to
    * [[Worker.Stop]] with [[Done]] (required for the Dispatcher's shutdown
    * sequence) and silently ignores every other message, so it never sends
    * AddProspect or RequestPoints messages that would race with test assertions
    * about prospect state.
    */
  val noOpWorkerFactory: Dispatcher.WorkerFactory = (ctx, i) =>
    ctx.spawn(
      Behaviors.receiveMessage[Worker.Command] {
        case Worker.Stop(replyTo) =>
          replyTo ! Worker.WorkerStopped(Success(()))
          Behaviors.stopped
        case _ =>
          Behaviors.same
      },
      s"noop-worker-$i"
    )

  override def afterAll(): Unit = testKit.shutdownTestKit()

  /** Spawns a fresh Dispatcher with empty memory stores and the no-op factory. */
  def makeDispatcher() = {
    val pointsMemory    = new AtomicReference[Set[DataPoint[Sample]]](Set.empty)
    val prospectsMemory = new AtomicReference[Set[DataPoint[Point]]](Set.empty)
    testKit.spawn(Dispatcher(pointsMemory, prospectsMemory, noOpWorkerFactory))
  }

  /** Constructs a DataPoint[Point] directly (constructor is test-accessible per
    * DataPoint's scaladoc). Each call with distinct arguments produces a unique
    * object with reference identity, which is how the internal Set[DataPoint[Point]]
    * distinguishes prospects.
    */
  def makePoint(
      seq: Long   = 0L,
      x: Double   = 0.5,
      y: Double   = 0.5
  ): DataPoint[Point] =
    new DataPoint[Point](
      seq,
      System.currentTimeMillis(),
      "test-worker",
      DataPoint.Phase.Explorer,
      (BigDecimal(x), BigDecimal(y))
    )

  // ---------------------------------------------------------------------------
  // 1. Actor lifecycle
  // ---------------------------------------------------------------------------

  test("Dispatcher starts successfully") {
    val dispatcher = makeDispatcher()
    dispatcher should not be null
    testKit.stop(dispatcher)
  }

  test("Dispatcher stops correctly and replies with Stopped") {
    val dispatcher = makeDispatcher()
    val result = Await.result(
      dispatcher.ask[Dispatcher.Response](Dispatcher.Stop(_)),
      5.seconds
    )
    result shouldBe a[Dispatcher.Stopped]
  }

  test("Dispatcher can be stopped while holding prospects") {
    val dispatcher = makeDispatcher()
    try {
      val point = makePoint()
      dispatcher ! Dispatcher.AddProspect(point, 10_000L)
      Thread.sleep(100)
      val result = Await.result(
        dispatcher.ask[Dispatcher.Response](Dispatcher.Stop(_)),
        5.seconds
      )
      result shouldBe a[Dispatcher.Stopped]
    } finally {
      // Actor is already stopped after the Stop reply; testKit.stop is a no-op
      // if the actor is already terminated but avoids leaking it on failure.
      testKit.stop(dispatcher)
    }
  }

  // ---------------------------------------------------------------------------
  // 2. RequestPoints
  // ---------------------------------------------------------------------------

  test("RequestPoints returns an empty set when no prospects have been added") {
    val dispatcher = makeDispatcher()
    try {
      val result = Await.result(
        dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)),
        3.seconds
      )
      result.points shouldBe empty
    } finally {
      testKit.stop(dispatcher)
    }
  }

  // ---------------------------------------------------------------------------
  // 3. AddProspect
  // ---------------------------------------------------------------------------

  test("Dispatcher accepts a prospect via AddProspect") {
    val dispatcher = makeDispatcher()
    try {
      val point = makePoint()
      dispatcher ! Dispatcher.AddProspect(point, 10_000L)
      Thread.sleep(100) // allow the message to be processed
      val result = Await.result(
        dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)),
        3.seconds
      )
      result.points should contain(point)
    } finally {
      testKit.stop(dispatcher)
    }
  }

  test("Dispatcher accumulates multiple prospects") {
    val dispatcher = makeDispatcher()
    try {
      val p1 = makePoint(1L, 0.1, 0.1)
      val p2 = makePoint(2L, 0.2, 0.2)
      val p3 = makePoint(3L, 0.3, 0.3)
      dispatcher ! Dispatcher.AddProspect(p1, 10_000L)
      dispatcher ! Dispatcher.AddProspect(p2, 10_000L)
      dispatcher ! Dispatcher.AddProspect(p3, 10_000L)
      Thread.sleep(100)
      val result = Await.result(
        dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)),
        3.seconds
      )
      result.points should contain allOf (p1, p2, p3)
    } finally {
      testKit.stop(dispatcher)
    }
  }

  test("Adding the same prospect twice does not duplicate it in the set") {
    val dispatcher = makeDispatcher()
    try {
      val point = makePoint()
      dispatcher ! Dispatcher.AddProspect(point, 10_000L)
      dispatcher ! Dispatcher.AddProspect(point, 10_000L)
      Thread.sleep(100)
      val result = Await.result(
        dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)),
        3.seconds
      )
      result.points should have size 1
    } finally {
      testKit.stop(dispatcher)
    }
  }

  // ---------------------------------------------------------------------------
  // 4. Timeout / RemoveProspect
  // ---------------------------------------------------------------------------

  test("Dispatcher removes a prospect after its timeout expires") {
    val dispatcher = makeDispatcher()
    try {
      val point = makePoint()
      dispatcher ! Dispatcher.AddProspect(point, 200L) // expires in 200 ms

      Thread.sleep(50)
      val beforeExpiry = Await.result(
        dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)),
        3.seconds
      )
      beforeExpiry.points should contain(point)

      Thread.sleep(400) // well past the 200 ms expiry
      val afterExpiry = Await.result(
        dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)),
        3.seconds
      )
      afterExpiry.points should not contain point
    } finally {
      testKit.stop(dispatcher)
    }
  }

  test("Dispatcher removes only the timed-out prospect, leaving others intact") {
    val dispatcher = makeDispatcher()
    try {
      val shortLived = makePoint(1L, 0.1, 0.1)
      val longLived  = makePoint(2L, 0.9, 0.9)
      dispatcher ! Dispatcher.AddProspect(shortLived, 200L)    // expires in 200 ms
      dispatcher ! Dispatcher.AddProspect(longLived,  10_000L) // stays for 10 s

      Thread.sleep(500) // past the 200 ms expiry
      val result = Await.result(
        dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)),
        3.seconds
      )
      result.points should not contain shortLived
      result.points should contain(longLived)
    } finally {
      testKit.stop(dispatcher)
    }
  }

  test("Dispatcher removes a prospect via explicit RemoveProspect message") {
    val dispatcher = makeDispatcher()
    try {
      val point = makePoint()
      dispatcher ! Dispatcher.AddProspect(point, 10_000L)
      Thread.sleep(100)
      dispatcher ! Dispatcher.RemoveProspect(point)
      Thread.sleep(100)
      val result = Await.result(
        dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)),
        3.seconds
      )
      result.points should not contain point
    } finally {
      testKit.stop(dispatcher)
    }
  }
}
