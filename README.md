# jvm-build-tools-bench

> Just a naive mini benchmark!

Benchmarks comparing JVM build tools (Maven, Gradle, Mill, bleep, sbt, …) on real-world open-source repositories using [hyperfine](https://github.com/sharkdp/hyperfine).

Multiple benchmark types are measured per tool — each defined in `benchmarks.yaml` as a named entry under `hyperfine.benchmark_types`. Typical types include:

| Benchmark type | Description |
|---|---|
| **compile-clean** | Full rebuild from scratch (clean + compile) |
| **compile-incremental** | Touch a set of source files, recompile |
| **test** | Run all tests |

Additional types (e.g., `compile-module-abc`) can be added without changing any Scala code.

Results are exported as hyperfine JSON and uploaded as GitHub Actions artifacts.  
After all tools finish, an `aggregate` job produces a unified comparison.

---

## How it works

1. **`benchmarks.yaml`** — single source of truth: which repos to clone, which repo + build tool benchmarks to run, what commands to execute, and the shared hyperfine settings for each benchmark scenario.
2. **`run_bench.scala`** — Scala CLI runner. Parses `benchmarks.yaml` directly (no `yq` needed), clones the target repo, optionally overlays extra build files, runs setup, then benchmarks each benchmark type defined for the tool.
3. **`aggregate.scala`** — scales all per-tool hyperfine JSON results into a unified comparison with SVG charts, markdown tables, and a static HTML page.
4. **`.github/workflows/bench.yml`** — CI workflow. The benchmark list is read dynamically from `benchmarks.yaml` to build a matrix; each repo + build tool combination runs on a **separate fresh runner** for maximum isolation. An `aggregate` job runs after all tools finish.

Shared code lives in **`bench.scala`** — config model, YAML parsing, git operations, hyperfine runner, and chart colors.

Build files that a repo doesn't ship natively (e.g. a `build.mill` for a Maven-only repo) are stored under `build-files/<repo>/<tool>/` and overlaid before benchmarking.

---

## Prerequisites (local run)

| Tool | Install |
|---|---|
| JDK 21 | [Temurin](https://adoptium.net/) or `sdk install java 21-tem` |
| [scala-cli](https://scala-cli.virtuslab.org/) | `brew install scala-cli` / `sdk install scala-cli` / [releases page](https://github.com/VirtusLab/scala-cli/releases) |
| hyperfine | `cargo install hyperfine` or [releases page](https://github.com/sharkdp/hyperfine/releases) |
| Build tool | Maven / Mill / Deder / sbt — see `benchmarks.yaml` for `install` commands |

All scripting is in Scala, charts use JFreeChart.

---

## Running locally

```bash
# list all available benchmarks
scala-cli run run_bench.scala -- --list-benchmarks

# benchmark Maven on java-algorithms
scala-cli run run_bench.scala -- --benchmark java-algorithms-maven
```

Results land in `results/` by default:

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

Each file is a standard hyperfine JSON export — use `cat results/java-algorithms/maven-compile-clean.json | jq .results[0].mean` to read the mean time in seconds.

---

## Results

Results are published to **GitHub Pages** after each CI run:
> `https://sake92.github.io/jvm-build-tools-bench/`

The page has one collapsible section per repo with separate SVG charts and report links for each scenario.

You can also download the **`results-aggregated`** artifact from the Actions tab. It contains:

| File | Contents |
|---|---|
| `index.html` | Static results page (charts + links) |
| `<repo>/<scenario>/summary.json` | Mean / stddev / min / max per tool for one scenario |
| `<repo>/<scenario>/summary.md` | Markdown comparison table for one scenario |
| `<repo>/<scenario>/chart.svg` | Horizontal bar chart for one scenario |

To run aggregation locally after collecting results:

```bash
scala-cli run aggregate.scala -- --results-dir results/ --output-dir aggregated/ --benchmarks-yaml benchmarks.yaml
```

ASCII bar charts are also printed to stdout during aggregation (handy for reading directly in CI logs).

> **Note:** Enable GitHub Pages in your repo settings with source branch `gh-pages` for the published URL to work.

---

## Adding a new tool

1. Add an entry to `benchmarks.yaml` with a `repo` and `build_tool_name` (copy an existing one as a template).
2. If the target repo doesn't have a native build file for this tool, add it under `build-files/<repo>/<tool>/`.
3. Add the tool's chart color in `bench.scala`'s `ToolColors` map so aggregated SVGs stay consistent.
4. That's it — GitHub Actions will pick it up automatically on the next run.

---

## Benchmark targets

| Repo | URL |
|---|---|
| TheAlgorithms/Java | https://github.com/TheAlgorithms/Java |
| TheAlgorithms/Scala | https://github.com/TheAlgorithms/Scala |
