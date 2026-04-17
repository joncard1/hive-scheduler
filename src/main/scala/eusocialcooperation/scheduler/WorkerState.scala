package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern._

import scala.util.Random
import scala.concurrent.Future
import scala.concurrent.Await
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import org.apache.pekko.actor.typed.Scheduler


sealed trait WorkerState {
    /**
      * The probability the worker will choose to be an explorer rather than an exploiter.
      */
    val preference: BigDecimal

    /**
      * The dispatcher to communicate with to submit points and request prospects.
      */
    val dispatcher: ActorRef[Dispatcher.Command]

    enum Phase:
        case Explorer, Exploiter

    def apply()(using ActorRef[DataPointActor.Create[Sample]]):  WorkerState = ???

    def chooseState(createExplorer: () => WorkerState, createExploiter: ((BigDecimal, BigDecimal)) => WorkerState)(implicit scheduler: Scheduler): WorkerState = {
        // TODO: Maybe make this an implicit parameter
        implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
        implicit val timeout: Timeout = Timeout(3.seconds)

        println("Requesting points")
        val state = dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)).map(_.points).map({
            case s if s.nonEmpty => 
                // Adjust it so that the more prospects there are, the more likely the exploiter state is to be chosen, but in a non-linear way that still gives some chance to the explorer state even with many prospects.
                val adjustedPreference = (1.0 - Math.pow((1.0 - preference).toDouble, s.size))
                // I'm assuming I got this backwards
                if (Random.nextDouble() < adjustedPreference) createExplorer()
                else
                    createExploiter(Random.shuffle(s).head)
            case _ => createExplorer()
        })
        // TODO: Revisit being infinite.
        Await.result(state, scala.concurrent.duration.Duration.Inf)
    }
}

object ExplorerState {
    /**
      * The number of points to explore before choosing whether to transition to the exploiter state or to continue exploring. This is a hyperparameter that may require tuning.
      */
    val numPointsToExplore = 10
    /**
     * The threshold below which a point is considered promising enough to be sent to the dispatcher as a prospect. This is a hyperparameter that may require tuning.
     */
    val threshold = BigDecimal(0.1)
    /**
      * The radius around the current location the next randomly selected point will be located. This is a hyperparameter that may require tuning.
      */
    val explorationRadius = BigDecimal(0.1)

    /** Number of milliseconds to delay a prospect for each point in the explorer's memory. This is a hyperparameter that may require tuning. */
    val delayPerProspect = 100L
}

case class ExplorerState(
    startLocation: (BigDecimal, BigDecimal),
    kernelFn: Worker.KernelFn,
    preference: BigDecimal
    , dispatcher: ActorRef[Dispatcher.Command])(implicit scheduler: Scheduler) extends WorkerState {

    /**
      * The memory of points considered promising enough to be sent to the dispatcher as prospects. Other approaches to consider include submitting a promising location after the first high value after the first low value, or refining the search pattern when a low value is discovered, and/or stopping after finding n points lower than threshold.
      */
    var memory: Set[Point] = Set.empty

    override def apply()(using ActorRef[DataPointActor.Create[Sample]]): WorkerState = {
        var (x, y) = startLocation
        
        // Explore a certain number of points.
        for (i <- 0 until ExplorerState.numPointsToExplore) {
            val result = kernelFn(x, y)
            if result < ExplorerState.threshold then memory = memory + ((x, y))
            val res = DataPoint((x, y, result))

            dispatcher ! Dispatcher.AddPoint(res)
            val angle = Random.between(0.0, 2 * Math.PI)
            x = ExplorerState.explorationRadius * Math.cos(angle)
            y = ExplorerState.explorationRadius * Math.sin(angle)
            // Protecting against going out of bounds with a toroidal topology is probably dumb.
            if x < 0 then x = x + 1.0
            if x > 1 then x = x - 1.0
            if y < 0 then y = y + 1.0
            if y > 1 then y = y - 1.0
        }

        // TODO: This will break if the explorer goes over the edge to the other side of the space.
        if (memory.nonEmpty) {
            memory.fold[Point]((BigDecimal(0.0), BigDecimal(0.0)))((acc, sample) => (acc._1 + sample._1, acc._2 + sample._2)) match {
                case (sumX, sumY) =>
                    val numSamples = memory.size
                    val avgX = sumX / numSamples
                    val avgY = sumY / numSamples
                    // TODO: Refactor out the calculation of the delay to make clear this could be non-linear
                    dispatcher ! Dispatcher.AddProspect((avgX, avgY), ExplorerState.delayPerProspect * numSamples)
            }
        }

        // TODO: If this value of this is low, send to the dispatcher as a prospect. Weight the prospect according to how many zeroes this explorer has seen nearby.
        chooseState(
            () => this.copy(startLocation = (BigDecimal(Random.nextDouble()), BigDecimal(Random.nextDouble()))),
            (sample) => ExploiterState(sample, preference, dispatcher)
        )
    }
}
case class ExploiterState(prospect: (BigDecimal, BigDecimal), preference: BigDecimal, dispatcher: ActorRef[Dispatcher.Command]) extends WorkerState {
    override def apply()(using ActorRef[DataPointActor.Create[Sample]]): WorkerState = ??? /*{
        // TODO: Choose a point nearby the point provided and then sample 100 points nearby
    }*/
}