package eusocialcooperation.scheduler.dispatcher

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
import org.apache.pekko.actor.typed.Scheduler
import scala.util.Success
import eusocialcooperation.scheduler.datapoint.DataPoint
import eusocialcooperation.scheduler.dispatcher.PekkoDispatcher
import org.apache.pekko.actor.typed.ActorRef
import eusocialcooperation.scheduler.dispatcher.Dispatcher
import eusocialcooperation.scheduler.Worker
import eusocialcooperation.scheduler.Point
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import eusocialcooperation.scheduler.Sample
import eusocialcooperation.scheduler.DataPointP

/** Tests for the Dispatcher actor.
  *
  * Tests use a no-op [[Dispatcher.WorkerFactory]] that spawns actors which
  * silently ignore all messages. This isolates the Dispatcher from Worker side
  * effects (Workers continuously call RequestPoints and AddProspect, which
  * would make tests checking exact prospect state racy) without relaxing the
  * numWorkers >= 1 validation or coupling the tests to the Worker
  * implementation.
  */
abstract class DispatcherSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers {

  val testKit: ActorTestKit = ActorTestKit()
  val testName = this.getClass().getSimpleName()

  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit lazy val scheduler: Scheduler = testKit.system.scheduler

  // Empty MDC: no per-experiment log routing needed in tests.
  given Map[String, String] = Map.empty

  // numWorkers must be >= 1 per the Dispatcher's validation; the no-op factory
  // ensures the spawned worker ignores all messages and never interferes.
  implicit val testConfig: com.typesafe.config.Config =
    ConfigFactory.parseString("""
    duration = 5s
    dispatcher.numWorkers = 1
    """)

  /** Worker factory that spawns a minimal stub actor. The stub replies to
    * [[Worker.Stop]] with [[Done]] (required for the Dispatcher's shutdown
    * sequence) and silently ignores every other message, so it never sends
    * AddProspect or RequestPoints messages that would race with test assertions
    * about prospect state.
    */
  val noOpWorkerFactory: Dispatcher.WorkerFactory = (duration, ctx, i, sampleUnit, prospectUnit) =>
    val log = ctx.log
    ctx.spawn(
      Behaviors.setup { context =>
        context.scheduleOnce(duration, context.self, Worker.Stop(ctx.self))
        Behaviors.receiveMessage[Worker.Command] {
          case Worker.Stop(replyTo) =>
            log.info(s"No-op worker $i stopping")
            replyTo ! Dispatcher.WorkerStopped(context.self, Success(()))
            Behaviors.stopped
          case _ =>
            Behaviors.same
        }
      },
      s"noop-worker-$i"
    )

  override def afterAll(): Unit = testKit.shutdownTestKit()

  /** Spawns a fresh Dispatcher with empty memory stores and the no-op factory. */
  def makeDispatcher(workerFactory: Dispatcher.WorkerFactory = noOpWorkerFactory): ActorRef[Dispatcher.Command]

  /** Constructs a DataPoint[Point] directly (constructor is test-accessible per
    * DataPoint's scaladoc). Each call with distinct arguments produces a unique
    * object with reference identity, which is how the internal Set[DataPoint[Point]]
    * distinguishes prospects.
    */
  def makePoint(
      seq: Long   = 0L,
      x: Double   = 0.5,
      y: Double   = 0.5
  ): DataPointP =
    new DataPointP(
      seq,
      System.currentTimeMillis(),
      "test-worker",
      DataPoint.Phase.Explorer,
      (BigDecimal(x), BigDecimal(y))
    )

  // ---------------------------------------------------------------------------
  // 1. Actor lifecycle
  // ---------------------------------------------------------------------------

  test(s"${testName} starts successfully") {
    val dispatcher = makeDispatcher()
    dispatcher should not be null
    testKit.stop(dispatcher)
  }

  test(s"${testName} stops correctly and replies with Stopped") {
    val dispatcher = makeDispatcher()
    try {
      val result = Await.result(
        dispatcher.ask[Dispatcher.Response](Dispatcher.Stop(_)),
        5.seconds
      )
      result shouldBe a[Dispatcher.Stopped]
    } finally {
      testKit.stop(dispatcher)
    }
  }

  test(s"${testName} can be stopped while holding prospects") {
    val dispatcher = makeDispatcher()
    try {
      val endProbe = testKit.createTestProbe[Dispatcher.Response]()
      dispatcher ! Dispatcher.StartRun("experiment", 0, 1.second, endProbe.ref)
      val point = makePoint()
      dispatcher ! Dispatcher.AddProspect(point, 10_000L)
      Thread.sleep(100)
      val result = Await.result(
        dispatcher.ask[Dispatcher.Response](Dispatcher.Stop(_)),
        5.seconds
      )
      result shouldBe a[Dispatcher.Stopped]
      endProbe.expectNoMessage()
    } finally {
      // Actor is already stopped after the Stop reply; testKit.stop is a no-op
      // if the actor is already terminated but avoids leaking it on failure.
      testKit.stop(dispatcher)
    }
  }

  // ---------------------------------------------------------------------------
  // 2. RequestPoints
  // ---------------------------------------------------------------------------

  test(s"In ${testName}, RequestPoints returns an empty set when no prospects have been added") {
    val dispatcher = makeDispatcher()
    try {
      val endProbe = testKit.createTestProbe[Dispatcher.Response]()
      dispatcher ! Dispatcher.StartRun("experiment", 0, 1.second, endProbe.ref)
      val result = Await.result(
        dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)),
        3.seconds
      )
      result.points.foreach(p => s"${p.value}")
      result.points shouldBe empty
      endProbe.expectNoMessage()
    } finally {
      testKit.stop(dispatcher)
    }
  }

  // ---------------------------------------------------------------------------
  // 3. AddProspect
  // ---------------------------------------------------------------------------

  test(s"${testName} accepts a prospect via AddProspect") {
    val dispatcher = makeDispatcher()
    try {
      val endProbe = testKit.createTestProbe[Dispatcher.Response]()
      dispatcher ! Dispatcher.StartRun("experiment", 0, 1.second, endProbe.ref)

      val point = makePoint()
      dispatcher ! Dispatcher.AddProspect(point, 10_000L)
      Thread.sleep(100) // allow the message to be processed
      val result = Await.result(
        dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)),
        3.seconds
      )
      result.points should contain(point)
      endProbe.expectNoMessage()
    } finally {
      testKit.stop(dispatcher)
    }
  }

  test(s"${testName} accumulates multiple prospects") {
    val dispatcher = makeDispatcher()
    try {
      val endProbe = testKit.createTestProbe[Dispatcher.Response]()
      dispatcher ! Dispatcher.StartRun("experiment", 0, 1.second, endProbe.ref)

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
      endProbe.expectNoMessage()
    } finally {
      testKit.stop(dispatcher)
    }
  }

  test(s"In ${testName}, adding the same prospect twice does not duplicate it in the set") {
    val dispatcher = makeDispatcher()
    try {
      val endProbe = testKit.createTestProbe[Dispatcher.Response]()
      dispatcher ! Dispatcher.StartRun("experiment", 0, 1.second, endProbe.ref)

      val point = makePoint()
      dispatcher ! Dispatcher.AddProspect(point, 10_000L)
      dispatcher ! Dispatcher.AddProspect(point, 10_000L)
      Thread.sleep(100)
      val result = Await.result(
        dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)),
        3.seconds
      )
      result.points.foreach(p => println(s"(${p.value._1}, ${p.value._1}, ${p.value._1}"))
      result.points should have size 1
      endProbe.expectNoMessage()
    } finally {
      testKit.stop(dispatcher)
    }
  }

  // ---------------------------------------------------------------------------
  // 4. Timeout / RemoveProspect
  // ---------------------------------------------------------------------------

  test(s"${testName} removes a prospect after its timeout expires") {
    val dispatcher = makeDispatcher()
    try {
      val endProbe = testKit.createTestProbe[Dispatcher.Response]()
      dispatcher ! Dispatcher.StartRun("experiment", 0, 1.second, endProbe.ref)

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
      endProbe.expectNoMessage()
    } finally {
      testKit.stop(dispatcher)
    }
  }

  test(s"${testName} removes only the timed-out prospect, leaving others intact") {
    val dispatcher = makeDispatcher()
    try {
      val endProbe = testKit.createTestProbe[Dispatcher.Response]()
      dispatcher ! Dispatcher.StartRun("experiment", 0, 1.second, endProbe.ref)

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
      endProbe.expectNoMessage()
    } finally {
      testKit.stop(dispatcher)
    }
  }

  test(s"${testName} removes a prospect via explicit RemoveProspect message") {
    val dispatcher = makeDispatcher()
    try {
      val endProbe = testKit.createTestProbe[Dispatcher.Response]()
      dispatcher ! Dispatcher.StartRun("experiment", 0, 1.second, endProbe.ref)

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
      endProbe.expectNoMessage()
    } finally {
      testKit.stop(dispatcher)
    }
  }

  test(s"${testName} should send the WorkersStopped message when the workers set is empty.") {
    val workerFactory: Dispatcher.WorkerFactory = (duration: FiniteDuration, ctx: ActorContext[Dispatcher.Command], run: Int, sampleUnit: DataPoint.DataPointUnit[Sample], prospectUnit: DataPoint.DataPointUnit[Point]) => {
      ctx.spawn(
        Behaviors.setup[Worker.Command] { context => 
          ctx.self ! Dispatcher.WorkerStopped(context.self, Success(()))
          Behaviors.stopped
        }
        , "no-op-worker"
      )
    }

    val probe = testKit.createTestProbe[Dispatcher.Command]()
    // Set the duration to a longer timeout than
    val dispatcher = testKit.spawn(Behaviors.monitor(probe.ref, PekkoDispatcher(new AtomicReference(Set()), new AtomicReference(Set()), workerFactory)))
    try {
      val endProbe = testKit.createTestProbe[Dispatcher.Response]()
      dispatcher ! Dispatcher.StartRun("experiment", 0, 1.second, endProbe.ref)

      probe.expectMessageType[Dispatcher.StartRun](1.seconds)
      probe.expectMessageType[Dispatcher.WorkerStopped](3.seconds)
      probe.expectMessageType[Dispatcher.WorkersStopped](3.seconds)
      endProbe.expectMessageType[Dispatcher.RunCompleted]
    } finally {
      testKit.stop(dispatcher)
    }
   }

  // TODO: Currently, it sends the message and uses a pipeToSelf to wait for all responses. If it instead listens for WorkerStopped messages, then it's possible to just wait forever for the response to come and it may not.
  test(s"${testName} should timeout when it sends a Stop message to a worker and then does not receive a response by doing something about it and responding with Stopped.") { // TODO: This should specify a timeout somewhere.
    val dispatcher = makeDispatcher((duration, ctx, i, sampleUnit, prospectUnit) =>
      val log = ctx.log
      ctx.spawn(
        Behaviors.setup[Worker.Command] { context =>
          Thread.sleep(10)
          Behaviors.stopped
        },
        s"noop-worker-$i"
    ))
    try {
      val endProbe = testKit.createTestProbe[Dispatcher.Response]()
      dispatcher ! Dispatcher.StartRun("experiment", 0, 1.second, endProbe.ref)

      val result = Await.result(
        dispatcher.ask[Dispatcher.Response](Dispatcher.Stop(_)),
        5.seconds
      )
      result shouldBe a[Dispatcher.Stopped]
      endProbe.expectNoMessage()
    } finally {
      testKit.stop(dispatcher)
    }
  }
  //test("Dispatcher should send Stop messages to all workers when it receives the Stop message.") { ??? }
  //test("Dispatcher should send a RunCompleted message when it receives a DataReset message.") { ??? }
}
