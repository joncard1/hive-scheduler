package eusocialcooperation.scheduler

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import java.io.File
import eusocialcooperation.scheduler.Demo.CommandLineParams

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
    val params = CommandLineParams(Some("experiments/simple/"), None, 1, headless = true, None, None)
    Demo.runOutputPath(params, 1) `shouldEqual` "experiments/simple/"
  }

  test("runOutputPath uses zero-padded subfolder for multi-run") {
    val params = CommandLineParams(Some("experiments/simple/"), None, 3, headless = true, None, None)
    Demo.runOutputPath(params, 2) `shouldEqual` "experiments/simple/run_002/"
  }

  test("runOutputPath uses specified output path when provided") {
    val params = CommandLineParams(Some("experiments/simple/"), None, 1, headless = true, None, Some("custom/output/"))
    Demo.runOutputPath(params, 1) `shouldEqual` "custom/output/"
  }

  test("runOutputPath uses specified output path when provided for multi-run") {
    val params = CommandLineParams(Some("experiments/simple/"), None, 3, headless = true, None, Some("custom/output/"))
    Demo.runOutputPath(params, 1) `shouldEqual` "custom/output/run_001/"
  }

  test("runOutputPath uses specified output path when provided with experimentsPath") {
    val params = CommandLineParams(Some("experiments/simple/"), Some("experiments/"), 1, headless = true, None, Some("custom/output/"))
    Demo.runOutputPath(params, 1) `shouldEqual` "custom/output/simple/"
  }

  test("runOutputPath uses specified output path when provided for multi-run with experimentsPath") {
    val params = CommandLineParams(Some("experiments/simple/"), Some("experiments/"), 3, headless = true, None, Some("custom/output/"))
    Demo.runOutputPath(params, 1) `shouldEqual` "custom/output/simple/run_001/"
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
    val params = Demo.parseCommandLineParams(Array("--experimentsPath=testconf/"))
    params.experimentPath should not be `defined`
  }

  test("parseCommandLineParams rejects experimentsPath with config, logs, and the parent folder only") {
    val testHarnessFolder = "target/testharness"
    val logsFolder = new File(s"${testHarnessFolder}/logs")
    val configFolder = new File(s"${testHarnessFolder}/config")
    val parentFolder = new File(s"${testHarnessFolder}/parent")
    logsFolder.mkdirs()
    configFolder.mkdirs()
    parentFolder.mkdirs()
    try {
      val exception = intercept[IllegalArgumentException] {
        Demo.parseCommandLineParams(Array(s"--experimentsPath=${testHarnessFolder}/", s"--parent=${testHarnessFolder}/parent/"))
      }
      exception.getMessage should include(s"Experiments path '${testHarnessFolder}/' must contain at least one subdirectory representing an experiment.")
    } finally {
      logsFolder.delete()
      configFolder.delete()
      parentFolder.delete()
      new File(testHarnessFolder).delete()
    }
  }

  test("parseCommandLineParams acceptsexperimentsPath with config, logs, and the parent folder and an experiment folder.") {
    val testHarnessFolder = "target/testharness"
    val logsFolder = new File(s"${testHarnessFolder}/logs")
    val configFolder = new File(s"${testHarnessFolder}/config")
    val parentFolder = new File(s"${testHarnessFolder}/parent")
    val experimentFolder = new File(s"${testHarnessFolder}/experiment1")
    logsFolder.mkdirs()
    configFolder.mkdirs()
    parentFolder.mkdirs()
    experimentFolder.mkdirs()
    try {
      val commandLineParams = Demo.parseCommandLineParams(Array(s"--experimentsPath=${testHarnessFolder}/", s"--parent=${testHarnessFolder}/parent/"))
      commandLineParams.experimentsPath shouldEqual Some(s"${testHarnessFolder}/")
    } finally {
      logsFolder.delete()
      configFolder.delete()
      parentFolder.delete()
      experimentFolder.delete()
      new File(testHarnessFolder).delete()
    }
  }

  test("parseCommandLineParams requires experimentPath when --experimentsPath is not set") {
    val exception = intercept[IllegalArgumentException] {
      Demo.parseCommandLineParams(Array.empty)
    }
    exception.getMessage should include("Experiment path is required")
  }

  test("parseCommandLineParams defaults to --headless=true when --experimentsPath is set") {
    val params = Demo.parseCommandLineParams(Array("--experimentsPath=testconf/"))
    params.headless shouldEqual true
  }

  test("parseCommandLineParams preserves trailing '/' in --experimentsPath") {
    val params = Demo.parseCommandLineParams(Array("--experimentsPath=testconf/"))
    params.experimentsPath shouldEqual Some("testconf/")
  }

  test("parseCommandLineParams adds trailing '/' to --experimentsPath") {
    val params = Demo.parseCommandLineParams(Array("--experimentsPath=testconf"))
    params.experimentsPath shouldEqual Some("testconf/")
  }

  // TODO: parseCommandLineParams should reject --experimentsPath if there are no folders in other than /config (which is not required)

  // ── CommandLineParams case class ────────────────────────────────────────────

  test("CommandLineParams stores all fields correctly") {
    val params = Demo.CommandLineParams(Some("testconf/"), None, 2, true, Some("testconf/"), None)
    params.experimentPath.value `shouldBe` ("testconf/")
    params.runs shouldEqual 2
    params.headless shouldEqual true
    params.parentPath shouldEqual Some("testconf/")
  }

  test("CommandLineParams supports structural equality") {
    val p1 = Demo.CommandLineParams(Some("testconf/"), None, 1, false, None, None)
    val p2 = Demo.CommandLineParams(Some("testconf/"), None, 1, false, None, None)
    p1 shouldEqual p2
  }

  // ── loadConfig ──────────────────────────────────────────────────────────────

  test("loadConfig loads configuration from testconf/") {
    val params = Demo.CommandLineParams(Some("testconf/"), None, 1, headless = true, None, None)
    val config = Demo.loadConfig(params)
    config should not be null
    config.hasPath("eusocialcooperation.scheduler.duration") shouldEqual true
  }

  test("loadConfig reads duration from testconf/") {
    val params = Demo.CommandLineParams(Some("testconf/"), None, 1, headless = true, None, None)
    val config = Demo.loadConfig(params)
    config.getDuration("eusocialcooperation.scheduler.duration").toMillis shouldEqual 10000L
  }

  test("loadConfig loads the default configuration from classpath") {
    val params = Demo.CommandLineParams(Some("testconf/"), None, 1, headless = true, None, None)
    val config = Demo.loadConfig(params)
    config.hasPath("eusocialcooperation.scheduler.testkey") shouldEqual true
    config.getString("eusocialcooperation.scheduler.testkey") shouldEqual "testvalue"
  }
}
