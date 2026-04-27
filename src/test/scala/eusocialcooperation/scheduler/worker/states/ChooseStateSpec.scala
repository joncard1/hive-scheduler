package eusocialcooperation.scheduler.worker.states

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.Promise
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import com.typesafe.config.Config
import eusocialcooperation.scheduler.Dispatcher
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import eusocialcooperation.scheduler.DataPoint
import eusocialcooperation.scheduler.Dispatcher.RequestPoints
import eusocialcooperation.scheduler.DataPointActor
import eusocialcooperation.scheduler.Worker.Sample
import org.apache.pekko.actor.typed.ActorRef
import eusocialcooperation.scheduler.Point
import eusocialcooperation.scheduler.Worker

class ChooseStateSpec extends AnyFunSuite with BeforeAndAfterAll with MockFactory with OptionValues with Matchers {

    val testKit: ActorTestKit = ActorTestKit()

    val numSteps = 10
    val delayPerProspect = 50L
    val explorationRadius = 0.01
    val threshold = 0.05   

    val weightPerProspect = 0.2

    implicit val timeout: Timeout = Timeout(30.seconds)
    implicit lazy val scheduler: org.apache.pekko.actor.typed.Scheduler = testKit.system.scheduler

    override protected def afterAll(): Unit = testKit.shutdownTestKit()

    def getConfig() = {
        val config: Config = mock[Config]

        (config.getMilliseconds).expects(Worker.loopDelayConfigKey).returning(10L).atLeastOnce()
        (config.getDouble).expects(Worker.weightPerProspectConfigKey).returning(weightPerProspect).atLeastOnce()
        config
    }

    test("Execute ChooseState with 2 prospects and a low preference should transition to ExploiterState") {
        given config: Config = getConfig()
        (config.getDouble).expects(ExploiterState.fuzzinessConfigKey).returning(0.01).anyNumberOfTimes()
        (config.getDouble).expects(ExploiterState.incrementConfigKey).returning(0.001).anyNumberOfTimes()

        val fn = mockFunction[BigDecimal, BigDecimal, BigDecimal]

        val newProspects = Set(
            new DataPoint(0, 0, "name", DataPoint.Phase.Explorer, (BigDecimal(0.1), BigDecimal(0.1)), None)
            , new DataPoint(1, 0, "name", DataPoint.Phase.Explorer, (BigDecimal(0.2), BigDecimal(0.2)), None)
        )
        val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()
        val dispatcher = testKit.spawn(Behaviors.monitor(dispatcherProbe.ref, Behaviors.receiveMessage[Dispatcher.Command] {
            case Dispatcher.RequestPoints(replyTo) =>
                replyTo ! Dispatcher.RequestedPoints(newProspects)
                Behaviors.same
            case _ => Behaviors.same
        }), "dispatcher")

        given sampleActor: ActorRef[DataPointActor.Create[Sample]] = testKit.createTestProbe[DataPointActor.Create[Sample]]().ref
        given pointActor: ActorRef[DataPointActor.Create[Point]] = testKit.createTestProbe[DataPointActor.Create[Point]]().ref

        try {
            val preference = BigDecimal((weightPerProspect * newProspects.size) - 0.001)
            val state = ChooseState(fn, preference, dispatcher)
            val newState = state().asInstanceOf[ExploiterState]

            dispatcherProbe.expectMessageType[RequestPoints]
        } finally {
            testKit.stop(dispatcher)
        }
    }

    test("Execute ChooseState with 2 prospects and a high preference should transition to ExplorerState") {
        given config: Config = getConfig()
        (config.getInt).expects(ExplorerState.numPointsToExploreConfigKey).returning(numSteps).atLeastOnce()
        (config.getMilliseconds).expects(ExplorerState.delayPerProspectConfigKey).returning(delayPerProspect).atLeastOnce()
        (config.getDouble).expects(ExplorerState.explorationRadiusConfigKey).returning(explorationRadius).atLeastOnce()
        (config.getDouble).expects(ExplorerState.thresholdConfigKey).returning(threshold).atLeastOnce()

        val fn = mockFunction[BigDecimal, BigDecimal, BigDecimal]

        val newProspects = Set(
            new DataPoint(0, 0, "name", DataPoint.Phase.Explorer, (BigDecimal(0.1), BigDecimal(0.1)), None)
            , new DataPoint(1, 0, "name", DataPoint.Phase.Explorer, (BigDecimal(0.2), BigDecimal(0.2)), None)
        )
        val dispatcherProbe = testKit.createTestProbe[Dispatcher.Command]()
        val dispatcher = testKit.spawn(Behaviors.monitor(dispatcherProbe.ref, Behaviors.receiveMessage[Dispatcher.Command] {
            case Dispatcher.RequestPoints(replyTo) =>
                replyTo ! Dispatcher.RequestedPoints(newProspects)
                Behaviors.same
            case _ => Behaviors.same
        }), "dispatcher")

        given sampleActor: ActorRef[DataPointActor.Create[Sample]] = testKit.createTestProbe[DataPointActor.Create[Sample]]().ref
        given pointActor: ActorRef[DataPointActor.Create[Point]] = testKit.createTestProbe[DataPointActor.Create[Point]]().ref

        try {
            val preference = BigDecimal((weightPerProspect * newProspects.size) + 0.001)
            val state = ChooseState(fn, preference, dispatcher)
            val newState = state().asInstanceOf[ExplorerState]

            dispatcherProbe.expectMessageType[RequestPoints]
        } finally {
            testKit.stop(dispatcher)
        }
     }
} 
