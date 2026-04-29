//> using file bench.scala

package bench

import mainargs.{main, arg, Flag, ParserForClass}

@main
case class RunBench(
  @arg(name = "benchmark", doc = "Benchmark ID: <repo>-<build-tool>")
  benchmark: String = "",
  @arg(name = "results-dir", doc = "Where to write hyperfine JSON results")
  resultsDir: String = "results",
  @arg(name = "repos-dir", doc = "Where to clone target repos")
  reposDir: String = "tmp/repos",
  @arg(name = "list-benchmarks", doc = "Print all benchmark IDs as JSON array and exit")
  listBenchmarks: Flag = Flag(false),
  @arg(name = "install-cmd", doc = "Print and execute the install command for the given benchmark's build tool and exit")
  installCmd: Flag = Flag(false)
)

object RunBench:
  def main(args: Array[String]): Unit =
    val config = ParserForClass[RunBench].constructEither(args.toSeq) match
      case Left(errMsg) =>
        System.err.println(errMsg)
        sys.exit(1)
      case Right(c) => c

    val scriptDir: os.Path = os.pwd

    val benchYaml = scriptDir / "benchmarks.yaml"
    if !os.exists(benchYaml) then
      System.err.println(s"Error: benchmarks.yaml not found at $benchYaml")
      sys.exit(1)

    val benchConfig = Config.load(benchYaml)

    if config.listBenchmarks.value then
      val ids = benchConfig.tools.map(t => s"${t.repo}-${t.build_tool_name}").sorted
      val json = ujson.Arr(ids.map(ujson.Str(_)): _*)
      println(upickle.default.write(json))
      sys.exit(0)

    if config.benchmark.isEmpty then
      System.err.println("Usage: run_bench --benchmark <repo>-<build-tool> [--results-dir <dir>] [--repos-dir <dir>]")
      System.err.println("       run_bench --list-benchmarks")
      System.err.println("       run_bench --install-cmd --benchmark <repo>-<build-tool>")
      sys.exit(1)

    // Resolve tool build_tool_name first (needed for both install-cmd and normal run)
    val toolConfig = benchConfig.findTool(config.benchmark).getOrElse {
      System.err.println(s"Error: Benchmark '${config.benchmark}' not found in benchmarks.yaml")
      sys.exit(1)
    }

    if config.installCmd.value then
      val bt = benchConfig.findBuildTool(toolConfig.build_tool_name).getOrElse {
        System.err.println(s"Error: Build tool '${toolConfig.build_tool_name}' not found in benchmarks.yaml")
        sys.exit(1)
      }
      println(s">>> Installing ${toolConfig.build_tool_name}: ${bt.install}")
      os.proc("bash", "-c", bt.install).call(stdout = os.Inherit, stderr = os.Inherit)
      sys.exit(0)

    val repoDef = benchConfig.findRepo(toolConfig.repo).getOrElse {
      System.err.println(s"Error: Repo '${toolConfig.repo}' not found in benchmarks.yaml")
      sys.exit(1)
    }

    val buildToolDef = benchConfig.findBuildTool(toolConfig.build_tool_name).getOrElse {
      System.err.println(s"Error: Build tool '${toolConfig.build_tool_name}' not found in benchmarks.yaml")
      sys.exit(1)
    }

    val resultsDirPath = os.Path(config.resultsDir, os.pwd)
    val reposDirPath = os.Path(config.reposDir, os.pwd)

    println("=== JVM Build Tools Bench ===")
    println(s"Benchmark: ${config.benchmark}")
    println(s"Tool:     ${toolConfig.build_tool_name}")
    println(s"Repo:     ${toolConfig.repo} (${repoDef.url} @ ${repoDef.ref})")
    println(s"Results:  $resultsDirPath")
    println()

    // Phase tracking
    var failedPhases = List.empty[String]

    def runPhase(name: String)(body: => Unit): Unit =
      try
        body
      catch
        case e: Exception =>
          System.err.println(s"WARNING: '$name' failed — continuing with remaining phases...")
          System.err.println(s"  ${e.getMessage}")
          failedPhases = failedPhases :+ name

    // Clone/update repo (per repo+build_tool for isolation)
    val repoDir = Git.cloneOrUpdate(repoDef, toolConfig.build_tool_name, reposDirPath)

    // Overlay build files
    overlayBuildFiles(scriptDir, toolConfig.repo, toolConfig.build_tool_name, repoDir)

    // Repo-level setup
    repoDef.setup.foreach { cmd =>
      println(s">>> Repo setup: $cmd")
      os.proc("bash", "-c", cmd).call(cwd = repoDir, stdout = os.Inherit, stderr = os.Inherit)
    }

    // Tool-level setup
    toolConfig.setup.foreach { cmd =>
      println(s">>> Tool setup: $cmd")
      os.proc("bash", "-c", cmd).call(cwd = repoDir, stdout = os.Inherit, stderr = os.Inherit)
    }

    // Prepare results directory
    val repoResultsDir = resultsDirPath / toolConfig.repo
    os.makeDir.all(repoResultsDir)

    val hf = benchConfig.hyperfine

    val writtenFiles = List.newBuilder[os.Path]

    for (benchmarkType, bm) <- toolConfig.benchmarks.toSeq.sortBy(_._1) do
      val hfConfig = hf.benchmark_types.getOrElse(benchmarkType, {
        System.err.println(
          s"Error: Benchmark type '$benchmarkType' is not declared in hyperfine.benchmark_types. " +
          s"Available types: ${hf.benchmark_types.keys.toSeq.sorted.mkString(", ")}"
        )
        sys.exit(1)
      })

      // Smoke mode override: PRs use warmup=0, runs=2 for fast pipeline validation
      val (warmup, runs) = sys.env.get("BENCH_SMOKE") match
        case Some("true" | "1") =>
          println(s">>> Smoke mode: overriding $benchmarkType " +
            s"(warmup ${hfConfig.warmup}→0, runs ${hfConfig.runs}→2)")
          (0, 2)
        case _ =>
          (hfConfig.warmup, hfConfig.runs)

      runPhase(benchmarkType) {
        println()

        // Touch files (if any)
        bm.touch_files.foreach { f =>
          val p = repoDir / os.RelPath(f)
          if os.exists(p) then
            os.write.append(p, "")
          else
            System.err.println(s"  Warning: file not found: $p")
        }

        val outFile = repoResultsDir / s"${toolConfig.build_tool_name}-$benchmarkType.json"
        Hyperfine.run(bm.command, warmup, runs, outFile, repoDir)
        println(s"  Results: $outFile")
        writtenFiles += outFile
      }

    // Shutdown
    buildToolDef.shutdown.foreach { cmd =>
      println()
      println(s">>> Shutdown: $cmd")
      val r = os.proc("bash", "-c", cmd).call(cwd = repoDir, stdout = os.Inherit, stderr = os.Inherit, check = false)
      // shutdown failures are not fatal — ignore exit code
    }

    if failedPhases.nonEmpty then
      println()
      println(s"WARNING: The following phases failed: ${failedPhases.mkString(", ")}")

    println()
    println("=== Done ===")
    val files = writtenFiles.result()
    if files.nonEmpty then
      println("Results written to:")
      files.foreach(f => println(s"  $f"))

    if failedPhases.nonEmpty then sys.exit(1)
