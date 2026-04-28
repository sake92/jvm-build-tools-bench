# PR Smoke Test — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-activate `warmup=0, runs=1` hyperfine mode on PR CI runs via `BENCH_SMOKE` environment variable.

**Architecture:** An env var `BENCH_SMOKE` set by the CI workflow (only on `pull_request` events) is read by `run_bench.scala` inside the benchmark loop. When `"true"` or `"1"`, it overrides warmup/runs to 0/1. No YAML changes, no CLI flags, no changes to `bench.scala` or `aggregate.scala`.

**Tech Stack:** Scala 3.3, scala-cli, GitHub Actions YAML, hyperfine

---

## Files

| File | Action | Responsibility |
|------|--------|---------------|
| `.github/workflows/bench.yml` | Modify | Set `BENCH_SMOKE=true` env var on PR events |
| `run_bench.scala` | Modify | Read `BENCH_SMOKE` env var, override warmup/runs |
| `docs/superpowers/plans/2026-04-28-pr-smoke-test.md` | Create | This plan document |

---

### Task 1: Add smoke mode env var to CI workflow

**Files:**
- Modify: `.github/workflows/bench.yml`

- [ ] **Step 1: Add smoke mode step before "Run benchmark"**

In the `bench` job, insert a new step after "Install build tool" and before "Run benchmark" (after line 68, before line 69):

```yaml
      - name: Enable smoke mode for PRs
        if: github.event_name == 'pull_request'
        run: echo "BENCH_SMOKE=true" >> "$GITHUB_ENV"
```

The result should look like:

```yaml
      - name: Install build tool
        run: |
          scala-cli run run_bench.scala -- --install-cmd --benchmark "${{ matrix.benchmark }}"
      - name: Enable smoke mode for PRs
        if: github.event_name == 'pull_request'
        run: echo "BENCH_SMOKE=true" >> "$GITHUB_ENV"
      - name: Run benchmark
        run: |
          scala-cli run run_bench.scala -- \
            --benchmark "${{ matrix.benchmark }}" \
            --results-dir results/ \
            --repos-dir "${{ runner.temp }}/bench-repos"
```

- [ ] **Step 2: Verify YAML syntax**

```bash
yamllint .github/workflows/bench.yml || echo "No yamllint installed — visually verify"
```

Visually verify indentation is consistent (2 spaces).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/bench.yml
git commit -m "ci: enable BENCH_SMOKE env var on PR events"
```

---

### Task 2: Add smoke mode override to run_bench.scala

**Files:**
- Modify: `run_bench.scala:119-148` (the benchmark loop)

- [ ] **Step 1: Read the current benchmark loop to confirm line numbers**

The section to modify is in `RunBench.main`, after `val hf = benchConfig.hyperfine` (line 119), inside the `for (benchmarkType, bm) <- toolConfig.benchmarks.toSeq.sortBy(_._1) do` loop. Specifically, lines 123-148 where `hfConfig` is resolved and `Hyperfine.run` is called.

- [ ] **Step 2: Add smoke mode override**

In the loop body, after the `hfConfig` resolution block (lines 123-130), and before `runPhase(benchmarkType)`, add the override. Change this section:

**Before (lines 123-148):**
```scala
    for (benchmarkType, bm) <- toolConfig.benchmarks.toSeq.sortBy(_._1) do
      val hfConfig = hf.benchmark_types.getOrElse(benchmarkType, {
        System.err.println(
          s"Error: Benchmark type '$benchmarkType' is not declared in hyperfine.benchmark_types. " +
          s"Available types: ${hf.benchmark_types.keys.toSeq.sorted.mkString(", ")}"
        )
        sys.exit(1)
      })

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

**After:**
```scala
    for (benchmarkType, bm) <- toolConfig.benchmarks.toSeq.sortBy(_._1) do
      val hfConfig = hf.benchmark_types.getOrElse(benchmarkType, {
        System.err.println(
          s"Error: Benchmark type '$benchmarkType' is not declared in hyperfine.benchmark_types. " +
          s"Available types: ${hf.benchmark_types.keys.toSeq.sorted.mkString(", ")}"
        )
        sys.exit(1)
      })

      // Smoke mode override: PRs use warmup=0, runs=1 for fast pipeline validation
      val (warmup, runs) = sys.env.get("BENCH_SMOKE") match
        case Some("true" | "1") =>
          println(s">>> Smoke mode: overriding $benchmarkType " +
            s"(warmup ${hfConfig.warmup}→0, runs ${hfConfig.runs}→1)")
          (0, 1)
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
```

Key change: `Hyperfine.run(bm.command, hfConfig.warmup, hfConfig.runs, ...)` becomes `Hyperfine.run(bm.command, warmup, runs, ...)` — using the resolved `(warmup, runs)` tuple instead of the raw `hfConfig` fields.

- [ ] **Step 3: Verify the script compiles**

```bash
scala-cli compile run_bench.scala
```

Expected: Compiles without errors.

- [ ] **Step 4: Verify --help still shows same args (no new flags)**

```bash
scala-cli run run_bench.scala -- --help
```

Expected: Same output as before — no new args.

- [ ] **Step 5: Smoke test locally (optional, requires hyperfine + build tools)**

```bash
BENCH_SMOKE=true scala-cli run run_bench.scala -- --benchmark java-algorithms-maven --results-dir /tmp/test-results/
```

Expected: Should see `">>> Smoke mode: overriding compile-clean (warmup 2→0, runs 10→1)"` in output, hyperfine runs with 0 warmup and 1 run.

- [ ] **Step 6: Commit**

```bash
git add run_bench.scala
git commit -m "feat: support BENCH_SMOKE env var to override hyperfine to warmup=0,runs=1"
```

---

### Task 3: Write plan document

**Files:**
- Create: `docs/superpowers/plans/2026-04-28-pr-smoke-test.md`

- [ ] **Step 1: Write this plan document**

Write the full content of this plan to `docs/superpowers/plans/2026-04-28-pr-smoke-test.md`.

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/plans/2026-04-28-pr-smoke-test.md
git commit -m "docs: add implementation plan for PR smoke test"
```
