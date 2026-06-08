package eusocialcooperation.scheduler.dispatcher

import java.util.concurrent.atomic.AtomicReference
import eusocialcooperation.scheduler.datapoint.DataPoint
import eusocialcooperation.scheduler.Point
import eusocialcooperation.scheduler.Sample

class PekkoDispatcherSpec extends DispatcherSpec {
   override def makeDispatcher(workerFactory: Dispatcher.WorkerFactory = noOpWorkerFactory) = {
    val pointsMemory    = new AtomicReference[Set[DataPoint[Sample]]](Set.empty)
    val prospectsMemory = new AtomicReference[Set[DataPoint[Point]]](Set.empty)
    testKit.spawn(PekkoDispatcher(pointsMemory, prospectsMemory, workerFactory))
  }
}
