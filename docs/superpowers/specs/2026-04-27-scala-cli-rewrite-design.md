# Scala-CLI Rewrite of Benchmark Scripts

**Date:** 2026-04-27
**Status:** Approved

## Summary

Rewrite `run_bench.sh` (bash) and `aggregate.py` (Python) into Scala using scala-cli, eliminating the need for two languages and the `yq` dependency. The result is a unified, typed codebase that's easier to understand and maintain.

## Goals

- **Unified language**: Single typed language (Scala 3) for all scripting
- **Eliminate yq**: Parse YAML directly with scala-yaml
- **Eliminate Python**: Use JVM charting library instead of matplotlib
- **Maintain feature parity**: All current functionality preserved

## Non-Goals

- Restructuring `benchmarks.yaml` format (keep current structure)
- Comprehensive test suite (minimal testing initially, port later)
- Performance optimization of the scripts themselves

## File Structure

```
bench.scala           # Shared: config model, YAML parsing, git ops, hyperfine runner, utilities
run_bench.scala       # Entry point for running benchmarks
aggregate.scala       # Entry point for aggregating results + charts
```

## Dependencies

```scala
//> using scala 3.3
//> using dep org.virtuslab::scala-yaml:0.3.0    // YAML parsing
//> using dep com.lihaoyi::os-lib:0.11.4         // Process execution, file I/O
//> using dep com.lihaoyi::mainargs:0.7.6        // CLI argument parsing
//> using dep com.lihaoyi::upickle:4.1.0         // JSON parsing (hyperfine results)
//> using dep org.jfree:jfreechart:1.5.6         // Chart generation
//> using dep org.jfree:jfreesvg:3.4.3           // SVG export for JFreeChart
```

## Configuration Model

```scala
case class HyperfinePhaseConfig(warmup: Int, runs: Int)
case class HyperfineConfig(
  cleanCompile: HyperfinePhaseConfig,
  incrementalCompile: HyperfinePhaseConfig,
  testAll: HyperfinePhaseConfig
)

case class BuildToolDef(install: String, shutdown: Option[String])

case class RepoDef(
  name: String,
  url: String,
  ref: String,
  setup: Option[String]
)

case class ToolConfig(
  buildToolName: String,
  repo: String,
  setup: Option[String],
  compileClean: String,
  compileIncremental: String,
  incrementalFiles: List[String],
  testAll: Option[String]
)

case class BenchmarkConfig(
  hyperfine: HyperfineConfig,
  buildTools: Map[String, BuildToolDef],
  repos: List[RepoDef],
  tools: List[ToolConfig]
)
```

## Shared Components (bench.scala)

### Config Parsing
- Load `benchmarks.yaml` from script directory
- Parse with scala-yaml into typed case classes
- Handle snake_case → camelCase field mapping

### Git Operations
- `cloneOrUpdate(repo, reposDir)`: Clone if not exists, fetch + checkout if exists
- Handle both 40-char SHA refs and branch/tag refs

### Hyperfine Runner
- Shell out to `hyperfine` with `--export-json`
- Parse result JSON, return typed `HyperfineResult`

### File Overlay
- Copy `build-files/<repo>/<tool>/*` to target dir if exists

### Tool Colors
- Static map of tool → hex color (moved from `tool_colors.py`)
- Fail fast on unknown tool

## run_bench.scala

### CLI
```
scala-cli run run_bench.scala -- \
  --benchmark <repo>-<build-tool> \
  [--results-dir <dir>] \
  [--repos-dir <dir>] \
  [--list-benchmarks]
```

### Flow
1. Load config from `benchmarks.yaml`
2. Resolve benchmark by ID → `ToolConfig` + `RepoDef`
3. Clone/update target repo
4. Apply build file overlay
5. Run repo-level setup (if defined)
6. Run tool-level setup (if defined)
7. Benchmark clean compile → `<results>/<repo>/<tool>-clean-compile.json`
8. Touch incremental files
9. Benchmark incremental compile → `<results>/<repo>/<tool>-incremental-compile.json`
10. Benchmark test_all (if defined) → `<results>/<repo>/<tool>-test-all.json`
11. Run shutdown command (if defined)
12. Report results, exit 1 if any phase failed

### Error Handling
- Track failed phases, continue execution
- Report all failures at end
- Non-zero exit if any failure

## aggregate.scala

### CLI
```
scala-cli run aggregate.scala -- \
  --results-dir <dir> \
  [--output-dir <dir>]
```

### Flow
1. Scan `<results-dir>/**/*.json` for hyperfine result files
2. Parse scenario from filename suffix (`-clean-compile`, `-incremental-compile`, `-test-all`)
3. Validate all tools have configured colors
4. Parse each JSON, extract mean/stddev/min/max/median
5. Group by repo → scenario, sort by mean (fastest first)
6. For each repo/scenario:
   - Write `summary.json`
   - Write `summary.md` (markdown table)
   - Write `chart.svg` (horizontal bar chart)
7. Print ASCII bar charts to stdout
8. Generate `index.html` with collapsible repo sections

### Chart Generation
- JFreeChart horizontal bar chart
- Tool-specific colors from `ToolColors`
- Error bars for stddev
- SVG export via jfreesvg

## GitHub Actions Changes

### Setup Job
- Remove yq installation
- Add scala-cli installation
- Use `--list-benchmarks` flag for matrix generation

### Bench Job
- Remove yq installation
- Add scala-cli (cached via coursier)
- Replace `./run_bench.sh` with `scala-cli run run_bench.scala --`

### Aggregate Job
- Remove `pip install matplotlib`
- Add scala-cli
- Replace `python aggregate.py` with `scala-cli run aggregate.scala --`

## Files to Delete After Migration
- `run_bench.sh`
- `aggregate.py`
- `tool_colors.py`
- `tests/` (Python tests, port later if needed)

## Migration Strategy
1. Implement new Scala scripts alongside existing ones
2. Test locally with same inputs
3. Update CI workflow
4. Verify CI passes
5. Remove old files