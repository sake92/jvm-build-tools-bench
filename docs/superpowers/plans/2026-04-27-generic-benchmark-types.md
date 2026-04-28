# Generic Benchmark Types Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace 3 hardcoded benchmark types with an arbitrary map of named types, enabling new benchmark types to be added without changing Scala code.

**Architecture:** Refactor `HyperfineConfig` and `ToolConfig` from named fields to `Map[String, _]`. Replace the runner's 3 hardcoded phase blocks with a dynamic loop. Update the aggregator to discover valid benchmark type names from the YAML instead of a hardcoded alias map.

**Tech Stack:** Scala 3.3, scala-yaml, upickle, JFreeChart, os-lib, mainargs

**Files to modify:**
- `bench.scala` — shared data model + filename parsing
- `run_bench.scala` — benchmark runner
- `aggregate.scala` — results aggregator
- `benchmarks.yaml` — config data
- `.github/workflows/bench.yml` — CI pipeline
- `README.md` — documentation

---

### Task 1: Rewrite data model in bench.scala

**Files:** Modify `bench.scala`

- [ ] **Step 1: Rename HyperfinePhaseConfig to BenchmarkTypeConfig**

Replace the case class definition at lines 18-21:

```scala
// Before:
case class HyperfinePhaseConfig(
  warmup: Int,
  runs: Int
) derives YamlDecoder

// After:
case class BenchmarkTypeConfig(
  warmup: Int,
  runs: Int
) derives YamlDecoder
```

- [ ] **Step 2: Add ToolBenchmark case class**

Insert after `BenchmarkTypeConfig` (after `BuildToolDef`, before `RepoDef` or alongside where it fits):

```scala
case class ToolBenchmark(
  command: String,
  touch_files: List[String] = Nil
) derives YamlDecoder
```

Place it after `BuildToolDef` (around line 32) to keep config types grouped.

- [ ] **Step 3: Rewrite HyperfineConfig**

Replace lines 23-27:

```scala
// Before:
case class HyperfineConfig(
  clean_compile: HyperfinePhaseConfig,
  incremental_compile: HyperfinePhaseConfig,
  test_all: HyperfinePhaseConfig
) derives YamlDecoder

// After:
case class HyperfineConfig(
  benchmark_types: Map[String, BenchmarkTypeConfig]
) derives YamlDecoder
```

- [ ] **Step 4: Rewrite ToolConfig**

Replace lines 41-49:

```scala
// Before:
case class ToolConfig(
  build_tool_name: String,
  repo: String,
  setup: Option[String] = None,
  compile_clean: String,
  compile_incremental: String,
  incremental_files: List[String] = Nil,
  test_all: Option[String] = None
) derives YamlDecoder

// After:
case class ToolConfig(
  build_tool_name: String,
  repo: String,
  setup: Option[String] = None,
  benchmarks: Map[String, ToolBenchmark]
) derives YamlDecoder
```

- [ ] **Step 5: Remove scenarioAliases and rewrite parseResultStem**

Replace lines 199-214:

```scala
// Before:
val scenarioAliases: Map[String, String] = Map(
  "clean-compile"         -> "clean-compile",
  "incremental-compile"   -> "incremental-compile",
  "test-all"              -> "test-all",
  "clean"                 -> "clean-compile",
  "incremental"           -> "incremental-compile"
)

def parseResultStem(stem: String): Option[(String, String)] =
  scenarioAliases.toSeq
    .sortBy(-_._1.length)
    .collectFirst {
      case (suffix, scenario) if stem.endsWith(s"-$suffix") && stem.stripSuffix(s"-$suffix").nonEmpty =>
        val tool = stem.stripSuffix(s"-$suffix")
        (tool, scenario)
    }

// After:
def parseResultStem(stem: String, validBenchmarkTypes: Set[String]): Option[(String, String)] =
  validBenchmarkTypes.toSeq
    .sortBy(-_.length)  // longest first to avoid partial matches
    .collectFirst {
      case suffix if stem.endsWith(s"-$suffix") && stem.stripSuffix(s"-$suffix").nonEmpty =>
        val tool = stem.stripSuffix(s"-$suffix")
        (tool, suffix)
    }
```

- [ ] **Step 6: Verify bench.scala compiles**

Run: `scala-cli compile bench.scala`
Expected: Compilation success (may have unused import warnings, that's fine for now)

---

### Task 2: Rewrite runner in run_bench.scala

**Files:** Modify `run_bench.scala`

- [ ] **Step 1: Replace results file declarations and hardcoded blocks**

Replace lines 119-161 (from `val cleanCompileJson = ...` through the `test_all.foreach` block):

```scala
    // Prepare results directory
    val repoResultsDir = resultsDirPath / toolConfig.repo
    os.makeDir.all(repoResultsDir)

    val hf = benchConfig.hyperfine

    val writtenFiles = List.newBuilder[os.Path]

    for (benchmarkType, bm) <- toolConfig.benchmarks.toSeq.sortBy(_._1) do
      val hfConfig = hf.benchmark_types.getOrElse(
        benchmarkType,
        throw new RuntimeException(
          s"Benchmark type '$benchmarkType' is not declared in hyperfine.benchmark_types. " +
          s"Available types: ${hf.benchmark_types.keys.toSeq.sorted.mkString(", ")}"
        )
      )

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
        Hyperfine.run(bm.command, hfConfig.warmup, hfConfig.runs, outFile, repoDir)
        println(s"  Results: $outFile")
        writtenFiles += outFile
      }
```

- [ ] **Step 2: Replace final "Results written to:" output**

Replace lines 175-181 (the `=== Done ===` section):

```scala
    println()
    println("=== Done ===")
    val files = writtenFiles.result()
    if files.nonEmpty then
      println("Results written to:")
      files.foreach(f => println(s"  $f"))

    if failedPhases.nonEmpty then sys.exit(1)
```

Note: The `failedPhases.nonEmpty` check + `sys.exit(1)` from the original line 182 is now the last line. The old line 171-174 (failed phases warning) stays above.

- [ ] **Step 3: Remove the standalone failedPhases warning (it's already printed inline)**

The `runPhase` wrapper already prints warnings on failure via line 93. The final `failedPhases.nonEmpty` block at old lines 171-174 can be simplified — keep it or remove it. The key exit code behavior is preserved. Keep it for visibility:

Lines 171-174 stay:
```scala
    if failedPhases.nonEmpty then
      println()
      println(s"WARNING: The following phases failed: ${failedPhases.mkString(", ")}")
```

- [ ] **Step 4: Verify run_bench.scala compiles**

Run: `scala-cli compile run_bench.scala`
Expected: Compilation success

---

### Task 3: Update aggregator in aggregate.scala

**Files:** Modify `aggregate.scala`

- [ ] **Step 1: Add --benchmarks-yaml CLI arg**

Replace lines 10-16:

```scala
// Before:
@main
case class Aggregate(
  @arg(name = "results-dir", doc = "Directory with <repo>/<tool>-<scenario>.json files")
  resultsDir: String,
  @arg(name = "output-dir", doc = "Where to write aggregated outputs")
  outputDir: String = "aggregated"
)

// After:
@main
case class Aggregate(
  @arg(name = "results-dir", doc = "Directory with <repo>/<tool>-<scenario>.json files")
  resultsDir: String,
  @arg(name = "output-dir", doc = "Where to write aggregated outputs")
  outputDir: String = "aggregated",
  @arg(name = "benchmarks-yaml", doc = "Path to benchmarks.yaml to discover valid benchmark types")
  benchmarksYaml: String = "benchmarks.yaml"
)
```

- [ ] **Step 2: Load YAML and extract valid benchmark types**

In the `main` method, after the `outputPath` definition (after line 30), add:

```scala
    val benchmarksYamlPath = os.Path(config.benchmarksYaml, os.pwd)
    val benchConfig = Config.load(benchmarksYamlPath)
    val validTypes = benchConfig.hyperfine.benchmark_types.keySet
    println(s"Valid benchmark types: ${validTypes.toSeq.sorted.mkString(", ")}")
```

- [ ] **Step 3: Pass validTypes to parseResultStem in loadResults**

In `loadResults` (line 65), change:

```scala
// Before:
parseResultStem(stem) match

// After:
parseResultStem(stem, validTypes) match
```

But `loadResults` is a private method, so `validTypes` needs to be passed in or made accessible. Change the method signature:

```scala
// Before:
private def loadResults(resultsDir: os.Path): Map[String, Map[String, List[BenchmarkEntry]]] =

// After:
private def loadResults(resultsDir: os.Path, validTypes: Set[String]): Map[String, Map[String, List[BenchmarkEntry]]] =
```

And update the call site in `main` (line 34):

```scala
// Before:
val allData = loadResults(resultsPath)

// After:
val allData = loadResults(resultsPath, validTypes)
```

- [ ] **Step 4: Verify aggregate.scala compiles**

Run: `scala-cli compile aggregate.scala`
Expected: Compilation success

---

### Task 4: Migrate benchmarks.yaml

**Files:** Modify `benchmarks.yaml`

- [ ] **Step 1: Rewrite the hyperfine section**

Replace lines 35-44:

```yaml
hyperfine:
  benchmark_types:
    compile-clean:
      warmup: 2
      runs: 10
    compile-incremental:
      warmup: 1
      runs: 10
    test:
      warmup: 1
      runs: 10
```

- [ ] **Step 2: Update the YAML header comments (lines 1-33)**

Replace the doc comment block with updated field descriptions:

```yaml
# JVM Build Tools Benchmark Configuration
#
# hyperfine:    global benchmark parameters shared by all tools
# build_tools:  per-tool install and shutdown commands (defined once, shared across all repos)
# repos:        external repositories to clone and compile
# tools:        one entry per (tool, repo) combination
#
# Fields in `hyperfine`:
#   benchmark_types.<name>.warmup - unmeasured warmup runs for the named benchmark type
#   benchmark_types.<name>.runs   - measured runs for the named benchmark type
#
# Fields in `build_tools.<name>`:
#   install  - how to install the tool on a fresh CI runner
#   shutdown - clean up daemons / processes after benchmarking
#
# Fields per repo:
#   name    - unique identifier for the repo
#   url     - git URL to clone
#   ref     - branch, tag, or commit SHA to checkout
#   setup   - optional shell command run once after cloning/updating (e.g. remove broken test files)
#
# Fields per tool entry:
#   build_tool_name   - build tool label used in result filenames and overlays; must match a key in build_tools
#   repo              - matches a name in `repos`; combined with build_tool_name to form a unique benchmark id
#   setup             - run once before hyperfine (download deps, warm daemon)
#   benchmarks.<name>.command      - shell command measured by hyperfine
#   benchmarks.<name>.touch_files  - files to `touch` before the benchmark run (optional)
#
# Build file overlays: if build-files/<repo>/<tool>/ exists in this repo,
# its contents are automatically copied into the cloned repo before benchmarking.
```

- [ ] **Step 3: Migrate all 13 active tool entries**

Each entry transforms from old format to new format. Do them in order they appear.

**Entry 1: java-algorithms Maven (lines 115-123)**

Replace:
```yaml
  - build_tool_name: maven
    repo: java-algorithms
    setup: "mvn dependency:resolve -q"
    compile_clean: "mvn clean compile -q"
    compile_incremental: "mvn compile -q"
    incremental_files:
      - src/main/java/com/thealgorithms/sorts/BubbleSort.java
      - src/main/java/com/thealgorithms/sorts/MergeSort.java
    test_all: "mvn test -q -DforkCount=8 -DreuseForks=true"
```

With:
```yaml
  - build_tool_name: maven
    repo: java-algorithms
    setup: "mvn dependency:resolve -q"
    benchmarks:
      compile-clean:
        command: "mvn clean compile -q"
      compile-incremental:
        command: "mvn compile -q"
        touch_files:
          - src/main/java/com/thealgorithms/sorts/BubbleSort.java
          - src/main/java/com/thealgorithms/sorts/MergeSort.java
      test:
        command: "mvn test -q -DforkCount=8 -DreuseForks=true"
```

**Entry 2: java-algorithms Mill (lines 127-135)**

Replace:
```yaml
  - build_tool_name: mill
    repo: java-algorithms
    setup: "mill __.compile"
    compile_clean: "mill clean && mill __.compile"
    compile_incremental: "mill __.compile"
    incremental_files:
      - src/main/java/com/thealgorithms/sorts/BubbleSort.java
      - src/main/java/com/thealgorithms/sorts/MergeSort.java
    test_all: "mill __.test"
```

With:
```yaml
  - build_tool_name: mill
    repo: java-algorithms
    setup: "mill __.compile"
    benchmarks:
      compile-clean:
        command: "mill clean && mill __.compile"
      compile-incremental:
        command: "mill __.compile"
        touch_files:
          - src/main/java/com/thealgorithms/sorts/BubbleSort.java
          - src/main/java/com/thealgorithms/sorts/MergeSort.java
      test:
        command: "mill __.test"
```

**Entry 3: java-algorithms Deder (lines 138-146)**

Replace:
```yaml
  - build_tool_name: deder
    repo: java-algorithms
    setup: "deder exec -t compile"
    compile_clean: "deder clean && deder exec -t compile"
    compile_incremental: "deder exec -t compile"
    incremental_files:
      - src/main/java/com/thealgorithms/sorts/BubbleSort.java
      - src/main/java/com/thealgorithms/sorts/MergeSort.java
    test_all: "deder exec -t test"
```

With:
```yaml
  - build_tool_name: deder
    repo: java-algorithms
    setup: "deder exec -t compile"
    benchmarks:
      compile-clean:
        command: "deder clean && deder exec -t compile"
      compile-incremental:
        command: "deder exec -t compile"
        touch_files:
          - src/main/java/com/thealgorithms/sorts/BubbleSort.java
          - src/main/java/com/thealgorithms/sorts/MergeSort.java
      test:
        command: "deder exec -t test"
```

**Entry 4: java-algorithms sbt (lines 164-172)**

Replace:
```yaml
  - build_tool_name: sbt
    repo: java-algorithms
    setup: "sbt --client compile"
    compile_clean: "sbt --client 'clean; test:clean; compile; test:compile'"
    compile_incremental: "sbt --client compile"
    incremental_files:
      - src/main/java/com/thealgorithms/sorts/BubbleSort.java
      - src/main/java/com/thealgorithms/sorts/MergeSort.java
    test_all: "sbt --client test"
```

With:
```yaml
  - build_tool_name: sbt
    repo: java-algorithms
    setup: "sbt --client compile"
    benchmarks:
      compile-clean:
        command: "sbt --client 'clean; test:clean; compile; test:compile'"
      compile-incremental:
        command: "sbt --client compile"
        touch_files:
          - src/main/java/com/thealgorithms/sorts/BubbleSort.java
          - src/main/java/com/thealgorithms/sorts/MergeSort.java
      test:
        command: "sbt --client test"
```

**Entry 5: java-algorithms sbt2 (lines 176-184)**

Replace:
```yaml
  - build_tool_name: sbt2
    repo: java-algorithms
    setup: "sbt --client compile"
    compile_clean: "sbt --client 'clean; Test/clean; compile; Test/compile'"
    compile_incremental: "sbt --client compile"
    incremental_files:
      - src/main/java/com/thealgorithms/sorts/BubbleSort.java
      - src/main/java/com/thealgorithms/sorts/MergeSort.java
    test_all: "sbt --client test"
```

With:
```yaml
  - build_tool_name: sbt2
    repo: java-algorithms
    setup: "sbt --client compile"
    benchmarks:
      compile-clean:
        command: "sbt --client 'clean; Test/clean; compile; Test/compile'"
      compile-incremental:
        command: "sbt --client compile"
        touch_files:
          - src/main/java/com/thealgorithms/sorts/BubbleSort.java
          - src/main/java/com/thealgorithms/sorts/MergeSort.java
      test:
        command: "sbt --client test"
```

**Entry 6: scala-algorithms sbt (lines 188-200)**

Replace:
```yaml
  - build_tool_name: sbt
    repo: scala-algorithms
    setup: |
      # scala version is old; use gsed on macOS, sed on Linux
      $(command -v gsed 2>/dev/null || echo sed) -i 's/scalaVersion := "2.13.6",/scalaVersion := "2.13.11",/g' build.sbt
      sbt --client compile
    compile_clean: |
      sbt --client 'clean; test:clean; compile; test:compile'
    compile_incremental: "sbt --client compile"
    incremental_files:
      - src/main/scala/Sort/BubbleSort.scala
      - src/main/scala/Sort/MergeSort.scala
    test_all: "sbt --client test"
```

With:
```yaml
  - build_tool_name: sbt
    repo: scala-algorithms
    setup: |
      # scala version is old; use gsed on macOS, sed on Linux
      $(command -v gsed 2>/dev/null || echo sed) -i 's/scalaVersion := "2.13.6",/scalaVersion := "2.13.11",/g' build.sbt
      sbt --client compile
    benchmarks:
      compile-clean:
        command: |
          sbt --client 'clean; test:clean; compile; test:compile'
      compile-incremental:
        command: "sbt --client compile"
        touch_files:
          - src/main/scala/Sort/BubbleSort.scala
          - src/main/scala/Sort/MergeSort.scala
      test:
        command: "sbt --client test"
```

**Entry 7: scala-algorithms sbt2 (lines 204-216)**

Replace:
```yaml
  - build_tool_name: sbt2
    repo: scala-algorithms
    setup: |
      # scala version is old; use gsed on macOS, sed on Linux
      $(command -v gsed 2>/dev/null || echo sed) -i 's/scalaVersion := "2.13.6",/scalaVersion := "2.13.11",/g' build.sbt
      sbt --client compile
    compile_clean: |
      sbt --client 'clean; Test/clean; compile; Test/compile'
    compile_incremental: "sbt --client compile"
    incremental_files:
      - src/main/scala/Sort/BubbleSort.scala
      - src/main/scala/Sort/MergeSort.scala
    test_all: "sbt --client test"
```

With:
```yaml
  - build_tool_name: sbt2
    repo: scala-algorithms
    setup: |
      # scala version is old; use gsed on macOS, sed on Linux
      $(command -v gsed 2>/dev/null || echo sed) -i 's/scalaVersion := "2.13.6",/scalaVersion := "2.13.11",/g' build.sbt
      sbt --client compile
    benchmarks:
      compile-clean:
        command: |
          sbt --client 'clean; Test/clean; compile; Test/compile'
      compile-incremental:
        command: "sbt --client compile"
        touch_files:
          - src/main/scala/Sort/BubbleSort.scala
          - src/main/scala/Sort/MergeSort.scala
      test:
        command: "sbt --client test"
```

**Entry 8: scala-algorithms Deder (lines 219-227)**

Replace:
```yaml
  - build_tool_name: deder
    repo: scala-algorithms
    setup: "deder exec -t compile"
    compile_clean: "deder clean && deder exec -t compile"
    compile_incremental: "deder exec -t compile"
    incremental_files:
      - src/main/scala/Sort/BubbleSort.scala
      - src/main/scala/Sort/MergeSort.scala
    test_all: "deder exec -t testInMemory"
```

With:
```yaml
  - build_tool_name: deder
    repo: scala-algorithms
    setup: "deder exec -t compile"
    benchmarks:
      compile-clean:
        command: "deder clean && deder exec -t compile"
      compile-incremental:
        command: "deder exec -t compile"
        touch_files:
          - src/main/scala/Sort/BubbleSort.scala
          - src/main/scala/Sort/MergeSort.scala
      test:
        command: "deder exec -t testInMemory"
```

**Entry 9: scala-algorithms Mill (lines 243-251)**

Replace:
```yaml
  - build_tool_name: mill
    repo: scala-algorithms
    setup: "mill __.compile"
    compile_clean: "mill clean && mill __.compile"
    compile_incremental: "mill __.compile"
    incremental_files:
      - src/main/scala/Sort/BubbleSort.scala
      - src/main/scala/Sort/MergeSort.scala
    test_all: "mill __.testLocal"
```

With:
```yaml
  - build_tool_name: mill
    repo: scala-algorithms
    setup: "mill __.compile"
    benchmarks:
      compile-clean:
        command: "mill clean && mill __.compile"
      compile-incremental:
        command: "mill __.compile"
        touch_files:
          - src/main/scala/Sort/BubbleSort.scala
          - src/main/scala/Sort/MergeSort.scala
      test:
        command: "mill __.testLocal"
```

- [ ] **Step 4: Update commented-out Bleep entries (optional, for consistency)**

Both commented-out bleep entries (java-algorithms lines 148-160, scala-algorithms lines 230-239) should have their format updated inside comments. This is cosmetic but maintains consistency for when they're re-enabled.

**java-algorithms Bleep (lines 148-160)** — replace:
```yaml
  #- build_tool_name: bleep
  #  repo: java-algorithms
  #  setup: "bleep compile algorithms-test"
  #  compile_clean: "bleep clean && bleep compile algorithms"
  #  compile_incremental: "bleep compile algorithms"
  #  incremental_files:
  #    - src/main/java/com/thealgorithms/sorts/BubbleSort.java
  #    - src/main/java/com/thealgorithms/sorts/MergeSort.java
  #  test_all: "bleep test algorithms-test"
```
With:
```yaml
  #- build_tool_name: bleep
  #  repo: java-algorithms
  #  setup: "bleep compile algorithms-test"
  #  benchmarks:
  #    compile-clean:
  #      command: "bleep clean && bleep compile algorithms"
  #    compile-incremental:
  #      command: "bleep compile algorithms"
  #      touch_files:
  #        - src/main/java/com/thealgorithms/sorts/BubbleSort.java
  #        - src/main/java/com/thealgorithms/sorts/MergeSort.java
  #    test:
  #      command: "bleep test algorithms-test"
```

**scala-algorithms Bleep (lines 230-239)** — replace:
```yaml
  #- build_tool_name: bleep
  #  repo: scala-algorithms
  #  setup: "bleep compile algorithms-test"
  #  compile_clean: "bleep clean && bleep compile algorithms"
  #  compile_incremental: "bleep compile algorithms"
  #  incremental_files:
  #    - src/main/scala/Sort/BubbleSort.scala
  #    - src/main/scala/Sort/MergeSort.scala
  #  test_all: "bleep test algorithms-test"
```
With:
```yaml
  #- build_tool_name: bleep
  #  repo: scala-algorithms
  #  setup: "bleep compile algorithms-test"
  #  benchmarks:
  #    compile-clean:
  #      command: "bleep clean && bleep compile algorithms"
  #    compile-incremental:
  #      command: "bleep compile algorithms"
  #      touch_files:
  #        - src/main/scala/Sort/BubbleSort.scala
  #        - src/main/scala/Sort/MergeSort.scala
  #    test:
  #      command: "bleep test algorithms-test"
```

- [ ] **Step 5: Verify YAML structure is valid**

Run: `scala-cli run run_bench.scala -- --list-benchmarks`
Expected: Prints a JSON array of all benchmark IDs (same list as before, just `test` not `test_all` in the output... wait, no — `--list-benchmarks` returns tool+repo combos, not benchmark types. The output should be unchanged.)

---

### Task 5: Update CI workflow

**Files:** Modify `.github/workflows/bench.yml`

- [ ] **Step 1: Add --benchmarks-yaml to aggregate job**

Replace lines 102-105:

```yaml
# Before:
      - name: Aggregate results
        run: |
          scala-cli run aggregate.scala -- \
            --results-dir all-results/ \
            --output-dir aggregated/

# After:
      - name: Aggregate results
        run: |
          scala-cli run aggregate.scala -- \
            --results-dir all-results/ \
            --output-dir aggregated/ \
            --benchmarks-yaml benchmarks.yaml
```

---

### Task 6: Update README.md

**Files:** Modify `README.md`

- [ ] **Step 1: Update "Three scenarios" section (lines 7-13)**

Replace:
```markdown
Three scenarios are measured per tool:

| Scenario | Description |
|---|---|
| **clean compile** | Full rebuild from scratch (`clean` + compile) |
| **incremental compile** | Touch a set of source files, recompile |
| **test all** | Run all tests |
```

With:
```markdown
Multiple benchmark types are measured per tool — each defined in `benchmarks.yaml` as a named entry under `hyperfine.benchmark_types`. Typical types include:

| Benchmark type | Description |
|---|---|
| **compile-clean** | Full rebuild from scratch (clean + compile) |
| **compile-incremental** | Touch a set of source files, recompile |
| **test** | Run all tests |

Additional types (e.g., `compile-module-abc`) can be added without changing any Scala code.
```

- [ ] **Step 2: Update file structure example (lines 64-75)**

Replace:
```text
results/
├── java-algorithms/
│   ├── maven-clean-compile.json
│   ├── maven-incremental-compile.json
│   ├── maven-test-all.json
│   └── ...
└── scala-algorithms/
    ├── sbt-clean-compile.json
    ├── sbt-incremental-compile.json
    └── ...
```

With:
```text
results/
├── java-algorithms/
│   ├── maven-compile-clean.json
│   ├── maven-compile-incremental.json
│   ├── maven-test.json
│   └── ...
└── scala-algorithms/
    ├── sbt-compile-clean.json
    ├── sbt-compile-incremental.json
    └── ...
```

- [ ] **Step 3: Update aggregate command example (line 100)**

Replace:
```bash
scala-cli run aggregate.scala -- --results-dir results/ --output-dir aggregated/
```

With:
```bash
scala-cli run aggregate.scala -- --results-dir results/ --output-dir aggregated/ --benchmarks-yaml benchmarks.yaml
```

- [ ] **Step 4: Update "How it works" item 2 (lines 22-23)**

Replace:
```
2. **`run_bench.scala`** — Scala CLI runner. Parses `benchmarks.yaml` directly (no `yq` needed), clones the target repo, optionally overlays extra build files, runs setup, then benchmarks clean and incremental compilation.
```

With:
```
2. **`run_bench.scala`** — Scala CLI runner. Parses `benchmarks.yaml` directly (no `yq` needed), clones the target repo, optionally overlays extra build files, runs setup, then benchmarks each benchmark type defined for the tool.
```

---

### Task 7: Verify everything compiles and works

**Files:** All modified files

- [ ] **Step 1: Compile all three Scala files together**

Run: `scala-cli compile bench.scala run_bench.scala aggregate.scala`
Expected: Compilation success, no errors

- [ ] **Step 2: Test --list-benchmarks**

Run: `scala-cli run run_bench.scala -- --list-benchmarks`
Expected: JSON array of benchmark IDs (e.g., `["java-algorithms-deder","java-algorithms-maven",...]`)

- [ ] **Step 3: Test --install-cmd for one tool**

Run: `scala-cli run run_bench.scala -- --install-cmd --benchmark java-algorithms-maven`
Expected: Prints install command, exits 0

- [ ] **Step 4: Test aggregate --help**

Run: `scala-cli run aggregate.scala -- --help`
Expected: Shows usage including `--benchmarks-yaml` option

- [ ] **Step 5: Commit all changes**

```bash
git add bench.scala run_bench.scala aggregate.scala benchmarks.yaml .github/workflows/bench.yml README.md docs/superpowers/specs/2026-04-27-generic-benchmark-types-design.md docs/superpowers/plans/2026-04-27-generic-benchmark-types.md
git commit -m "refactor: replace hardcoded benchmark types with generic Map-based config"
```
