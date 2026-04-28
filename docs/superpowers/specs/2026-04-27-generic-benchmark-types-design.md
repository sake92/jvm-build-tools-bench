# Generic Benchmark Types — Design Spec

**Date:** 2026-04-27  
**Status:** approved  
**Goal:** Replace the 3 hardcoded benchmark types (`compile_clean`, `compile_incremental`, `test_all`) with an arbitrary set of named types, so new benchmark types (e.g., `compile-module-abc`) can be added without changing Scala code. Graphs, tables, and HTML must adapt automatically. Failure-independence must be preserved.

---

## 1. Data Model (`bench.scala`)

### 1.1 New case classes

```scala
// Renamed from HyperfinePhaseConfig — one per named benchmark type
case class BenchmarkTypeConfig(
  warmup: Int,
  runs: Int
) derives YamlDecoder

// Per-benchmark command + optional touch-files
case class ToolBenchmark(
  command: String,
  touch_files: List[String] = Nil
) derives YamlDecoder
```

### 1.2 Rewritten config classes

```scala
// Before: 3 hardcoded fields
// After:  generic map keyed by benchmark type name
case class HyperfineConfig(
  benchmark_types: Map[String, BenchmarkTypeConfig]
) derives YamlDecoder

// Before: compile_clean, compile_incremental, incremental_files, test_all
// After:  benchmarks map — each entry holds command + optional touch_files
case class ToolConfig(
  build_tool_name: String,
  repo: String,
  setup: Option[String] = None,
  benchmarks: Map[String, ToolBenchmark]
) derives YamlDecoder
```

`HyperfinePhaseConfig` is renamed to `BenchmarkTypeConfig`.  
`incremental_files` is removed from `ToolConfig`; `touch_files` now lives inside `ToolBenchmark` so touch behavior is tied to a specific benchmark entry.

### 1.3 Removals

- `HyperfinePhaseConfig` → renamed to `BenchmarkTypeConfig`
- `scenarioAliases` map — removed entirely
- `parseResultStem(stem: String)` replaced with `parseResultStem(stem: String, validBenchmarkTypes: Set[String])`

### 1.4 New `parseResultStem`

```scala
def parseResultStem(stem: String, validBenchmarkTypes: Set[String]): Option[(String, String)] =
  validBenchmarkTypes.toSeq
    .sortBy(-_.length)  // longest first to avoid partial matches
    .collectFirst {
      case suffix if stem.endsWith(s"-$suffix") && stem.stripSuffix(s"-$suffix").nonEmpty =>
        val tool = stem.stripSuffix(s"-$suffix")
        (tool, suffix)
    }
```

Takes the set of valid benchmark type names (extracted from YAML's `hyperfine.benchmark_types` keys by the aggregator). No shorthand aliases.

---

## 2. Runner (`run_bench.scala`)

### 2.1 Dynamic loop replaces 3 hardcoded phase blocks

The three separate blocks for clean-compile, incremental-compile, and test-all (lines 125–161) are replaced by a single loop over `toolConfig.benchmarks`:

```scala
val writtenFiles = List.newBuilder[os.Path]

for (benchmarkType, bm) <- toolConfig.benchmarks.toSeq.sortBy(_._1) do
  val hfConfig = hf.benchmark_types.getOrElse(benchmarkType,
    throw RuntimeException(s"Benchmark type '$benchmarkType' not declared in hyperfine.benchmark_types"))

  runPhase(benchmarkType):
    // Touch files (if any)
    bm.touch_files.foreach: f =>
      val p = repoDir / os.RelPath(f)
      if os.exists(p) then os.write.append(p, "")
      else System.err.println(s"  Warning: file not found: $p")

    // Output: <resultsDir>/<repo>/<tool>-<benchmarkType>.json
    val outFile = repoResultsDir / s"${toolConfig.build_tool_name}-$benchmarkType.json"
    Hyperfine.run(bm.command, hfConfig.warmup, hfConfig.runs, outFile, repoDir)
    println(s"  Results: $outFile")
    writtenFiles += outFile
```

### 2.2 Summary output

The final "Results written to:" block uses `writtenFiles.result()` instead of 3 hardcoded paths.

### 2.3 Error handling

- Missing benchmark type in `hyperfine.benchmark_types`: throws `RuntimeException` with a clear message — misconfiguration is caught early rather than silently skipped.
- `runPhase` wrapper unchanged: each benchmark type is independently resilient. One phase failing does not block others.
- Exit code: if any phase failed, `sys.exit(1)` at end (unchanged).

### 2.4 What does NOT change

- CLI args (`--benchmark`, `--results-dir`, `--repos-dir`, `--list-benchmarks`, `--install-cmd`)
- `--list-benchmarks` still returns tool+repo combos (the benchmark types are phases within a run, not separate CI jobs)
- Git clone, repo setup, tool setup, build-file overlay, shutdown — all unchanged
- `runPhase` wrapper — unchanged

---

## 3. Aggregator (`aggregate.scala`)

### 3.1 New CLI arg

`--benchmarks-yaml` (String, required) — path to `benchmarks.yaml` so the aggregator can discover valid benchmark type names.

### 3.2 YAML loading for type discovery

At startup, the aggregator reads the YAML and extracts the set of benchmark type names:

```scala
val benchConfig = Config.load(benchmarksYamlPath)
val validTypes = benchConfig.hyperfine.benchmark_types.keySet
```

### 3.3 Filename parsing

`parseResultStem(stem, validTypes)` is called with the set from the YAML. This replaces the old `parseResultStem(stem)` which used hardcoded `scenarioAliases`.

### 3.4 What does NOT change

- SVG chart generation (`writeSvgChart`) — already dynamic
- Markdown tables (`writeSummaryMd`) — already dynamic
- JSON summaries (`writeSummaryJson`) — already dynamic
- ASCII bar charts (`asciiBarChart`) — already dynamic
- HTML index (`writeIndexHtml`) — already dynamic
- `tryParseHyperfine` — unchanged
- Top-level directory walking (`loadResults`) — unchanged (still walks `results/` directory)

---

## 4. YAML Migration (`benchmarks.yaml`)

### 4.1 Top-level hyperfine section

Before:
```yaml
hyperfine:
  clean_compile:
    warmup: 2
    runs: 10
  incremental_compile:
    warmup: 1
    runs: 10
  test_all:
    warmup: 1
    runs: 10
```

After:
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

### 4.2 Per-tool entries

Before:
```yaml
- build_tool_name: maven
  repo: java-algorithms
  setup: "mvn dependency:resolve -q"
  compile_clean: "mvn clean compile -q"
  compile_incremental: "mvn compile -q"
  incremental_files:
    - src/.../BubbleSort.java
    - src/.../MergeSort.java
  test_all: "mvn test -q -DforkCount=8 -DreuseForks=true"
```

After:
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
        - src/.../BubbleSort.java
        - src/.../MergeSort.java
    test:
      command: "mvn test -q -DforkCount=8 -DreuseForks=true"
```

### 4.3 Naming conventions

- Benchmark type keys use hyphens (`compile-clean`, `compile-incremental`, `test`)
- These keys are canonical: they appear identically in YAML, result filenames, chart labels, and directory names
- Old underscore names (`compile_clean`, `test_all`) are discarded

### 4.4 Migration scope

All 13 active tool entries require the same structural transformation. The 2 commented-out Bleep entries follow the new format in comments.

---

## 5. CI Workflow (`bench.yml`)

### 5.1 Aggregate job

One line added to pass the YAML path:

```yaml
- name: Aggregate results
  run: |
    scala-cli run aggregate.scala -- \
      --results-dir all-results/ \
      --output-dir aggregated/ \
      --benchmarks-yaml benchmarks.yaml
```

### 5.2 What does NOT change

- `--list-benchmarks` pipeline — unchanged (tool+repo combos not affected)
- `fail-fast: false` on matrix — unchanged
- `if: always()` on artifact upload and aggregate job — unchanged
- Setup and bench jobs — unchanged

---

## 6. Failure-Independence Preservation

| Layer | Mechanism | Status |
|-------|-----------|--------|
| CI matrix | `fail-fast: false` | Unchanged |
| CI artifact upload | `if: always()` | Unchanged |
| CI aggregate job | `if: always()` | Unchanged |
| Runner per-phase | `runPhase()` try/catch wrapper | Dynamic loop still calls `runPhase` per benchmark type |
| Runner shutdown | Always runs, errors non-fatal | Unchanged |
| Aggregator bad files | `tryParseHyperfine` catches and skips | Unchanged |
| Aggregator chart gen | Catches exceptions per scenario | Unchanged |

---

## 7. README Updates

- "Three scenarios" section → generalized to mention arbitrary benchmark types
- File structure example updated to show hyphenated names
- Command examples adjusted where they reference old field names

---

## 8. Out of Scope

- Per-benchmark `setup` field (beyond tool-level `setup`)
- Per-tool hyperfine overrides (global hyperfine config remains shared)
- Changing the `build_tools` section structure
- Adding new repositories or tools beyond the migration
- Test suite (no tests exist yet in this project)
