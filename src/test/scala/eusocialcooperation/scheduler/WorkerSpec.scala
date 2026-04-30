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
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.Done
import eusocialcooperation.scheduler.distributions.DistributionStrategy
import scala.util.Success
import eusocialcooperation.scheduler.worker.states.ExplorerState
import scala.concurrent.Future
import org.apache.pekko.actor.typed.receptionist.Receptionist.Listing
import scala.concurrent.ExecutionContext
import org.apache.pekko.actor.typed.ActorRef

class WorkerSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers with MockFactory {

  implicit val timeout: Timeout = Timeout(3.seconds)

  val testKit: ActorTestKit = ActorTestKit()
  implicit val scheduler: Scheduler = testKit.system.scheduler

  // The MDC configuration specifying where to store the logging output.
  given Map[String, String] = Map.empty

  override def afterAll(): Unit = {
    println("Shutting down test kit in afterAll.")
    testKit.shutdownTestKit()
  }

  def getDistributionConfigNode() = {
    val distributionConfig = stub[Config]
    (distributionConfig.getString).when("type").returning("uniform")
    distributionConfig
  }

  // Kernel that always returns 1.0 so runExplorer sleeps 0 ms in tests
  val testKernelFn: Worker.KernelFn = (_, _) => BigDecimal(1.0)

  def dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()


// TODO: Not convinced that this will stop the internal thread when stopped without the Stop command. Look up how this message would be communicated normally. I think there's some kind of subscription you have to make.
  test("Worker can be spawned with a kernel function and dispatcher") {
    implicit val config: Config = mock[Config]
    val workerConfig = mock[Config]
    (config.getConfig).expects("workers").returns(workerConfig)
  
    val worker = testKit.spawn(Worker(testKernelFn, dispatcherProbe.ref))
    try {
      worker should not be null
    } finally {
      testKit.stop(worker)
    }
  }

  test("Worker stops when it receives a Stop message") {
    given config: Config = mock[Config]
    val workerConfig = mock[Config]
    (config.getConfig).expects("workers").returns(workerConfig)

    val worker = testKit.spawn(Worker(testKernelFn, dispatcherProbe.ref))
    try {
      val response = Await.result[Worker.WorkerStopped](worker.ask(Worker.Stop(_)), 5.seconds)
      response.result shouldBe a[Success[Unit]]
    } finally {
      testKit.stop(worker)
    }
  }

  test("Worker handles Stop message when it has started the thread") {
    given ExecutionContext = ExecutionContext.global
    implicit val config: Config = mock[Config]
    val workerConfig = mock[Config]
    val distributionConfig = mock[Config]
    (config.getConfig).expects(Worker.workersConfigKey).returns(workerConfig)
    (workerConfig.getConfig).expects(DistributionStrategy.distributionConfigKey).returns(distributionConfig)
    (distributionConfig.getString).expects("type").returning("uniform")
    val workerThreadFactory: Worker.WorkerThreadFactory = (_, _, _, _) => Future.successful(())

    // Need to start a DataPoint worker for the start-up sequence of Worker to find.
    given dpaSample: ActorRef[DataPointActor.Command] = testKit.createTestProbe[DataPointActor.Command]().ref
    given dpaPoint: ActorRef[DataPointActor.Command] = testKit.createTestProbe[DataPointActor.Command]().ref

    val workerProbe = testKit.createTestProbe[Worker.Command]()
    val worker = testKit.spawn(Behaviors.monitor(workerProbe.ref, Worker(testKernelFn, dispatcherProbe.ref, workerThreadFactory)))

    try {
      // Wait for the worker to finish starting.
      worker ! Worker.DPActorListing(Listing(DataPointActor.DataPointActorKey[Sample], Set(dpaSample)))
      worker ! Worker.DPActorListing(Listing(DataPointActor.DataPointActorKey[Point], Set(dpaPoint)))
      Thread.sleep(1000) // Wait for the scheduled job to execute
      val response = Await.result[Worker.WorkerStopped](worker.ask(Worker.Stop(_)), 60.seconds)
      response.result shouldBe a[Success[Unit]]
    } finally {
      testKit.stop(worker)
    }
  }

  test("Worker reports error when it receives a Stop message when the thread failed") {
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    implicit val config: Config = mock[Config]
    val workerConfig = mock[Config]
    val distributionConfig = mock[Config]
    (config.getConfig).expects(Worker.workersConfigKey).returns(workerConfig)
    (workerConfig.getConfig).expects(DistributionStrategy.distributionConfigKey).returns(distributionConfig)
    (distributionConfig.getString).expects("type").returning("uniform")
    val workerThreadFactory: Worker.WorkerThreadFactory = (_, _, _, _) => Future.failed(new RuntimeException("Thread failed"))

    // Need to start a DataPoint worker for the start-up sequence of Worker to find.
    given dpaSample: ActorRef[DataPointActor.Command] = testKit.createTestProbe[DataPointActor.Command]().ref
    given dpaPoint: ActorRef[DataPointActor.Command] = testKit.createTestProbe[DataPointActor.Command]().ref

    val workerProbe = testKit.createTestProbe[Worker.Command]()
    val worker = testKit.spawn(Behaviors.monitor(workerProbe.ref, Worker(testKernelFn, dispatcherProbe.ref, workerThreadFactory)))
    try {
      // Wait for the worker to finish starting.
      worker ! Worker.DPActorListing(Listing(DataPointActor.DataPointActorKey[Sample], Set(dpaSample)))
      worker ! Worker.DPActorListing(Listing(DataPointActor.DataPointActorKey[Point], Set(dpaPoint)))
      //Thread.sleep(1000) // Wait for the scheduled job to execute
      val response = Await.result[Worker.WorkerStopped](worker.ask(Worker.Stop(_)), 5.seconds)
      response.result shouldBe a[scala.util.Failure[Unit]]
    } finally {}
      testKit.stop(worker)
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
    (config.getConfig).expects(Worker.workersConfigKey).returns(workerConfig)

    val alternateKernel: Worker.KernelFn = (x, y) => (x + y) / 2
    val worker = testKit.spawn(Worker(alternateKernel, dispatcherProbe.ref))
    try {
    worker should not be null
    } finally {
      testKit.stop(worker)
    }
  }
}

