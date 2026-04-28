//> using scala 3.3
//> using dep org.virtuslab::scala-yaml:0.3.1
//> using dep com.lihaoyi::os-lib:0.11.9-M7
//> using dep com.lihaoyi::mainargs:0.7.8
//> using dep com.lihaoyi::upickle:4.4.3
//> using dep org.jfree:jfreechart:1.5.6
//> using dep org.jfree:jfreesvg:3.4.4

package bench

import org.virtuslab.yaml.*
import java.io.IOException
import scala.io.Source
import scala.util.{Try, Using}

// ── Configuration model ────────────────────────────────────────────────────

case class BenchmarkTypeConfig(
  warmup: Int,
  runs: Int
) derives YamlDecoder

case class HyperfineConfig(
  benchmark_types: Map[String, BenchmarkTypeConfig]
) derives YamlDecoder

case class BuildToolDef(
  install: String,
  shutdown: Option[String] = None
) derives YamlDecoder

case class ToolBenchmark(
  command: String,
  touch_files: List[String] = Nil
) derives YamlDecoder

case class RepoDef(
  name: String,
  url: String,
  ref: String,
  setup: Option[String] = None
) derives YamlDecoder

case class ToolConfig(
  build_tool_name: String,
  repo: String,
  setup: Option[String] = None,
  benchmarks: Map[String, ToolBenchmark]
) derives YamlDecoder

case class BenchmarkConfig(
  hyperfine: HyperfineConfig,
  build_tools: Map[String, BuildToolDef],
  repos: List[RepoDef],
  tools: List[ToolConfig]
) derives YamlDecoder:
  def findTool(benchmarkId: String): Option[ToolConfig] =
    tools.find(t => s"${t.repo}-${t.build_tool_name}" == benchmarkId)

  def findRepo(name: String): Option[RepoDef] =
    repos.find(_.name == name)

  def findBuildTool(name: String): Option[BuildToolDef] =
    build_tools.get(name)

// ── Result types ────────────────────────────────────────────────────────────

case class HyperfineResult(
  mean: Double,
  stddev: Double,
  min: Double,
  max: Double,
  median: Double
)

case class BenchmarkEntry(
  tool: String,
  mean: Double,
  stddev: Double,
  min: Double,
  max: Double,
  median: Double
)

// ── Tool colors ─────────────────────────────────────────────────────────────

object ToolColors:
  private val colors: Map[String, String] = Map(
    "maven" -> "#1f77b4",
    "mill"  -> "#666666",
    "deder" -> "#2ca02c",
    "bleep" -> "#ff6b35",
    "sbt"   -> "#01191F",
    "sbt2"  -> "#d62728"
  )

  def get(tool: String): String = colors.getOrElse(tool,
    throw new IllegalArgumentException(
      s"Unknown build tool '$tool' has no configured chart color"
    )
  )

// ── Config loading ──────────────────────────────────────────────────────────

object Config:
  def load(path: os.Path): BenchmarkConfig =
    val yamlContent = os.read(path)
    yamlContent.as[BenchmarkConfig] match
      case Right(config) => config
      case Left(error) =>
        throw new RuntimeException(s"Failed to parse $path: $error")

// ── Git operations ──────────────────────────────────────────────────────────

object Git:
  private def isSha(ref: String): Boolean =
    ref.matches("[0-9a-f]{40}")

  def cloneOrUpdate(repo: RepoDef, toolName: String, reposDir: os.Path): os.Path =
    val repoDir = reposDir / repo.name / toolName

    if os.exists(repoDir / ".git") then
      println(s">>> Updating ${repo.name} (${toolName}) to ${repo.ref}...")
      os.proc("git", "-C", repoDir.toString, "fetch", "--quiet", "origin").call()
      if isSha(repo.ref) then
        os.proc("git", "-C", repoDir.toString, "fetch", "--quiet", "origin", repo.ref)
          .call(check = false, stderr = os.Inherit)
        os.proc("git", "-C", repoDir.toString, "checkout", "--quiet", "--detach", repo.ref).call()
      else
        os.proc("git", "-C", repoDir.toString, "checkout", "--quiet", repo.ref).call()
        os.proc("git", "-C", repoDir.toString, "reset", "--quiet", "--hard", s"origin/${repo.ref}").call()
    else
      println(s">>> Cloning ${repo.name} (${toolName})...")
      os.makeDir.all(reposDir)
      if isSha(repo.ref) then
        os.proc("git", "clone", "--quiet", repo.url, repoDir.toString).call()
        os.proc("git", "-C", repoDir.toString, "checkout", "--quiet", "--detach", repo.ref).call()
      else
        os.proc("git", "clone", "--quiet", "--branch", repo.ref, "--depth", "1", repo.url, repoDir.toString).call()

    repoDir

// ── Hyperfine runner ────────────────────────────────────────────────────────

object Hyperfine:
  def run(
    command: String,
    warmup: Int,
    runs: Int,
    outputJson: os.Path,
    workDir: os.Path,
    timeoutMs: Long = 30 * 60 * 1000 // 30 minutes per scenario
  ): HyperfineResult =
    os.makeDir.all(outputJson / os.up)
    os.write.over(outputJson, "{}")

    println(s">>> Running hyperfine (warmup=$warmup, runs=$runs, timeout=${timeoutMs/60000}m): $command")
    
    def cleanupStub(): Unit =
      try os.remove(outputJson) catch case _ => ()

    val result = try
      os.proc(
        "hyperfine",
        "--shell", "bash",
        "--warmup", warmup.toString,
        "--runs", runs.toString,
        "--export-json", outputJson.toString,
        command
      ).call(cwd = workDir, check = false, stdout = os.Inherit, stderr = os.Inherit, timeout = timeoutMs)
    catch
      case e: Exception =>
        cleanupStub()
        throw new RuntimeException(s"hyperfine aborted: $command — ${e.getMessage}")

    if result.exitCode != 0 then
      cleanupStub()
      throw new RuntimeException(s"hyperfine failed with exit code ${result.exitCode}")

    val jsonContent = os.read(outputJson)
    val data = upickle.default.read[Map[String, ujson.Value]](jsonContent)
    val results = data("results").arr
    val r = results.head.obj

    HyperfineResult(
      mean = r("mean").num,
      stddev = r("stddev").num,
      min = r("min").num,
      max = r("max").num,
      median = r.get("median").map(_.num).getOrElse(r("mean").num)
    )

// ── File overlay ────────────────────────────────────────────────────────────

def overlayBuildFiles(scriptDir: os.Path, repoName: String, toolName: String, targetDir: os.Path): Unit =
  val overlayDir = scriptDir / "build-files" / repoName / toolName
  if os.exists(overlayDir) && os.isDir(overlayDir) then
    println(s">>> Overlaying build files from $overlayDir...")
    os.list(overlayDir).foreach { src =>
      val dest = targetDir / src.last
      os.copy(src, dest, mergeFolders = true, replaceExisting = true)
    }

// ── Result parsing ──────────────────────────────────────────────────────────

def parseResultStem(stem: String, validBenchmarkTypes: Set[String]): Option[(String, String)] =
  validBenchmarkTypes.toSeq
    .sortBy(-_.length)  // longest first to avoid partial matches
    .collectFirst {
      case suffix if stem.endsWith(s"-$suffix") && stem.stripSuffix(s"-$suffix").nonEmpty =>
        val tool = stem.stripSuffix(s"-$suffix")
        (tool, suffix)
    }

// ── Formatting helpers ──────────────────────────────────────────────────────

def fmtMs(seconds: Double): String = f"${seconds * 1000}%.1f ms"
