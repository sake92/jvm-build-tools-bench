//> using file bench.scala

package bench

import mainargs.{main, arg, ParserForClass}
import java.awt.Color
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
        writeSvgChart(repo, scenario, entries, scenarioOut)

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

  // ── SVG chart (JFreeChart + jfreesvg) ────────────────────────────────────

  private def writeSvgChart(repo: String, scenario: String, entries: List[BenchmarkEntry], outputDir: os.Path): Unit =
    try
      import org.jfree.chart.{ChartFactory, StandardChartTheme}
      import org.jfree.chart.plot.{CategoryPlot, PlotOrientation}
      import org.jfree.chart.renderer.category.BarRenderer
      import org.jfree.data.category.DefaultCategoryDataset

      // Build dataset — reversed so fastest is at top
      val reversed = entries.reverse
      val dataset = new DefaultCategoryDataset()
      for e <- reversed do
        dataset.addValue(e.mean * 1000, "Mean", e.tool)

      // Create chart
      val chart = ChartFactory.createBarChart(
        "",
        "",
        "Mean time (ms) — lower is better",
        dataset,
        PlotOrientation.HORIZONTAL,
        false,
        false,
        false
      )

      val title = s"$repo: ${scenario.replace("-", " ").split(" ").map(_.capitalize).mkString(" ")}"
      chart.setTitle(title)

      // Style
      val theme = StandardChartTheme.createJFreeTheme()
      theme.apply(chart)

      val plot = chart.getPlot.asInstanceOf[CategoryPlot]
      plot.setBackgroundPaint(java.awt.Color.WHITE)
      plot.setRangeGridlinePaint(java.awt.Color.LIGHT_GRAY)
      plot.setOutlineVisible(false)

      // Custom renderer with per-bar colors
      val renderer = new BarRenderer {
        override def getItemPaint(row: Int, column: Int): java.awt.Paint =
          val tool = reversed(column).tool
          try hexToColor(ToolColors.get(tool))
          catch case _: Exception => java.awt.Color.GRAY
      }
      renderer.setDrawBarOutline(false)
      plot.setRenderer(renderer)

      // Size
      val height = Math.max(150, entries.size * 40 + 100)
      val width = 800

      // Export to SVG via jfreesvg
      import org.jfree.graphics2d.svg.SVGGraphics2D
      val svgG2d = new SVGGraphics2D(width, height)
      val rect = new java.awt.Rectangle(0, 0, width, height)
      svgG2d.setClip(rect)
      chart.draw(svgG2d, rect)

      val svgText = svgG2d.getSVGElement
      val out = outputDir / "chart.svg"
      os.write.over(out, svgText)
      println(s"  Wrote $out")
    catch
      case e: Exception =>
        System.err.println(s"  SVG chart generation failed for $scenario: ${e.getMessage}")

  // ── HTML index page ───────────────────────────────────────────────────────

  private def writeIndexHtml(allData: Map[String, Map[String, List[BenchmarkEntry]]], outputDir: os.Path): Unit =
    val timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
    )

    val sections = new StringBuilder
    var first = true
    for (repo, scenarios) <- allData.toSeq.sortBy(_._1) do
      val openAttr = if first then " open" else ""
      first = false

      val charts = new StringBuilder
      for (scenario, _) <- scenarios.toSeq.sortBy(_._1) do
        val scenarioTitle = scenario.replace("-", " ").split(" ").map(_.capitalize).mkString(" ")
        charts.append(
          s"""      <figure>
             |        <img src="$repo/$scenario/chart.svg" alt="$scenario chart" loading="lazy">
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
         |  summary::before { content: "▶ "; font-size: 0.8em; color: #666; }
         |  details[open] summary::before { content: "▼ "; }
         |  .repo-body { padding: 1rem; }
         |  .charts { display: flex; flex-wrap: wrap; gap: 1rem; }
         |  figure { margin: 0; flex: 1 1 400px; }
         |  figure img { width: 100%; height: auto; border: 1px solid #eee; border-radius: 4px; }
         |  figcaption { font-size: 0.85rem; color: #555; margin-top: 0.3rem; text-align: center; }
         |  footer { margin-top: 2rem; font-size: 0.8rem; color: #999; }
         |  </style>
         |</head>
         |<body>
         |  <h1>JVM Build Tools Benchmark</h1>
         |  <p>Compilation time comparison across build tools on real-world repos.
         |     Each chart shows mean time (lower is better).</p>
         |
         |$sections
         |
         |  <footer>Generated $timestamp</footer>
         |</body>
         |</html>
         |""".stripMargin

    val out = outputDir / "index.html"
    os.write.over(out, html)
    println(s"  Wrote $out")

  // ── Color utility ─────────────────────────────────────────────────────────

  private def hexToColor(hex: String): Color =
    val h = hex.stripPrefix("#")
    new Color(Integer.parseInt(h, 16))
