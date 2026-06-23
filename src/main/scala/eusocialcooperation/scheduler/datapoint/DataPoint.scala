package eusocialcooperation.scheduler.datapoint

case class DataPointContext(phase: DataPoint.Phase, hostname: String, actorName: String)

/** 
 * My intent for this class is that, as the reporting needs of the
    * application evolves, it would not be necessary to make major structural
    * changes to the rest of the algorithm to keep up with them, slowing
    * development and confusing the human reader. Instead, the compiler should
    * be able to adapt to additions or subtractions from the list of implicit
    * parameters with only the occasional addition of a "given" in the code
    * that is easier to ignore than changes to the parameter list of a function.
    * This way, changes to the implementation that are only of use to the
    * reporting system, such as the use of a database or an actor to provide the
    * sequence number, will be as low-impact as possible, although it isn't
    * completely invisible.
    * 
  */
object DataPoint {

  /**
    * The type description of the unit operator for lifting a value to a DataValue.
    */
  type DataPointUnit[A] =
    (A) => (DataPointContext, Option[DataPoint[?]]) ?=> DataPoint[A]

  /** An enum to designate the phases in which a DataPoint can be generated.
    */
  enum Phase:
    case Explorer, Exploiter, ChooseState
}

/** This represents a monad that tracks the metadata containing the
  *
  * @param sequenceNumber
  *   The sequence number that this data point was recorded. I am interested in
  *   environmental conditions when the point was generated. To create one of
  *   these objects, use one of the "unit" functions provided by the DataPoint
  *   companion object. what order all of the points were created in across the
  *   different threads.
  * @param timestamp
  *   The time at which the data point was created.
  * @param actorName
  *   This is really the thread name, in practice, but it allows grouping by
  *   which worker generated the point.
  * @param phase
  *   The state the worker was in when the point was generated, explorer or
  *   exploiter.
  * @param value
  *   The value contained in the monad.
  * @param parent
  *   The value that led to the generation of the value. In general, the
  *   prospect point that the exploiter is working off of when it generated the
  *   sample point. This allows grouping by precedent to validate whether points
  *   with high prospects are properly being favored for exploitation.
  */
class DataPoint[A](
    val sequenceNumber: Long,
    val timestamp: Long,
    val hostName: String,
    val actorName: String,
    val phase: DataPoint.Phase,
    val value: A,
    val parent: Option[DataPoint[?]] = None
) /*extends LocalSerialization*/ {

  /**
    * The 'bind' operation for DataPoint.
    *
    * @param f
    *   A function to apply to the value within the DataPoint.
    * @return
    */
  def flatMap[B](f: A => DataPoint[B]): DataPoint[B] = {
    f(value)
  }

  /**
    * When the operation passed to the map function is applied to the value of
    * this object, the current object is made the parent of the new DataPoint
    * object.
    *
    * @param f
    * The operation to apply to the value of this object.
    * @param dpb
    * The 'unit' or 'lift' operator for the type that will result from applying f.
    * @param phase
    * The phase during which the map operation is taking place.
    * @param actorName
    * The string identifying the thread or actor during with the map operation is taking place.
    * @return
    */
  def map[B](f: A => B)(implicit
      dpb: DataPoint.DataPointUnit[B],
      context: DataPointContext
  ): DataPoint[B] = {
    given Option[DataPoint[?]] = Some(this)
    dpb(
      f(value)
    )
  }
}
