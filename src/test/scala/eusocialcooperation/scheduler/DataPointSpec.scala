package eusocialcooperation.scheduler

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import cats.Monad
import eusocialcooperation.scheduler.DataPoint._
import org.scalatest.BeforeAndAfterAll
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import eusocialcooperation.scheduler.DataPointActor.Create
import org.scalatest.OptionValues

class DataPointSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with OptionValues {
    val testKit: ActorTestKit = ActorTestKit()
    given Timeout = Timeout(3.seconds)

    override def afterAll(): Unit = testKit.shutdownTestKit()
    
    "DataPoint" should {
        "create a pure item" in {
            val sampleActorProbe = testKit.createTestProbe[DataPointActor.Create[Sample]]()
            val pointActorProbe = testKit.spawn[DataPointActor.Create[Point]](Behaviors.receiveMessage {
                case Create(value, phase, name, replyTo, parent) => 
                    replyTo ! new DataPoint(1, System.currentTimeMillis(), name, phase, value, parent)
                    Behaviors.same
            })
            given sampleActor: ActorRef[DataPointActor.Create[Sample]] = sampleActorProbe.ref
            given pointActor: ActorRef[DataPointActor.Create[Point]] = pointActorProbe.ref
            given Scheduler = testKit.scheduler
            given expectedContext: DataPointContext = DataPointContext("actor", "hostname")
            given expectedPhase: DataPoint.Phase = DataPoint.Phase.ExplorerStart
            val mm: Monad[DataPoint] = summon[Monad[DataPoint]]
            val dp = mm.pure((1.0, 2.0))
            sampleActorProbe.expectNoMessage()
            dp `shouldBe` a[DataPoint[Point]]
            dp.value shouldEqual (1.0, 2.0)
            dp.actorName shouldEqual expectedContext.actorName
            dp.sequenceNumber should be > 0L
            dp.timestamp should be > 0L
            dp.phase shouldEqual expectedPhase
        }

        "create a derivative item" in {
            val sampleActorProbe = testKit.createTestProbe[DataPointActor.Create[Sample]]()
            val pointActorProbe = testKit.createTestProbe[DataPointActor.Create[Point]]()
            given sampleActor: ActorRef[DataPointActor.Create[Sample]] = testKit.spawn[DataPointActor.Create[Sample]](Behaviors.monitor(sampleActorProbe.ref, Behaviors.receiveMessage {
                case Create(value, phase, name, replyTo, parent) => 
                    replyTo ! new DataPoint(1, System.currentTimeMillis(), name, phase, value, parent)
                        Behaviors.same
            }))
            given pointActor: ActorRef[DataPointActor.Create[Point]] = pointActorProbe.ref
            given Scheduler = testKit.scheduler
            val expectedParentActorName = "anotherActor"
            given expectedContext: DataPointContext = DataPointContext("actor", "hostname")
            given expectedPhase: DataPoint.Phase = DataPoint.Phase.Exploiter
            val mm: Monad[DataPoint] = summon[Monad[DataPoint]]
            val originalPoint = DataPoint(1, 0, expectedParentActorName, DataPoint.Phase.Explorer, (1.0, 2.0), None)
            val dp = mm.map(originalPoint)(_ => (3.0, 4.0, 5.0))
            pointActorProbe.expectNoMessage()
            dp `shouldBe` a[DataPoint[Sample]]
            dp.value shouldEqual (3.0, 4.0, 5.0)
            dp.actorName shouldEqual expectedContext.actorName
            dp.sequenceNumber should be > 0L
            dp.timestamp should be > 0L
            dp.phase shouldEqual expectedPhase
            dp.parent.value shouldEqual originalPoint
        }
    }
}
