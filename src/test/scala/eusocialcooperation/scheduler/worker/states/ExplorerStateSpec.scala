package eusocialcooperation.scheduler.worker.states

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.util.Timeout
import org.apache.pekko.actor.typed.Scheduler
import scala.concurrent.duration.DurationInt
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import eusocialcooperation.scheduler._
import org.apache.pekko.actor.typed.ActorRef
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Promise
import scala.concurrent.Await
import eusocialcooperation.scheduler.worker.states.ExplorerState.State
import scala.util.Using
import scala.util.Try
import com.typesafe.config.Config
import eusocialcooperation.scheduler.DataPoint.Phase

class ExplorerStateSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers with MockFactory with OptionValues {
    val testKit: ActorTestKit = ActorTestKit()

    implicit val timeout: Timeout = Timeout(30.seconds)
    implicit lazy val scheduler: Scheduler = testKit.system.scheduler

    val numSteps = 10
    val delayPerProspect = 50L
    val explorationRadius = 0.01
    val threshold = 0.05   
    val weightPerProspect = 0.2

    def getConfig() = {
        val config: Config = mock[Config]
         
        (config.getInt).expects(ExplorerState.numPointsToExploreConfigKey).returning(numSteps).atLeastOnce()
        (config.getMilliseconds).expects(ExplorerState.delayPerProspectConfigKey).returning(delayPerProspect).atLeastOnce()
        (config.getDouble).expects(ExplorerState.explorationRadiusConfigKey).returning(explorationRadius).atLeastOnce()
        (config.getDouble).expects(ExplorerState.thresholdConfigKey).returning(threshold).atLeastOnce()
        (config.getMilliseconds).expects(Worker.loopDelayConfigKey).returning(10L).anyNumberOfTimes()
        (config.getDouble).expects(Worker.weightPerProspectConfigKey).returning(weightPerProspect).anyNumberOfTimes()
        config
    }
    
    override def afterAll(): Unit = testKit.shutdownTestKit()

    // TODO: Incorporate scalacheck

    test("ExplorerState should explore 1 point") {
        given Config = getConfig()

        val fn = mockFunction[BigDecimal, BigDecimal, BigDecimal]
        fn.expects(*, *).returning(BigDecimal(0.75))
        val startLocationX = BigDecimal(0.5)
        val startLocationY = BigDecimal(0.5)

        val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()

        val sampleProbe = testKit.createTestProbe[DataPointActor.Create[Sample]]()
        given sampleActor: ActorRef[DataPointActor.Create[Sample]] = testKit.spawn(Behaviors.monitor(sampleProbe.ref, Behaviors.receiveMessage[DataPointActor.Create[Sample]] {
            case DataPointActor.Create(sample, phase, name, replyTo, parent) =>
                replyTo ! new DataPoint(0, 0, name, phase, sample, parent)
                Behaviors.same
        }), "dataPointActor")

        val pointProbe = testKit.createTestProbe[DataPointActor.Create[Point]]()
        given pointActor: ActorRef[DataPointActor.Create[Point]] = testKit.spawn(Behaviors.monitor(pointProbe.ref, Behaviors.receiveMessage[DataPointActor.Create[Point]] {
            case DataPointActor.Create(point, phase, name, replyTo, parent) =>            
                replyTo ! new DataPoint(0, 0, name, phase, point, parent)
                Behaviors.same
        }), "pointActor")

        try {
            val state = ExplorerState((startLocationX, startLocationY), fn, BigDecimal(0.5), dispatcherProbe.ref)
            val newState = state().asInstanceOf[ExplorerState]

            sampleProbe.expectMessageType[DataPointActor.Create[Sample]]
            newState.remainingSteps.value `shouldBe` (numSteps - 1)
            newState.state `shouldBe` ExplorerState.State.LookingForFirstLowValue
        } finally {
            testKit.stop(sampleActor)
            testKit.stop(pointActor)
        }
    }

    test("ExplorerState should explore the last point and change to a choose state") {
        given Config = getConfig()

        val numPoints = 10
        val fn = mockFunction[BigDecimal, BigDecimal, BigDecimal]
        fn.expects(*, *).returning(BigDecimal(0.5))
        val startLocationX = BigDecimal(0.5)
        val startLocationY = BigDecimal(0.5)
        val newProspects = Set(
            new DataPoint(0, 0, "name", DataPoint.Phase.Explorer, (BigDecimal(0.1), BigDecimal(0.1)), None)
            , new DataPoint(1, 0, "name", DataPoint.Phase.Explorer, (BigDecimal(0.2), BigDecimal(0.2)), None)
        )

        val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()
        /*
        val dispatcher = testKit.spawn(Behaviors.monitor(dispatcherProbe.ref, Behaviors.receiveMessage[Dispatcher.Command] {
            case Dispatcher.RequestPoints(replyTo) =>
                replyTo ! Dispatcher.RequestedPoints(newProspects)
                Behaviors.same
            case _ => Behaviors.same
        }), "dispatcher")
        */

        val sampleProbe = testKit.createTestProbe[DataPointActor.Create[Sample]]()
        given sampleActor: ActorRef[DataPointActor.Create[Sample]] = testKit.spawn(Behaviors.monitor(sampleProbe.ref, Behaviors.receiveMessage[DataPointActor.Create[Sample]] {
            case DataPointActor.Create(sample, phase, name, replyTo, parent) =>
                replyTo ! new DataPoint(0, 0, name, phase, sample, parent)
                Behaviors.same
        }), "dataPointActor")

        val pointProbe = testKit.createTestProbe[DataPointActor.Create[Point]]()
        given pointActor: ActorRef[DataPointActor.Create[Point]] = testKit.spawn(Behaviors.monitor(pointProbe.ref, Behaviors.receiveMessage[DataPointActor.Create[Point]] {
            case DataPointActor.Create(point, phase, name, replyTo, parent) =>
                replyTo ! new DataPoint(0, 0, name, phase, point, parent)
                Behaviors.same
        }), "pointActor")

        try {
            // Given the preference and the expected number of prospects delivered above, the worker should choose to be an exploiter, but if the preference is higher it should choose to be an explorer, so we can test both branches by adjusting the preference.
            val preference = BigDecimal((weightPerProspect * newProspects.size) + 0.001)
            val state = ExplorerState((startLocationX, startLocationY), fn, preference, dispatcherProbe.ref, Some(0))
            val newState = state().asInstanceOf[ChooseState]

            sampleProbe.expectMessageType[DataPointActor.Create[Sample]]
            //dispatcherProbe.expectMessageType[Dispatcher.RequestPoints]
        } finally {
            //testKit.stop(dispatcher)
            testKit.stop(sampleActor)
            testKit.stop(pointActor)
        }
    }

    test("ExplorerState should explore test 1 point that is a prospect") {
        given Config = getConfig()

        val numPoints = 10
        val fn = mockFunction[BigDecimal, BigDecimal, BigDecimal]
        fn.expects(*, *).returning(threshold - 0.001)
        val startLocationX = BigDecimal(0.5)
        val startLocationY = BigDecimal(0.5)

        val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()
        val sampleProbe = testKit.createTestProbe[DataPointActor.Create[Sample]]()
        given sampleActor: ActorRef[DataPointActor.Create[Sample]] = testKit.spawn(Behaviors.monitor(sampleProbe.ref, Behaviors.receiveMessage[DataPointActor.Create[Sample]] {
            case DataPointActor.Create(sample, phase, name, replyTo, parent) =>
                replyTo ! new DataPoint(0, 0, name, phase, sample, parent)
                Behaviors.same
        }), "dataPointActor")

        val pointProbe = testKit.createTestProbe[DataPointActor.Create[Point]]()
        given pointActor: ActorRef[DataPointActor.Create[Point]] = testKit.spawn(Behaviors.monitor(pointProbe.ref, Behaviors.receiveMessage[DataPointActor.Create[Point]] {
            case DataPointActor.Create(point, phase, name, replyTo, parent) =>
                replyTo ! new DataPoint(0, 0, name, phase, point, parent)
                Behaviors.same
        }), "pointActor")

        try {
            val state = ExplorerState((startLocationX, startLocationY), fn, BigDecimal(0.5), dispatcherProbe.ref)
            val newState = state().asInstanceOf[ExplorerState]

            sampleProbe.expectMessageType[DataPointActor.Create[Sample]]
            newState.remainingSteps.value `shouldBe` (numPoints - 1)
            newState.state `shouldBe` ExplorerState.State.LookingForHighValueAfterLow
        } finally {
            testKit.stop(sampleActor)
            testKit.stop(pointActor)
        }
    }

    test("ExplorerState should submit a prospect after finding a high value after a low value and transition to a choose state") {
        given config: Config = getConfig()

        val point1 = (BigDecimal(0.1), BigDecimal(0.1))
        val point2 = (BigDecimal(0.2), BigDecimal(0.2))
        val memory = Set(point1, point2)
        val fn = mockFunction[BigDecimal, BigDecimal, BigDecimal]
        fn.expects(*, *).returning(threshold + 0.001)
        val startLocationX = BigDecimal(0.5)
        val startLocationY = BigDecimal(0.5)
        val newProspects = Set(
            new DataPoint(0, 0, "name", DataPoint.Phase.Explorer, (BigDecimal(0.1), BigDecimal(0.1)), None)
            , new DataPoint(0, 0, "name", DataPoint.Phase.Explorer, (BigDecimal(0.2), BigDecimal(0.2)), None)
        )

        val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()
        var actualDelay = AtomicLong(0L)
        var actualProspect = AtomicReference(Option.empty[Point])
        val setterPromise = Promise[Unit]()
        val dispatcher = testKit.spawn(Behaviors.monitor(dispatcherProbe.ref, Behaviors.receiveMessage[Dispatcher.Command] {
            case Dispatcher.AddProspect(point, delayMs) => 
                actualProspect.set(Some(point.value))
                actualDelay.set(delayMs)
                setterPromise.success(())
                Behaviors.same
                /*
            case Dispatcher.RequestPoints(replyTo) =>
                replyTo ! Dispatcher.RequestedPoints(newProspects)
                Behaviors.same
                */
            case _ => Behaviors.same
        }), "dispatcher")

        val sampleProbe = testKit.createTestProbe[DataPointActor.Create[Sample]]()
        given sampleActor: ActorRef[DataPointActor.Create[Sample]] = testKit.spawn(Behaviors.monitor(sampleProbe.ref, Behaviors.receiveMessage[DataPointActor.Create[Sample]] {
            case DataPointActor.Create(sample, phase, name, replyTo, parent) =>
                replyTo ! new DataPoint(0, 0, name, phase, sample, parent)
                Behaviors.same
        }), "dataPointActor")

        val pointProbe = testKit.createTestProbe[DataPointActor.Create[Point]]()
        given pointActor: ActorRef[DataPointActor.Create[Point]] = testKit.spawn(Behaviors.monitor(pointProbe.ref, Behaviors.receiveMessage[DataPointActor.Create[Point]] {
            case DataPointActor.Create(point, phase, name, replyTo, parent) =>
                replyTo ! new DataPoint(0, 0, name, phase, point, parent)
                Behaviors.same
        }), "pointActor")

        try {
            val preference = BigDecimal(weightPerProspect * newProspects.size) - BigDecimal(0.001)
            val state = ExplorerState((startLocationX, startLocationY), fn, preference, dispatcher, None, ExplorerState.State.LookingForHighValueAfterLow, memory)
            val newState = state().asInstanceOf[ChooseState]

            sampleProbe.expectMessageType[DataPointActor.Create[Sample]]
            dispatcherProbe.expectMessageType[Dispatcher.AddProspect]
            // Not sure why the expectMessageType can succeed without the values being set; I think the probe is notified before the monitor behavior is run.
            Await.result(setterPromise.future, 3.seconds)
            actualDelay.get() `shouldBe` (delayPerProspect * 2)
            actualProspect.get() shouldBe defined
            // I think it needs to query for the next state
            //dispatcherProbe.expectMessageType[Dispatcher.RequestPoints]
        } finally {
            testKit.stop(dispatcher)
            testKit.stop(sampleActor)
            testKit.stop(pointActor)
        }
    }

    test("ExplorerState should explore test 1 point that is a prospect on its last step and not change states yet") {
        given Config = getConfig()

        val fn = mockFunction[BigDecimal, BigDecimal, BigDecimal]
        fn.expects(*, *).returning(BigDecimal(threshold) - 0.001)
        val startLocationX = BigDecimal(0.5)
        val startLocationY = BigDecimal(0.5)

        val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()

        val sampleProbe = testKit.createTestProbe[DataPointActor.Create[Sample]]()
        given sampleActor: ActorRef[DataPointActor.Create[Sample]] = testKit.spawn(Behaviors.monitor(sampleProbe.ref, Behaviors.receiveMessage[DataPointActor.Create[Sample]] {
            case DataPointActor.Create(sample, phase, name, replyTo, parent) =>
                replyTo ! new DataPoint(0, 0, name, phase, sample, parent)
                Behaviors.same
        }), "dataPointActor")

        val pointProbe = testKit.createTestProbe[DataPointActor.Create[Point]]()
        given pointActor: ActorRef[DataPointActor.Create[Point]] = testKit.spawn(Behaviors.monitor(pointProbe.ref, Behaviors.receiveMessage[DataPointActor.Create[Point]] {
            case DataPointActor.Create(point, phase, name, replyTo, parent) =>
                replyTo ! new DataPoint(0, 0, name, phase, point, parent)
                Behaviors.same
        }), "pointActor")

        try {
            val state = ExplorerState((startLocationX, startLocationY), fn, BigDecimal(threshold), dispatcherProbe.ref, Some(0), State.LookingForHighValueAfterLow)
            val newState = state().asInstanceOf[ExplorerState]

            sampleProbe.expectMessageType[DataPointActor.Create[Sample]]
            newState.remainingSteps.value `shouldBe` 0
            newState.state `shouldBe` ExplorerState.State.LookingForHighValueAfterLow
        } finally {
            testKit.stop(sampleActor)
            testKit.stop(pointActor)
        }
    }

}
