package eusocialcooperation.scheduler

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import org.apache.pekko.actor.typed.Scheduler
import com.typesafe.config.Config
import org.scalamock.scalatest.MockFactory
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import eusocialcooperation.scheduler.distributions.DistributionStrategy
import scala.util.Success
import scala.concurrent.Future
import org.apache.pekko.actor.typed.receptionist.Receptionist.Listing
import scala.concurrent.ExecutionContext
import org.apache.pekko.actor.typed.ActorRef
import eusocialcooperation.scheduler.datapoint.DataPointActor
import eusocialcooperation.scheduler.dispatcher.Dispatcher
import com.typesafe.config.ConfigFactory
import eusocialcooperation.scheduler.datapoint.DataPoint

class WorkerSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers with MockFactory {

  implicit val timeout: Timeout = Timeout(3.seconds)

  val testKit: ActorTestKit = ActorTestKit()
  implicit val scheduler: Scheduler = testKit.system.scheduler

  // The MDC configuration specifying where to store the logging output.
  given Map[String, String] = Map.empty

  override def afterAll(): Unit = {
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
  given ExecutionContext = testKit.system.executionContext
  def noopWorkerThreadFactory(using ExecutionContext): Worker.WorkerThreadFactory = (_, _, _, running) => Future {
    while (running.get()) {
      Thread.sleep(100)
    }
  }

  given DataPoint.DataPointUnit[Sample] = mock[DataPoint.DataPointUnit[Sample]]
  given DataPoint.DataPointUnit[Point] = mock[DataPoint.DataPointUnit[Point]]

  // TODO: Not convinced that this will stop the internal thread when stopped without the Stop command. Look up how this message would be communicated normally. I think there's some kind of subscription you have to make.
  test("Worker can be spawned with a kernel function and dispatcher") {
    given ExecutionContext = testKit.system.executionContext
    implicit val config: Config = ConfigFactory.parseString("""
    workers {
      distribution {
        type = uniform
      }
    }
    """)
    val workerConfig = mock[Config]
    //(config.getConfig).expects("workers").returns(workerConfig)
  
    val worker = testKit.spawn(Worker(testKernelFn, dispatcherProbe.ref, 10.seconds, noopWorkerThreadFactory))
    try {
      worker should not be null
    } finally {
      testKit.stop(worker)
    }
  }

  test("Worker stops when it receives a Stop message") {
    given config: Config = ConfigFactory.parseString("""
    workers {
      distribution {
        type = uniform
      }
    }
    """)
    //val workerConfig = mock[Config]
    //(config.getConfig).expects("workers").returns(workerConfig)

    val worker = testKit.spawn(Worker(testKernelFn, dispatcherProbe.ref, 10.seconds, noopWorkerThreadFactory))
    try {
      val response = Await.result[Dispatcher.WorkerStopped](worker.ask(Worker.Stop(_)), 5.seconds)
      response.result shouldBe a[Success[Unit]]
    } finally {
      testKit.stop(worker)
    }
  }

  // TODO: This is a bad test. Waiting for 1 second isn't reliable, and I'm not sure the probe monitor will wait until the probe has finished processing before allowing passage forward.
  test("Worker handles Stop message when it has started the thread") {
    given ExecutionContext = ExecutionContext.global
    implicit val config: Config = mock[Config]
    val workerConfig = mock[Config]
    val distributionConfig = mock[Config]
    (config.getConfig).expects(Worker.workersConfigKey).returns(workerConfig)
    (workerConfig.getConfig).expects(DistributionStrategy.distributionConfigKey).returns(distributionConfig)
    (distributionConfig.getString).expects("type").returning("uniform")
    val workerThreadFactory: Worker.WorkerThreadFactory = (_, _, _, running) => Future { 
      while(running.get()) {
        Thread.sleep(100)
      }
      ()
    }

    // Need to start a DataPoint worker for the start-up sequence of Worker to find.
    given dpaSample: ActorRef[DataPointActor.Command] = testKit.createTestProbe[DataPointActor.Command]().ref
    given dpaPoint: ActorRef[DataPointActor.Command] = testKit.createTestProbe[DataPointActor.Command]().ref

    val workerProbe = testKit.createTestProbe[Worker.Command]()
    val worker = testKit.spawn(Behaviors.monitor(workerProbe.ref, Worker(testKernelFn, dispatcherProbe.ref, 10.seconds, workerThreadFactory)))

    try {
      // Wait for the worker to finish starting.
      worker ! Worker.DPActorListing(Listing(DataPointActor.DataPointActorKey[Sample], Set(dpaSample)))
      worker ! Worker.DPActorListing(Listing(DataPointActor.DataPointActorKey[Point], Set(dpaPoint)))
      //Thread.sleep(1000) // Wait for the scheduled job to execute
      workerProbe.expectMessageType[Worker.DPActorListing]
      workerProbe.expectMessageType[Worker.DPActorListing]
      val response = Await.result[Dispatcher.WorkerStopped](worker.ask(Worker.Stop(_)), 60.seconds)
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
    val dispProbe = dispatcherProbe
    val worker = testKit.spawn(Behaviors.monitor(workerProbe.ref, Worker(testKernelFn, dispProbe.ref, 10.seconds, workerThreadFactory)))
    try {
      // Wait for the worker to finish starting.
      worker ! Worker.DPActorListing(Listing(DataPointActor.DataPointActorKey[Sample], Set(dpaSample)))
      worker ! Worker.DPActorListing(Listing(DataPointActor.DataPointActorKey[Point], Set(dpaPoint)))
      workerProbe.expectMessageType[Worker.DPActorListing]
      workerProbe.expectMessageType[Worker.DPActorListing]
      dispProbe.receiveMessage() match {
        case Dispatcher.WorkerStopped(worker, result) =>
          result shouldBe a[scala.util.Failure[Unit]]
      }
    } finally {}
      testKit.stop(worker)
  }

  test("Worker accepts different kernel functions") {
    implicit val config: Config = ConfigFactory.parseString("""
    workers {
      distribution {
        type = uniform
      }
    }
    """)

    val alternateKernel: Worker.KernelFn = (x, y) => (x + y) / 2
    val worker = testKit.spawn(Worker(alternateKernel, dispatcherProbe.ref, 10.seconds, noopWorkerThreadFactory))
    try {
    worker should not be null
    } finally {
      testKit.stop(worker)
    }
  }

  test("Worker stops itself when the durationMs has elapsed") {
    given ExecutionContext =  testKit.system.executionContext
    implicit val config: Config = ConfigFactory.parseString("""
    workers: {
      distribution {
        type = uniform
      }
    }
    """)
    val durationMs = 1.second
    val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()
    val worker = testKit.spawn(Worker(kernel, dispatcherProbe.ref, durationMs, noopWorkerThreadFactory))
    try {
      dispatcherProbe.receiveMessage() match {
        case Dispatcher.WorkerStopped(_, Success(())) =>
      }
    } finally {
      testKit.stop(worker)
    }
  }
}

