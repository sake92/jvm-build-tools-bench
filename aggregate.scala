//> using file bench.scala

package bench

import mainargs.{main, arg, ParserForClass}
import java.time.{ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter

@main
case class Aggregate(
  @arg(name = "results-dir", doc = "Directory with <repo>/<tool>-<scenario>.json files")
  resultsDir: String,
  @arg(name = "output-dir", doc = "Where to write aggregated outputs")
  outputDir: String = "aggregated",
  @arg(name = "benchmarks-yaml", doc = "Path to benchmarks.yaml to discover valid benchmark types")
  benchmarksYaml: String = "benchmarks.yaml"
)

object Aggregate:
  def main(args: Array[String]): Unit =
    val config = ParserForClass[Aggregate].constructEither(args.toSeq) match
      case Left(errMsg) =>
        System.err.println(errMsg)
        sys.exit(1)
      case Right(c) => c

    val resultsPath = os.Path(config.resultsDir, os.pwd)
    val outputPath = os.Path(config.outputDir, os.pwd)

    val benchmarksYamlPath = os.Path(config.benchmarksYaml, os.pwd)
    if !os.exists(benchmarksYamlPath) then
      System.err.println(s"Error: benchmarks.yaml not found at $benchmarksYamlPath")
      sys.exit(1)
    val benchConfig = Config.load(benchmarksYamlPath)
    val validTypes = benchConfig.hyperfine.benchmark_types.keySet
    println(s"Valid benchmark types: ${validTypes.toSeq.sorted.mkString(", ")}")

    if !os.exists(resultsPath) then
      System.err.println(s"Error: results directory not found: $resultsPath")
      sys.exit(1)

    println(s"Scanning $resultsPath for benchmark results...")
    val allData = loadResults(resultsPath, validTypes)
    if allData.isEmpty then
      println("No benchmark results found.")
      sys.exit(1)

    for (repo, scenarios) <- allData.toSeq.sortBy(_._1) do
      println(s"\n=== $repo ===")
      for (scenario, entries) <- scenarios do
        val scenarioOut = outputPath / repo / scenario
        os.makeDir.all(scenarioOut)

        writeSummaryJson(repo, scenario, entries, scenarioOut)
        writeSummaryMd(repo, scenario, entries, scenarioOut)
        asciiBarChart(repo, scenario, entries)

    os.makeDir.all(outputPath)
    writeIndexHtml(allData, outputPath)
    println("\nDone.")

  // ── Data loading ──────────────────────────────────────────────────────────

  private def loadResults(resultsDir: os.Path, validTypes: Set[String]): Map[String, Map[String, List[BenchmarkEntry]]] =
    import scala.collection.mutable

    val byRepo = mutable.Map.empty[String, mutable.Map[String, mutable.Map[String, BenchmarkEntry]]]

    for path <- os.walk(resultsDir) if path.ext == "json" && !path.last.startsWith(".") do
      val repo = if path / os.up == resultsDir then resultsDir.last else (path / os.up).last

      val stem = path.last.stripSuffix(".json")
      parseResultStem(stem, validTypes) match
        case Some((tool, scenario)) =>
          // Validate tool color knowledge
          try ToolColors.get(tool)
          catch
            case e: IllegalArgumentException =>
              System.err.println(s"Error: ${e.getMessage}")
              sys.exit(1)

          val parsed = tryParseHyperfine(path)
          parsed.foreach { entry =>
            val e = entry.copy(tool = tool)
            val repoMap = byRepo.getOrElseUpdate(repo, mutable.Map.empty)
            val scenarioMap = repoMap.getOrElseUpdate(scenario, mutable.Map.empty)
            scenarioMap(tool) = e
          }
        case None =>
          System.err.println(s"  Skipping $path (unknown scenario suffix)")

    // Convert to immutable, sorted by mean (fastest first)
    byRepo.view.mapValues { scenarios =>
      scenarios.view.mapValues { entries =>
        entries.values.toList.sortBy(_.mean)
      }.toMap
    }.toMap

  private def tryParseHyperfine(path: os.Path): Option[BenchmarkEntry] =
    try
      val content = os.read(path)
      val data = upickle.default.read[Map[String, ujson.Value]](content)
      val results = data.getOrElse("results", ujson.Arr()).arr
      if results.isEmpty then
        System.err.println(s"  Warning: no results in $path")
        None
      else
        val r = results.head.obj
        Some(BenchmarkEntry(
          tool = "",
          mean = r.getOrElse("mean", ujson.Num(0.0)).num,
          stddev = r.getOrElse("stddev", ujson.Num(0.0)).num,
          min = r.getOrElse("min", ujson.Num(0.0)).num,
          max = r.getOrElse("max", ujson.Num(0.0)).num,
          median = r.getOrElse("median", r.getOrElse("mean", ujson.Num(0.0))).num
        ))
    catch
      case e: Exception =>
        System.err.println(s"  Warning: could not read $path: ${e.getMessage}")
        None

  // ── JSON summary ──────────────────────────────────────────────────────────

  private def writeSummaryJson(repo: String, scenario: String, entries: List[BenchmarkEntry], outputDir: os.Path): Unit =
    val out = outputDir / "summary.json"
    val json = ujson.Obj(
      "repo" -> ujson.Str(repo),
      "scenario" -> ujson.Str(scenario),
      "entries" -> ujson.Arr(entries.map { e =>
        ujson.Obj(
          "tool" -> ujson.Str(e.tool),
          "mean" -> ujson.Num(e.mean),
          "stddev" -> ujson.Num(e.stddev),
          "min" -> ujson.Num(e.min),
          "max" -> ujson.Num(e.max),
          "median" -> ujson.Num(e.median)
        )
      }: _*)
    )
    os.write.over(out, upickle.default.write(json, indent = 2))
    println(s"  Wrote $out")

  // ── Markdown summary ─────────────────────────────────────────────────────

  private def writeSummaryMd(repo: String, scenario: String, entries: List[BenchmarkEntry], outputDir: os.Path): Unit =
    val title = scenario.replace("-", " ").split(" ").map(_.capitalize).mkString(" ")
    val sb = new StringBuilder
    sb.append(s"# JVM Build Tools Benchmark — $repo — $title\n\n")
    sb.append("| Tool | Mean | Stddev | Min | Max |\n")
    sb.append("|------|-----:|-------:|----:|----:|\n")
    for e <- entries do
      sb.append(s"| ${e.tool} | ${fmtMs(e.mean)} | ± ${fmtMs(e.stddev)} | ${fmtMs(e.min)} | ${fmtMs(e.max)} |\n")
    sb.append("\n")
    val out = outputDir / "summary.md"
    os.write.over(out, sb.toString)
    println(s"  Wrote $out")

  // ── ASCII bar chart ───────────────────────────────────────────────────────

  private val BarWidth = 40
  private val BarChar  = "█"

  private def asciiBarChart(repo: String, scenario: String, entries: List[BenchmarkEntry]): Unit =
    if entries.isEmpty then return

    val maxMean = entries.map(_.mean).max
    val maxToolLen = entries.map(_.tool.length).max

    println(s"\n  [$repo] $scenario (mean time, lower is better)")
    for e <- entries do
      val barLen = if maxMean > 0 then Math.round((e.mean / maxMean) * BarWidth).toInt else 0
      val bar = BarChar * barLen
      val label = e.tool.padTo(maxToolLen, ' ')
      val meanMs = e.mean * 1000
      val stddevMs = e.stddev * 1000
      val barPadded = bar.padTo(BarWidth, ' ')
      println(f"    $label  $barPadded  $meanMs%8.1f ms ± $stddevMs%.1f")

  // ── HTML index page with Chart.js ─────────────────────────────────────────

  private def writeIndexHtml(allData: Map[String, Map[String, List[BenchmarkEntry]]], outputDir: os.Path): Unit =
    val timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
    )

    // Tool colors as a JS object literal
    val toolColorsJs = ToolColors.all.map { case (tool, color) =>
      s""""$tool":"$color""""
    }.mkString("{", ",", "}")

    val sections = new StringBuilder
    var first = true
    for (repo, scenarios) <- allData.toSeq.sortBy(_._1) do
      val openAttr = if first then " open" else ""
      first = false

      val charts = new StringBuilder
      for (scenario, entries) <- scenarios.toSeq.sortBy(_._1) do
        val scenarioTitle = scenario.replace("-", " ").split(" ").map(_.capitalize).mkString(" ")
        val chartHeight = Math.max(150, entries.size * 35 + 90)
        // Build a clean JSON array of entries
        val entriesJson = entries.map { e =>
          s"""{"tool":"${e.tool}","mean":${e.mean},"stddev":${e.stddev},"min":${e.min},"max":${e.max},"median":${e.median}}"""
        }.mkString("[", ",", "]")
        val chartJson = s"""{"title":"$scenarioTitle","repo":"$repo","scenario":"$scenario","entries":$entriesJson}"""
        charts.append(
          s"""      <figure>
             |        <div class="chart-wrapper" style="height: ${chartHeight}px">
             |          <canvas></canvas>
             |        </div>
             |        <script type="application/json">$chartJson</script>
             |        <figcaption>$scenarioTitle
             |          (<a href="$repo/$scenario/summary.json">json</a>,
             |           <a href="$repo/$scenario/summary.md">md</a>)
             |        </figcaption>
             |      </figure>
             |""".stripMargin
        )

      sections.append(
        s"""  <details$openAttr>
           |    <summary>$repo</summary>
           |    <div class="repo-body">
           |      <div class="charts">
           |$charts
           |      </div>
           |    </div>
           |  </details>
           |""".stripMargin
      )

    val html =
      s"""<!DOCTYPE html>
         |<html lang="en">
         |<head>
         |  <meta charset="UTF-8">
         |  <meta name="viewport" content="width=device-width, initial-scale=1.0">
         |  <title>JVM Build Tools Benchmark</title>
         |  <style>
         |  body { font-family: system-ui, sans-serif; max-width: 960px; margin: 2rem auto; padding: 0 1rem; color: #222; }
         |  h1 { font-size: 1.6rem; }
         |  details { border: 1px solid #ddd; border-radius: 6px; margin: 1rem 0; }
         |  summary { cursor: pointer; padding: 0.75rem 1rem; font-size: 1.1rem; font-weight: 600;
         |            background: #f5f5f5; border-radius: 6px; list-style: none; }
         |  summary::-webkit-details-marker { display: none; }
         |  summary::before { content: "\\25B6 "; font-size: 0.8em; color: #666; }
         |  details[open] summary::before { content: "\\25BC "; }
         |  .repo-body { padding: 1rem; }
         |  .charts { display: flex; flex-wrap: wrap; gap: 1rem; }
         |  figure { margin: 0; flex: 1 1 400px; }
         |  .chart-wrapper { width: 100%; position: relative; }
         |  figcaption { font-size: 0.85rem; color: #555; margin-top: 0.3rem; text-align: center; }
         |  .timestamp { font-size: 0.8rem; color: #999; }
         |  </style>
         |</head>
         |<body>
         |  <h1>JVM Build Tools Benchmark</h1>
         |  <p>Compilation time comparison across build tools on real-world repos.
         |     Each chart shows mean time (lower is better).</p>
         |  <p><a href="https://github.com/sake92/jvm-build-tools-bench">View on GitHub</a></p>
         |  <p class="timestamp">Generated $timestamp</p>
         |
         |$sections
         |
         |  <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
         |  <script>
         |    const toolColors = $toolColorsJs;
         |    document.querySelectorAll('.chart-wrapper canvas').forEach(canvas => {
         |      const figure = canvas.closest('figure');
         |      const dataEl = figure && figure.querySelector('script[type="application/json"]');
         |      if (!dataEl) return;
         |      const data = JSON.parse(dataEl.textContent);
         |      const entries = data.entries;
         |      new Chart(canvas, {
         |        type: 'bar',
         |        data: {
         |          labels: entries.map(e => e.tool),
         |          datasets: [{
         |            data: entries.map(e => e.mean * 1000),
         |            backgroundColor: entries.map(e => toolColors[e.tool] || '#999'),
         |            borderWidth: 0,
         |            borderRadius: 3
         |          }]
         |        },
         |        options: {
         |          indexAxis: 'y',
         |          responsive: true,
         |          maintainAspectRatio: false,
         |          plugins: {
         |            legend: { display: false },
         |            tooltip: {
         |              callbacks: {
         |                label: function(ctx) {
         |                  var e = entries[ctx.dataIndex];
         |                  return [
         |                    (e.mean * 1000).toFixed(1) + ' ms',
         |                    '± ' + (e.stddev * 1000).toFixed(1) + ' ms',
         |                    'min ' + (e.min * 1000).toFixed(1) + ' / max ' + (e.max * 1000).toFixed(1)
         |                  ];
         |                }
         |              }
         |            }
         |          },
         |          scales: {
         |            x: {
         |              title: { display: true, text: 'Mean time (ms) — lower is better' },
         |              beginAtZero: true,
         |              ticks: { callback: function(v) { return v.toLocaleString(); } }
         |            },
         |            y: {
         |              grid: { display: false }
         |            }
         |          }
         |        }
         |      });
         |    });
         |  </script>
         |</body>
         |</html>
         |""".stripMargin

    val out = outputDir / "index.html"
    os.write.over(out, html)
    println(s"  Wrote $out")
