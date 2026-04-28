package eusocialcooperation.scheduler

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
  * Convenience trait to ensure every class has access to the correct configured logger.
  * 
  * The logging strategy is direct logging output that is relevant to tracing and verifying the correctness of the algorithm to a file, determined at runtime, that becomes part of the experiment output. This is handled by MDC. The logging levels are expected to follow the standards:
  * error: messages indicating a failure that should be investigated and likely resulted in the failure of some part of the system.
  * warn: messages indicating a failure has happened that is recoverable and the system is stopping the propagation of the error message, but could potentially warrant investigation and invalidate the findings of the experiment.
  * info: messages useful for analyzing the behavior of the algorithm, representing decisions made by the algorithm that are potentially useful. The "results" (such as they are in this application) are not affected by the output and these messages can be suppressed, but they are activated by the default configuration.
  * debug: messages useful to the developer reporting values useful for debugging the application, but not for users interested in the data.
  * trace: messages useful to the developer reporting the path of the execution and often generates mountains of redundant data, so it is likely best left deactivated without a clear purpose.
  */
trait LoggingComponent {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)
}
