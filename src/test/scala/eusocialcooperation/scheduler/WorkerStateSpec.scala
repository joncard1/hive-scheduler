package eusocialcooperation.scheduler

import org.scalatest.funsuite.AnyFunSuite
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.util.Timeout
import scala.concurrent.duration._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import eusocialcooperation.scheduler.Dispatcher.AddProspect
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers

class WorkerStateSpec extends AnyFunSuite with BeforeAndAfterAll with MockFactory with OptionValues with Matchers {

    val testKit: ActorTestKit = ActorTestKit()

    implicit val timeout: Timeout = Timeout(30.seconds)
    implicit lazy val scheduler: org.apache.pekko.actor.typed.Scheduler = testKit.system.scheduler

    override def afterAll(): Unit = {
        println("shutting down")
        testKit.shutdownTestKit()
    }

    // TODO: Incorporate scalacheck
    test("ExplorerState should explore test 10 points randomly near a given point") {
        val numPoints = ExplorerState.numPointsToExplore
        val fn = mockFunction[BigDecimal, BigDecimal, BigDecimal]
        fn.expects(*, *).returning(BigDecimal(0.75)).repeat(numPoints)
        val startLocationX = BigDecimal(0.5)
        val startLocationY = BigDecimal(0.5)

        val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()
        val dispatcher = testKit.spawn(Behaviors.monitor(dispatcherProbe.ref, Behaviors.receiveMessage[Dispatcher.Command] {
            case Dispatcher.RequestPoints(replyTo) =>
                println("Received RequestPoints")
                replyTo ! Dispatcher.RequestedPoints(Set((startLocationX, startLocationY)))
                Behaviors.same
            case _ => Behaviors.same
        }), "dispatcher")
        implicit val dpProbe = testKit.createTestProbe[DataPointActor.Create[Worker.Sample]]().ref
        val state = ExplorerState((startLocationX, startLocationY), fn, BigDecimal(0.5), dispatcher)
        state()
        for (i <- 0 until numPoints) {
            dispatcherProbe.expectMessageType[Dispatcher.AddPoint]
        }
        dispatcherProbe.expectMessageType[Dispatcher.RequestPoints]
    }

    test("ExplorerState should explore test 10 points randomly near a given point, one of which is a prospect") {
        val numPoints = 10
        val fn = mockFunction[BigDecimal, BigDecimal, BigDecimal]
        fn.expects(*, *).returning(BigDecimal(0.75)).repeat(3)
        fn.expects(*, *).returning(BigDecimal(0.05))
        fn.expects(*, *).returning(BigDecimal(0.75)).repeat(6)
        val startLocationX = BigDecimal(0.5)
        val startLocationY = BigDecimal(0.5)

        val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()
        val dispatcher = testKit.spawn(Behaviors.monitor(dispatcherProbe.ref, Behaviors.receiveMessage[Dispatcher.Command] {
            case Dispatcher.RequestPoints(replyTo) =>
                println("Received RequestPoints")
                replyTo ! Dispatcher.RequestedPoints(Set((startLocationX, startLocationY)))
                Behaviors.same
            case _ => Behaviors.same
        }), "dispatcher")
        implicit val dpProbe = testKit.createTestProbe[DataPointActor.Create[Worker.Sample]]().ref
        val state = ExplorerState((startLocationX, startLocationY), fn, BigDecimal(0.5), dispatcher)
        state()
        for (i <- 0 until numPoints) {
            dispatcherProbe.expectMessageType[Dispatcher.AddPoint]
        }
        dispatcherProbe.expectMessageType[Dispatcher.AddProspect]
        dispatcherProbe.expectMessageType[Dispatcher.RequestPoints]
    }

    test("ExplorerState should explore test 10 points randomly near a given point, two of which is a prospect") {
        val numPoints = 10
        val fn = mockFunction[BigDecimal, BigDecimal, BigDecimal]
        fn.expects(*, *).returning(BigDecimal(0.75)).repeat(3)
        fn.expects(*, *).returning(BigDecimal(0.05))
        fn.expects(*, *).returning(BigDecimal(0.05))
        fn.expects(*, *).returning(BigDecimal(0.75)).repeat(5)
        val startLocationX = BigDecimal(0.5)
        val startLocationY = BigDecimal(0.5)
        var reportedDelay: Option[Long] = None

        val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()
        val dispatcher = testKit.spawn(Behaviors.monitor(dispatcherProbe.ref, Behaviors.receiveMessage[Dispatcher.Command] {
            case AddProspect(point, delayMs) => 
                reportedDelay = Some(delayMs)
                Behaviors.same
            case Dispatcher.RequestPoints(replyTo) =>
                replyTo ! Dispatcher.RequestedPoints(Set((startLocationX, startLocationY)))
                Behaviors.same
            case _ => Behaviors.same
        }), "dispatcher")
        implicit val dpProbe = testKit.createTestProbe[DataPointActor.Create[Worker.Sample]]().ref
        val state = ExplorerState((startLocationX, startLocationY), fn, BigDecimal(0.5), dispatcher)
        state()
        for (i <- 0 until numPoints) {
            dispatcherProbe.expectMessageType[Dispatcher.AddPoint]
        }
        dispatcherProbe.expectMessageType[Dispatcher.AddProspect]
        dispatcherProbe.expectMessageType[Dispatcher.RequestPoints]

        reportedDelay shouldBe defined
        val expectedDelay = ExplorerState.delayPerProspect * 2
        reportedDelay.value shouldBe expectedDelay
    }
}
