package eusocialcooperation.scheduler

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, BehaviorTestKit}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import org.apache.pekko.actor.typed.Scheduler
import java.util.concurrent.atomic.AtomicReference
import com.typesafe.config.Config
import org.scalamock.scalatest.MockFactory

class WorkerSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers with MockFactory {

  implicit val timeout: Timeout = Timeout(3.seconds)

  val testKit: ActorTestKit = ActorTestKit()
  implicit val scheduler: Scheduler = testKit.system.scheduler

  override def afterAll(): Unit = {
    println("Shutting down test kit in afterAll.")
    testKit.shutdownTestKit()
  }

  // Kernel that always returns 1.0 so runExplorer sleeps 0 ms in tests
  val testKernelFn: Worker.KernelFn = (_, _) => BigDecimal(1.0)

  def dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()


// TODO: Not convinced that this will stop the internal thread when stopped without the Stop command. Look up how this message would be communicated normally. I think there's some kind of subscription you have to make.
  test("Worker can be spawned with a kernel function and dispatcher") {
    implicit val config: Config = mock[Config]
    val workerConfig = mock[Config]
    (config.getConfig).expects("workers").returns(workerConfig)
    (workerConfig.getLong).expects("workerLoopDelayMs").returning(Worker.defaultLoopDelayMs)
    val worker = testKit.spawn(Worker(testKernelFn, dispatcherProbe.ref))
    worker should not be null
    testKit.stop(worker)
  }

  test("Worker stops when it receives a Stop message") {
    implicit val config: Config = mock[Config]
    val workerConfig = mock[Config]
    (config.getConfig).expects("workers").returns(workerConfig)
    (workerConfig.getLong).expects("workerLoopDelayMs").returning(Worker.defaultLoopDelayMs)
    val worker = testKit.spawn(Worker(testKernelFn, dispatcherProbe.ref))
    Await.result(worker.ask(Worker.Stop(_)), 5.seconds)
    testKit.stop(worker)
  }

  test("Worker handles Stop message when it has started the thread") {
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    implicit val config: Config = mock[Config]
    val workerConfig = mock[Config]
    (config.getConfig).expects("workers").returns(workerConfig)
    (workerConfig.getLong).expects("workerLoopDelayMs").returning(Worker.defaultLoopDelayMs)

    // Need to start a DataPoint worker for the start-up sequence of Worker to find.
    val dpa = testKit.spawn(DataPointActor[Sample](new AtomicReference[Set[DataPoint[Sample]]](Set.empty)))

    val worker = testKit.spawn(Worker(testKernelFn, dispatcherProbe.ref))
    // Wait for the worker to finish starting.
    Thread.sleep(1500) // Wait for the scheduled job to execute
    testKit.stop(worker)
    Thread.sleep(500) // Wait for the worker to process the stop message and shut down
  }

/*
  test("Worker explorer state is directly testable") {
    val dpProbe  = testKit.createTestProbe[DataPointActor.Create[Worker.Sample]]()
    val position = BigDecimal(0.5)
    val behaviorTestKit = BehaviorTestKit(
      Worker.explorer(testKernelFn, position, Set.empty, dpProbe.ref)
    )
    behaviorTestKit.isAlive shouldBe true
  }

  test("Worker exploiter state is directly testable") {
    val dpProbe  = testKit.createTestProbe[DataPointActor.Create[Worker.Sample]]()
    val position = BigDecimal(0.5)
    val behaviorTestKit = BehaviorTestKit(
      Worker.exploiter(testKernelFn, position, Set.empty, dpProbe.ref)
    )
    behaviorTestKit.isAlive shouldBe true
  }
  */

  test("Worker accepts different kernel functions") {
    implicit val config: Config = mock[Config]
    val workerConfig = mock[Config]
    (config.getConfig).expects("workers").returns(workerConfig)
    (workerConfig.getLong).expects("workerLoopDelayMs").returning(Worker.defaultLoopDelayMs)

    val alternateKernel: Worker.KernelFn = (x, y) => (x + y) / 2
    val worker = testKit.spawn(Worker(alternateKernel, dispatcherProbe.ref))
    worker should not be null
    testKit.stop(worker)
  }
}

