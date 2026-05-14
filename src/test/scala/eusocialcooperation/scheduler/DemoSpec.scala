package eusocialcooperation.scheduler

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues

class DemoSpec extends AnyFunSuite with Matchers with OptionValues {

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

  // TODO: runOutputPath should be --experimentsPath/<a folder> when --experimentsPath is set and --runs == 1
  // TODO: runOutputPath should be --experimentsPath/<a folder>/run_XXX/ when --experimentsPath is set and --runs > 1
  // TODO: runOutputPath should be settable.

  test("effectiveHeadless keeps requested value for single run") {
    Demo.effectiveHeadless(requestedHeadless = false, runs = 1, Some("value")) shouldEqual false
    Demo.effectiveHeadless(requestedHeadless = true, runs = 1, Some("value")) shouldEqual true
  }

  test("effectiveHeadless forces headless for multi-run") {
    Demo.effectiveHeadless(requestedHeadless = false, runs = 2, Some("value")) shouldEqual true
  }

  test("effectiveHeadless defaults to true when --experimentPath is not set (assumes experimentsPath was set)") {
    Demo.effectiveHeadless(requestedHeadless = false, runs = 1, None) shouldEqual true
  }

  test("Running in non-headless mode loads the correct FXML file") {
    Demo.fxmlFileName(headless = false) shouldBe Demo.mainLayoutFxmlFileName
  }

  test("Running in headless mode loads the correct FXML file") {
    Demo.fxmlFileName(headless = true) shouldBe Demo.headlessModeFxmlFileName
  }

  // ── parseCommandLineParams ──────────────────────────────────────────────────

  test("parseCommandLineParams appends trailing slash to bare experiment path") {
    Demo.parseCommandLineParams(Array("testconf")).experimentPath.value shouldEqual "testconf/"
  }

  test("parseCommandLineParams keeps existing trailing slash on experiment path") {
    Demo.parseCommandLineParams(Array("testconf/")).experimentPath.value shouldEqual "testconf/"
  }

  // TODO: This needs to be removed.
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

  test("parseCommandLineParams does not require experimentPath when --experimentsPath is set") {
    val params = Demo.parseCommandLineParams(Array("--experimentsPath=experiments"))
    params.experimentPath should not be `defined`
  }

  test("parseCommandLineParams requires experimentPath when --experimentsPath is not set") {
    val exception = intercept[IllegalArgumentException] {
      Demo.parseCommandLineParams(Array.empty)
    }
    exception.getMessage should include("Experiment path is required")
  }

  test("parseCommandLineParams defaults to --headless=true when --experimentsPath is set") {
    val params = Demo.parseCommandLineParams(Array("--experimentsPath=experiments/"))
    params.headless shouldEqual true
  }

  test("parseCommandLineParams preserves trailing '/' in --experimentsPath") {
    val params = Demo.parseCommandLineParams(Array("--experimentsPath=experiments/"))
    params.experimentsPath shouldEqual Some("experiments/")
  }

  test("parseCommandLineParams adds trailing '/' to --experimentsPath") {
    val params = Demo.parseCommandLineParams(Array("--experimentsPath=experiments"))
    params.experimentsPath shouldEqual Some("experiments/")
  }

  // TODO: parseCommandLineParams should reject --experimentsPath if there are no folders in other than /config (which is not required)

  // ── CommandLineParams case class ────────────────────────────────────────────

  test("CommandLineParams stores all fields correctly") {
    val params = Demo.CommandLineParams(Some("testconf/"), None, 2, true, Some("testconf/"))
    params.experimentPath.value `shouldBe` ("testconf/")
    params.runs shouldEqual 2
    params.headless shouldEqual true
    params.parentPath shouldEqual Some("testconf/")
  }

  test("CommandLineParams supports structural equality") {
    val p1 = Demo.CommandLineParams(Some("testconf/"), None, 1, false, None)
    val p2 = Demo.CommandLineParams(Some("testconf/"), None, 1, false, None)
    p1 shouldEqual p2
  }

  // ── loadConfig ──────────────────────────────────────────────────────────────

  test("loadConfig loads configuration from testconf/") {
    val params = Demo.CommandLineParams(Some("testconf/"), None, 1, headless = true, None)
    val config = Demo.loadConfig(params)
    config should not be null
    config.hasPath("duration") shouldEqual true
  }

  test("loadConfig reads duration from testconf/") {
    val params = Demo.CommandLineParams(Some("testconf/"), None, 1, headless = true, None)
    val config = Demo.loadConfig(params)
    config.getDuration("duration").toMillis shouldEqual 10000L
  }

  test("loadConfig loads the default configuration from classpath") {
    val params = Demo.CommandLineParams(Some("testconf/"), None, 1, headless = true, None)
    val config = Demo.loadConfig(params)
    config.hasPath("testkey") shouldEqual true
    config.getString("testkey") shouldEqual "testvalue"
  }
}
