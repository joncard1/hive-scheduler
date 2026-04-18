package eusocialcooperation.scheduler

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern._

import scala.util.Random
import scala.concurrent.Future
import scala.concurrent.Await
import org.apache.pekko.util.Timeout
import scala.concurrent.duration.DurationInt
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.ActorContext


sealed trait WorkerState {
    /**
      * The probability the worker will choose to be an explorer rather than an exploiter.
      */
    val preference: BigDecimal

    /**
      * The dispatcher to communicate with to submit points and request prospects.
      */
    val dispatcher: ActorRef[Dispatcher.Command]

    def apply()(using ActorRef[DataPointActor.Create[Sample]], ActorContext[?]):  WorkerState = ???

    def chooseState(createExplorer: () => WorkerState, createExploiter: ((BigDecimal, BigDecimal)) => WorkerState)(implicit scheduler: Scheduler): WorkerState = {
        // TODO: Maybe make this an implicit parameter
        implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
        implicit val timeout: Timeout = Timeout(3.seconds)

        val state = dispatcher.ask[Dispatcher.RequestedPoints](Dispatcher.RequestPoints(_)).map(_.points).map({
            case s if s.nonEmpty => 
                // Adjust it so that the more prospects there are, the more likely the exploiter state is to be chosen, but in a non-linear way that still gives some chance to the explorer state even with many prospects.
                //val adjustedPreference = (1.0 - Math.pow((1.0 - preference).toDouble, s.size))
                // I'm assuming I got this backwards
                /*
                if (Random.nextDouble() < adjustedPreference) createExplorer()
                else
                    createExploiter(Random.shuffle(s).head)
                */
                val value = s.size * 0.2    
                if (value < preference) {
                    //println(s"Creating Explorer because ${s.size} * 0.2 = $value is less than ${preference}.")
                    createExplorer()
                } else {
                    val prospect = Random.shuffle(s).head
                    //println(s"Creating Exploiter because ${s.size} * 0.2 = $value is greater than $preference for point $prospect")
                    createExploiter(prospect)
                }

            case _ =>
                //println("Creating Explorer because no prospects are available.")
                createExplorer()
        })
        
        Await.result(state, 3.seconds)
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
    val delayPerProspect = 300L
}

case class ExplorerState(
    startLocation: (BigDecimal, BigDecimal),
    kernelFn: Worker.KernelFn,
    preference: BigDecimal
    , dispatcher: ActorRef[Dispatcher.Command]) extends WorkerState {

    /**
      * The memory of points considered promising enough to be sent to the dispatcher as prospects. Other approaches to consider include submitting a promising location after the first high value after the first low value, or refining the search pattern when a low value is discovered, and/or stopping after finding n points lower than threshold.
      */
    var memory: Set[Point] = Set.empty

    override def apply()(using ref: ActorRef[DataPointActor.Create[Sample]], context: ActorContext[?]): WorkerState = {
        implicit val scheduler: Scheduler = context.system.scheduler
        var (x, y) = startLocation
        
        // Explore a certain number of points.
        for (i <- 0 until ExplorerState.numPointsToExplore) {
            val result = kernelFn(x, y)
            if (result < ExplorerState.threshold) {
                //println(s"Worker ${context.self.path.name} found a promising point at (${x}, ${y}) with value ${result}.")
                memory = memory + ((x, y))
            }
            val res = DataPoint((x, y, result), Thread.currentThread().getName(), DataPoint.Phase.Explorer)

            //dispatcher ! Dispatcher.AddPoint(res)
            def getNewPoint(): Point = {
                    
                val angle = Random.between(0.0, 2 * Math.PI)
                x = x + ExplorerState.explorationRadius * Math.cos(angle)
                y = y + ExplorerState.explorationRadius * Math.sin(angle)
                (x, y)
            }
            var newPoint = (BigDecimal(-1.0), BigDecimal(-1.0))
            while
                newPoint = getNewPoint()
                (newPoint._1.toDouble <= 0.0) || (newPoint._1.toDouble >= 1.0) || (newPoint._2.toDouble < 0.0) || (newPoint._2.toDouble > 1.0)
            do ()
            //println(s"Using new point (${newPoint._1}, ${newPoint._2}) for exploration.")
            x = newPoint._1
            y = newPoint._2
        }

        // TODO: This will break if the explorer goes over the edge to the other side of the space.
        if (memory.nonEmpty) {
            memory.fold[Point]((BigDecimal(0.0), BigDecimal(0.0)))((acc, sample) => (acc._1 + sample._1, acc._2 + sample._2)) match {
                case (sumX, sumY) =>
                    val numSamples = memory.size
                    val avgX = sumX / numSamples
                    val avgY = sumY / numSamples
                    // TODO: Refactor out the calculation of the delay to make clear this could be non-linear
                    //println(s"Worker ${context.self.path.name} sending prospect (${avgX}, ${avgY}) to dispatcher with delay of ${ExplorerState.delayPerProspect * numSamples} ms because it found ${memory.size} promising points.")
                    dispatcher ! Dispatcher.AddProspect((avgX, avgY), ExplorerState.delayPerProspect * numSamples)
            }
        }

        // TODO: If this value of this is low, send to the dispatcher as a prospect. Weight the prospect according to how many zeroes this explorer has seen nearby.
        chooseState(
            () => this.copy(startLocation = (BigDecimal(Random.nextDouble()), BigDecimal(Random.nextDouble()))),
            (prospect) => ExploiterState(prospect, preference, kernelFn, dispatcher)
        )
    }
}
case class ExploiterState(
    prospect: Point
    , preference: BigDecimal
    , kernelFn: Worker.KernelFn
    , dispatcher: ActorRef[Dispatcher.Command]) extends WorkerState {
    override def apply()(using actor: ActorRef[DataPointActor.Create[Sample]], context: ActorContext[?]): WorkerState = {
        implicit val scheduler: Scheduler = context.system.scheduler
        
        var (rootX, rootY) = prospect
        rootX = rootX + Random.between(-0.01, 0.01)
        rootY = rootY + Random.between(-0.01, 0.01)

        for (ix <- -5 to 5; iy <- -5 to 5) {
            val x = rootX + BigDecimal(ix) * 0.001
            val y = rootY + BigDecimal(iy) * 0.001
            val result = kernelFn(x, y)
            val res = DataPoint((x, y, result), Thread.currentThread().getName(), DataPoint.Phase.Exploiter)
            //dispatcher ! Dispatcher.AddPoint(res)
        }

        chooseState(
            () => ExplorerState((BigDecimal(Random.nextDouble()), BigDecimal(Random.nextDouble())), kernelFn, preference, dispatcher),
            (prospect) => this.copy(prospect = prospect)
        )
    }
}