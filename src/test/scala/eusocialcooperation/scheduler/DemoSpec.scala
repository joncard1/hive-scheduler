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

  test("Running in non-headless mode loads the correct FXML file") {
    Demo.fxmlFileName(headless = false) shouldBe Demo.mainLayoutFxmlFileName
  }

  test("Running in headless mode loads the correct FXML file") {
    Demo.fxmlFileName(headless = true) shouldBe Demo.headlessModeFxmlFileName
  }

  // ── parseCommandLineParams ──────────────────────────────────────────────────

  test("parseCommandLineParams defaults to testconf/ when no args are given") {
    Demo.parseCommandLineParams(Array.empty).experimentPath shouldEqual "testconf/"
  }

  test("parseCommandLineParams appends trailing slash to bare experiment path") {
    Demo.parseCommandLineParams(Array("testconf")).experimentPath shouldEqual "testconf/"
  }

  test("parseCommandLineParams keeps existing trailing slash on experiment path") {
    Demo.parseCommandLineParams(Array("testconf/")).experimentPath shouldEqual "testconf/"
  }

  test("parseCommandLineParams rejects an empty string as experiment path") {
    val exception = intercept[IllegalArgumentException] {
      Demo.parseCommandLineParams(Array(""))
    }
    exception.getMessage should include("non-empty")
  }

  test("parseCommandLineParams rejects a non-existent experiment path") {
    val exception = intercept[IllegalArgumentException] {
      Demo.parseCommandLineParams(Array("no_such_dir/"))
    }
    exception.getMessage should include("does not exist")
  }

  test("parseCommandLineParams parses --headless=true") {
    Demo.parseCommandLineParams(Array("testconf/", "--headless=true")).headless shouldEqual true
  }

  test("parseCommandLineParams parses --headless=false") {
    Demo.parseCommandLineParams(Array("testconf/", "--headless=false")).headless shouldEqual false
  }

  test("parseCommandLineParams treats bare --headless flag as true") {
    Demo.parseCommandLineParams(Array("testconf/", "--headless")).headless shouldEqual true
  }

  test("parseCommandLineParams parses --runs value") {
    Demo.parseCommandLineParams(Array("testconf/", "--runs=5")).runs shouldEqual 5
  }

  test("parseCommandLineParams defaults runs to 1") {
    Demo.parseCommandLineParams(Array("testconf/")).runs shouldEqual 1
  }

  test("parseCommandLineParams forces headless when --runs > 1") {
    Demo.parseCommandLineParams(Array("testconf/", "--runs=3")).headless shouldEqual true
  }

  test("parseCommandLineParams defaults parentPath to None") {
    Demo.parseCommandLineParams(Array("testconf/")).parentPath shouldEqual None
  }

  test("parseCommandLineParams rejects a non-existent parent path") {
    val exception = intercept[IllegalArgumentException] {
      Demo.parseCommandLineParams(Array("testconf/", "--parent=no_such_parent/"))
    }
    exception.getMessage should include("does not exist")
  }

  // ── CommandLineParams case class ────────────────────────────────────────────

  test("CommandLineParams stores all fields correctly") {
    val params = Demo.CommandLineParams("testconf/", 2, true, Some("testconf/"))
    params.experimentPath shouldEqual "testconf/"
    params.runs shouldEqual 2
    params.headless shouldEqual true
    params.parentPath shouldEqual Some("testconf/")
  }

  test("CommandLineParams supports structural equality") {
    val p1 = Demo.CommandLineParams("testconf/", 1, false, None)
    val p2 = Demo.CommandLineParams("testconf/", 1, false, None)
    p1 shouldEqual p2
  }

  // ── loadConfig ──────────────────────────────────────────────────────────────

  test("loadConfig loads configuration from testconf/") {
    val params = Demo.CommandLineParams("testconf/", 1, headless = true, None)
    val config = Demo.loadConfig(params)
    config should not be null
    config.hasPath("duration") shouldEqual true
  }

  test("loadConfig reads duration from testconf/") {
    val params = Demo.CommandLineParams("testconf/", 1, headless = true, None)
    val config = Demo.loadConfig(params)
    config.getDuration("duration").toMillis shouldEqual 10000L
  }
}
