package eusocialcooperation.scheduler

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DemoSpec extends AnyFunSuite with Matchers {

  test("parseRunsParam defaults to 1 when omitted") {
    Demo.parseRunsParam(Map.empty) shouldEqual 1
  }

  test("parseRunsParam parses valid integer value") {
    Demo.parseRunsParam(Map("runs" -> "3")) shouldEqual 3
  }

  test("parseRunsParam rejects non-integer value") {
    val exception = intercept[IllegalArgumentException] {
      Demo.parseRunsParam(Map("runs" -> "abc"))
    }
    exception.getMessage should include("--runs must be an integer")
  }

  test("parseRunsParam rejects values below 1") {
    val exception = intercept[IllegalArgumentException] {
      Demo.parseRunsParam(Map("runs" -> "0"))
    }
    exception.getMessage should include("--runs must be at least 1")
  }

  test("runOutputPath uses root experiment path for single run") {
    Demo.runOutputPath("experiments/simple/", 1, 1) shouldEqual "experiments/simple/"
  }

  test("runOutputPath uses zero-padded subfolder for multi-run") {
    Demo.runOutputPath("experiments/simple/", 2, 3) shouldEqual "experiments/simple/run_002/"
  }

  test("effectiveHeadless keeps requested value for single run") {
    Demo.effectiveHeadless(requestedHeadless = false, runs = 1) shouldEqual false
    Demo.effectiveHeadless(requestedHeadless = true, runs = 1) shouldEqual true
  }

  test("effectiveHeadless forces headless for multi-run") {
    Demo.effectiveHeadless(requestedHeadless = false, runs = 2) shouldEqual true
  }
}
