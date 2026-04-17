package eusocialcooperation.scheduler

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, BehaviorTestKit}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WorkerSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers {

  val testKit: ActorTestKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  // Kernel that always returns 1.0 so runExplorer sleeps 0 ms in tests
  val testKernelFn: Worker.KernelFn = (_, _) => BigDecimal(1.0)

  def dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()


// TODO: Not convinced that this will stop the internal thread when stopped without the Stop command. Look up how this message would be communicated normally. I think there's some kind of subscription you have to make.
  test("Worker can be spawned with a kernel function and dispatcher") {
    val worker = testKit.spawn(Worker(testKernelFn, dispatcherProbe.ref))
    worker should not be null
    testKit.stop(worker)
  }

  test("Worker stops when it receives a Stop message") {
    val worker = testKit.spawn(Worker(testKernelFn, dispatcherProbe.ref))
    worker ! Worker.Stop
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
    val alternateKernel: Worker.KernelFn = (x, y) => (x + y) / 2
    val worker = testKit.spawn(Worker(alternateKernel, dispatcherProbe.ref))
    worker should not be null
    testKit.stop(worker)
  }
}

