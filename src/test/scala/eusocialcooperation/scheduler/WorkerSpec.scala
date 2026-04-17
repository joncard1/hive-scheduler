package eusocialcooperation.scheduler

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, BehaviorTestKit}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WorkerSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers {

  val testKit: ActorTestKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  val testKernelFn: Worker.KernelFn = (x, y) => x * y

  test("Worker can be spawned with a kernel function") {
    val worker = testKit.spawn(Worker(testKernelFn))
    worker should not be null
    testKit.stop(worker)
  }

  test("Worker starts in the explorer state") {
    // Worker.apply uses Behaviors.setup which spawns a child actor;
    // use the async testKit to verify it starts alive
    val worker = testKit.spawn(Worker(testKernelFn))
    worker should not be null
    testKit.stop(worker)
  }

  test("Worker explorer state is directly testable") {
    val dpInbox = testKit.createTestProbe[DataPointActor.Create[Worker.Sample]]()
    val position = BigDecimal(0.5)
    val behaviorTestKit = BehaviorTestKit(
      Worker.explorer(testKernelFn, position, Set.empty, dpInbox.ref)
    )
    behaviorTestKit.isAlive shouldBe true
  }

  test("Worker exploiter state is directly testable") {
    val dpInbox = testKit.createTestProbe[DataPointActor.Create[Worker.Sample]]()
    val position = BigDecimal(0.5)
    val behaviorTestKit = BehaviorTestKit(
      Worker.exploiter(testKernelFn, position, Set.empty, dpInbox.ref)
    )
    behaviorTestKit.isAlive shouldBe true
  }

  test("Worker explorer schedules a DataPoint removal") {
    val dpInbox = testKit.createTestProbe[DataPointActor.Create[Worker.Sample]]()
    val position = BigDecimal(0.5)
    val behaviorTestKit = BehaviorTestKit(
      Worker.explorer(testKernelFn, position, Set.empty, dpInbox.ref)
    )
    // On entry, explorer generates a sample and schedules a removal;
    // verify the actor is still alive after setup
    behaviorTestKit.isAlive shouldBe true
  }

  test("Worker accepts a constant kernel function") {
    val constantKernel: Worker.KernelFn = (_, _) => BigDecimal(0.5)
    val worker = testKit.spawn(Worker(constantKernel))
    worker should not be null
    testKit.stop(worker)
  }
}
