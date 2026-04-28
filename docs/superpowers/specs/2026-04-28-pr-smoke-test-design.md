# PR Smoke Test — Design Spec

**Date:** 2026-04-28  
**Status:** awaiting review  
**Goal:** When a PR triggers the CI pipeline, run hyperfine with `warmup=0, runs=1` instead of the full values from `benchmarks.yaml`. Tool installation, repo setup, and build commands are unchanged — the goal is pure pipeline validation (does everything still work?), not accurate timing.

---

## 1. Trigger Mechanism

An environment variable `BENCH_SMOKE` controls the mode:

| Value | Behavior |
|-------|----------|
| `true` or `1` | Smoke mode: all benchmark types use `warmup=0, runs=1` |
| Missing or any other value | Full mode: warmup/runs read from `benchmarks.yaml` |

The environment variable is set automatically by the CI workflow on PR events. Users can also smoke-test locally:

```bash
BENCH_SMOKE=true scala-cli run run_bench.scala -- --benchmark java-algorithms-maven
```

---

## 2. CI Workflow (`bench.yml`)

### 2.1 New step in `bench` job

One step added **before** "Run benchmark":

```yaml
- name: Enable smoke mode for PRs
  if: github.event_name == 'pull_request'
  run: echo "BENCH_SMOKE=true" >> "$GITHUB_ENV"
```

The `$GITHUB_ENV` mechanism makes `BENCH_SMOKE` available to all subsequent steps in the same job. The `if:` guard ensures only PR events activate smoke mode.

### 2.2 Behavior matrix

| Trigger | Hyperfine params | Pages deployed? |
|---------|-----------------|----------------|
| `pull_request` | warmup=0, runs=1 (smoke) | No |
| `push: main` | From YAML (2/10 or 1/10) | Yes |
| `workflow_dispatch` | From YAML (full) | No (not main) |

### 2.3 What does NOT change

- `setup` job (matrix generation) — unchanged
- `aggregate` job — unchanged (reads whatever JSON hyperfine produces)
- `deploy-pages` job — already gated to `main` with `if: always() && github.ref == 'refs/heads/main'`
- Tool install step — unchanged (fully exercised in smoke mode)
- `if: always()` on artifact upload — unchanged (results are still uploaded regardless of failure)
- `fail-fast: false` on matrix — unchanged

---

## 3. Runner (`run_bench.scala`)

### 3.1 Smoke mode override

In the benchmark loop, after resolving `hfConfig` from the YAML (line ~124), add the override:

```scala
val (warmup, runs) = sys.env.get("BENCH_SMOKE") match
  case Some("true" | "1") =>
    println(s">>> Smoke mode: overriding $benchmarkType " +
      s"(warmup ${hfConfig.warmup}→0, runs ${hfConfig.runs}→1)")
    (0, 1)
  case _ =>
    (hfConfig.warmup, hfConfig.runs)
```

Then pass the resolved values to `Hyperfine.run`:

```scala
Hyperfine.run(bm.command, warmup, runs, outFile, repoDir)
// (was: Hyperfine.run(bm.command, hfConfig.warmup, hfConfig.runs, outFile, repoDir))
```

### 3.2 Design decisions

- **Per-benchmark-type resolution**: The env var is checked inside the loop, not once at startup. This means we log both the YAML value and the override for each type, making CI logs clear.
- **Tight guard**: Only `"true"` or `"1"` activate smoke mode. Random env values are safe no-ops.
- **No new CLI args**: Keeps the `RunBench` case class unchanged.
- **No YAML changes**: `benchmarks.yaml` remains the single source of truth for full-run parameters.

### 3.3 What does NOT change

- CLI args (`--benchmark`, `--results-dir`, `--repos-dir`, `--list-benchmarks`, `--install-cmd`)
- Tool install (`--install-cmd`) — fully exercised
- Git clone, repo setup, tool setup, build-file overlay — unchanged
- Tool shutdown — unchanged
- `runPhase` error handling — unchanged
- Result filenames — unchanged (still `<tool>-<type>.json`)
- Exit code behavior — unchanged

---

## 4. No Changes Needed

| Component | Reason |
|-----------|--------|
| `benchmarks.yaml` | Unchanged — full-run params remain canonical |
| `bench.scala` (`Hyperfine.run`) | Already accepts `warmup: Int` and `runs: Int` as parameters. Override happens before the call. |
| `aggregate.scala` | Reads whatever JSON hyperfine produced — works with 1 run or 10 |
| `deploy-pages` job | Already gated to `main` only |
| `setup` job (matrix) | Still lists all benchmarks |

---

## 5. Failure-Independence Preservation

All existing isolation guarantees are preserved in smoke mode:

| Layer | Mechanism | Status |
|-------|-----------|--------|
| CI matrix | `fail-fast: false` | Unchanged |
| CI artifact upload | `if: always()` | Unchanged |
| CI aggregate job | `if: always()` | Unchanged |
| Runner per-type | `runPhase()` try/catch wrapper | Unchanged |
| Runner shutdown | Always runs, errors non-fatal | Unchanged |
| Aggregator bad files | `tryParseHyperfine` catches and skips | Unchanged |

---

## 6. Out of Scope

- Per-benchmark smoke overrides (e.g., different smoke values for compile-clean vs test) — all types share the same `0/1`
- CLI flag for smoke mode — no new mainargs arg
- Smoke-specific YAML entries — YAML is unchanged
- Disabling the deploy-pages step on PR (already done)
- Changing the aggregate step behavior for smoke results
