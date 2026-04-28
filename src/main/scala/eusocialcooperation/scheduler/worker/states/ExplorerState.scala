package eusocialcooperation.scheduler.worker.states

import eusocialcooperation.scheduler._
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.Scheduler
import scala.util.Random
import eusocialcooperation.scheduler.worker.states.ExplorerState.State
import eusocialcooperation.scheduler.DataPoint.Phase
import com.typesafe.config.Config

object ExplorerState {
    val numPointsToExploreConfigKey = "explorer.numPointsToExplore"
    val explorationRadiusConfigKey = "explorer.explorationRadius"
    val thresholdConfigKey = "explorer.threshold"
    val delayPerProspectConfigKey = "explorer.delayPerProspect"
    enum State {
        case LookingForFirstLowValue, LookingForHighValueAfterLow, NextState
    }
}

case class ExplorerState(
    startLocation: Point,
    kernelFn: Worker.KernelFn,
    preference: BigDecimal
    , dispatcher: ActorRef[Dispatcher.Command]
    , var remainingSteps: Option[Int] = None
    , state: State = State.LookingForFirstLowValue
    , memory: Set[Point] = Set.empty)(implicit config: Config) extends WorkerState with LoggingComponent{

    given phase: DataPoint.Phase = DataPoint.Phase.Explorer
    given pointParent: Option[DataPoint[?]] = None

    val numStepsToExplore = config.getInt(ExplorerState.numPointsToExploreConfigKey)
    val explorationRadius = config.getDouble(ExplorerState.explorationRadiusConfigKey)
    val threshold = config.getDouble(ExplorerState.thresholdConfigKey)
    val delayPerProspect = config.getMilliseconds(ExplorerState.delayPerProspectConfigKey)

    remainingSteps = Option(remainingSteps.getOrElse(numStepsToExplore))

    override def apply()(using sampleRef: ActorRef[DataPointActor.Create[Sample]], pointRef: ActorRef[DataPointActor.Create[Point]], scheduler: Scheduler): WorkerState = {
        logger.info(s"Exploring at location: {} with state: {} and remaining steps: {}", startLocation, state, remainingSteps)
    
        var (x, y) = startLocation
        
        // Explore a certain number of points.
        val result = kernelFn(x, y)
        val res = DataPoint((x, y, result))

        //dispatcher ! Dispatcher.AddPoint(res)
        var angleMin = 0.0
        var angleMax = 2 * Math.PI
        if ((x - explorationRadius < 0.0) && (y - explorationRadius < 0.0)) {
            angleMin = 0.0
            angleMax = Math.PI / 2
        } else if ((x + explorationRadius > 1.0) && (y - explorationRadius < 0.0)) {
            angleMin = Math.PI / 2
            angleMax = Math.PI
        } else if (y - explorationRadius < 0.0) {
            angleMin = 0.0
            angleMax = Math.PI
        } else if (x + explorationRadius > 1.0) {
            angleMin = Math.PI / 2
            angleMax = 3 * Math.PI / 2
        } else if ((x - explorationRadius < 0.0) && (y + explorationRadius > 1.0)) {
            angleMin = Math.PI
            angleMax = 3 * Math.PI / 2
        } else if ((x + explorationRadius > 1.0) && (y + explorationRadius > 1.0)) {
            angleMin = 3 * Math.PI / 2
            angleMax = 2 * Math.PI
        } else if ((y + explorationRadius > 1.0)) {
            angleMin = Math.PI
            angleMax = 2 * Math.PI
        } else if ((x - explorationRadius < 0.0)) {
            angleMin = -Math.PI / 2
            angleMax = Math.PI / 2
        } else if ((x + explorationRadius > 1.0)) {
            angleMin = Math.PI / 2
            angleMax = 3 * Math.PI / 2
        }

        val angle = Random.between(angleMin, angleMax)
        x = x + explorationRadius * Math.cos(angle)
        y = y + explorationRadius * Math.sin(angle)
        
        val newPoint = (x, y)
        
        var newMemory = memory
        val newState = state match
            case _ if result < threshold => 
                logger.info("Found a point below the threshold: {}, adding to memory and continuing to look for more points below the threshold.", result)
                newMemory = memory + (newPoint)
                State.LookingForHighValueAfterLow
            case State.LookingForFirstLowValue =>
                State.LookingForFirstLowValue
            case State.LookingForHighValueAfterLow if result < threshold =>
                newMemory = memory + (newPoint)
                State.LookingForHighValueAfterLow
            case State.LookingForHighValueAfterLow =>
                if (memory.nonEmpty) {
                    memory.fold[Point]((BigDecimal(0.0), BigDecimal(0.0)))((acc, sample) => (acc._1 + sample._1, acc._2 + sample._2)) match {
                        case (sumX, sumY) =>
                            val numSamples = memory.size
                            val avgX = sumX / numSamples
                            val avgY = sumY / numSamples
                            val prospect = DataPoint((avgX, avgY))
                            // TODO: Refactor out the calculation of the delay to make clear this could be non-linear
                            dispatcher ! Dispatcher.AddProspect(prospect, delayPerProspect * numSamples)
                    }
                }
                State.NextState
            case State.NextState =>
                throw new RuntimeException("ExplorerState should not be in NextState state during exploration.")

        // TODO: If in the LookingForHighValueAterLow, maybe just continue exploring until we find a high value
        newState match
            case State.LookingForHighValueAfterLow =>
                logger.info("New state is still looking, keeping state")
                this.copy(startLocation = newPoint, remainingSteps = Option(Math.max(0, remainingSteps.get - 1)), state = newState, memory = newMemory)
            case State.NextState => // These two states should be the same, but "|" doesn't work with an "if" clause
                logger.info("Found a high, moving to another state.")
                ChooseState(kernelFn, preference, dispatcher)
            case _ if remainingSteps.get <= 0 =>
                logger.info("Ran out of steps, moving to another state")
                ChooseState(kernelFn, preference, dispatcher)
            case _ =>
                logger.info("Continuing exploration, decrementing remaining steps")
                this.copy(startLocation = newPoint, remainingSteps = Option(remainingSteps.get - 1), state = newState, memory = newMemory)
    }
}
