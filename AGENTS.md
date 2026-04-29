# AGENTS.md

## Project identity

This is a **benchmark harness** — not an application. It clones open-source JVM repos, overlays build files, and runs hyperfine benchmarks via GitHub Actions. There is no build step for this repo itself. All scripts are Scala CLI.

## Entrypoints

```
scala-cli run run_bench.scala -- --benchmark <repo>-<tool>
scala-cli run aggregate.scala  -- --results-dir results/ --output-dir aggregated/
```

`run_bench.scala` and `aggregate.scala` both include `bench.scala` via `//> using file bench.scala`. That shared file defines the config model, YAML parsing, git operations, and hyperfine runner.

## Single source of truth

**`benchmarks.yaml`** drives everything:
- CI matrix is generated dynamically from it (`--list-benchmarks` outputs JSON)
- `run_bench.scala` reads it to clone repos, install tools, and run commands
- `aggregate.scala` reads it to discover valid benchmark types
- Run `scala-cli run run_bench.scala -- --list-benchmarks` to see available `repo-tool` pairs

## Build file overlays

Build files for repos that don't ship them natively live under `build-files/<repo>/<tool>/`. The runner copies all files from this directory into the cloned repo root before benchmarking.

When adding a new overlay:
1. Ensure `sources` paths in the build file match the **cloned repo's actual source layout** — not the upstream repo's layout if they differ
2. Module IDs used in benchmark commands (`-m mockito-core` in Deder, `:mockito-core:compileJava` in Gradle) must match the build file's module names exactly
3. Add the tool's chart color in `bench.scala` `ToolColors` map

## Mockito repo layout

The cloned mockito v5.23.0 uses the **upstream monorepo layout**:
- Root module sources: `src/main/java/`
- Subproject sources: `subprojects/<name>/src/main/java/`
- Build files are placed at the repo root and reference these paths directly

The mill build at `build-files/mockito/mill/build.mill` was copied directly from the upstream mockito repo and is the reference for dependency versions/lists. Other mockito build files (deder) are translations of it.

## CI workflow

- **Every benchmark runs on a fresh runner** — one job per `repo-tool` pair
- PRs run in **smoke mode** (`BENCH_SMOKE=true`): warmup=0, runs=2 — for fast pipeline validation only
- Results upload even on partial failure (`if: always()`)
- Deployment to GitHub Pages only on pushes to `main`

## Deder specifics

- Deder creates a minimal `deder.pkl` (just `modules {}`) if none exists — the CI shutdown command uses this to connect to a running server
- Deder module IDs must match `^[.a-zA-Z0-9_-]+$` — use `mockito-core` not `mockitoCore`
- The `deder exec -t compile` command compiles all `JavaModule` instances; `JavaTestModule` instances are only compiled by `-t test`
- Deder does not support: custom resource generation, cross-module test class dependencies, source file filtering, dynamic classpath resolution (`-Xbootclasspath/a`)

## Result files

Results are hyperfine JSON at `results/<repo>/<tool>-<scenario>.json`. The aggregator expects filenames in exactly this format. Avoid changing the naming convention.
