package eusocialcooperation.scheduler.dispatcher

import java.util.concurrent.atomic.AtomicReference
import eusocialcooperation.scheduler.datapoint.DataPoint
import eusocialcooperation.scheduler.Point
import eusocialcooperation.scheduler.Sample

class PekkoDispatcherSpec extends DispatcherSpec {
   override def makeDispatcher(
    pointsMemory: AtomicReference[Set[DataPoint[Sample]]] = new AtomicReference[Set[DataPoint[Sample]]](Set.empty)
    , prospectsMemory: AtomicReference[Set[DataPoint[Point]]] = new AtomicReference[Set[DataPoint[Point]]](Set.empty)
    , workerFactory: Dispatcher.WorkerFactory = noOpWorkerFactory) = {
    testKit.spawn(PekkoDispatcher(pointsMemory, prospectsMemory, workerFactory))
  }
}
