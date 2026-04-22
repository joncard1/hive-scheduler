package eusocialcooperation.scheduler

import org.slf4j.Logger
import org.slf4j.LoggerFactory

trait LoggingComponent {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)
}
