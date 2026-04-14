# jvm-build-tools-bench

> Just a naive mini benchmark!

Benchmarks comparing JVM build tools (Maven, Gradle, Mill, sbt, …) on real-world open-source repositories using [hyperfine](https://github.com/sharkdp/hyperfine).

Two scenarios are measured per tool:

| Scenario | Description |
|---|---|
| **clean compile** | Full rebuild from scratch (`clean` + compile) |
| **incremental compile** | Touch a set of source files, recompile |
| **test all** | Run all tests |

Results are exported as hyperfine JSON and uploaded as GitHub Actions artifacts.  
After all tools finish, an `aggregate` job produces a unified comparison.

---

## How it works

1. **`benchmarks.yaml`** — single source of truth: which repos to clone, which repo + build tool benchmarks to run, what commands to execute, and the shared hyperfine settings for each benchmark scenario.
2. **`run_bench.sh`** — bash runner (requires `yq` + `hyperfine`). 
Clones the target repo, optionally overlays extra build files, runs setup, then benchmarks clean and incremental compilation.
3. **`aggregate.py`** — aggregates all per-tool hyperfine JSON results into a unified comparison (see [Results](#results)).
4. **`.github/workflows/bench.yml`** — CI workflow. 
The benchmark list is read dynamically from `benchmarks.yaml` to build a matrix; each repo + build tool combination runs on a **separate fresh runner** for maximum isolation. 
An `aggregate` job runs after all tools finish.

Build files that a repo doesn't ship natively (e.g. a `build.mill` for a Maven-only repo) are stored under `build-files/<repo>/<tool>/` and overlaid before benchmarking.

---

## Prerequisites (local run)

| Tool | Install |
|---|---|
| JDK 21 | [Temurin](https://adoptium.net/) or `sdk install java 21-tem` |
| hyperfine | `cargo install hyperfine` or [releases page](https://github.com/sharkdp/hyperfine/releases) |
| yq ≥ 4 | `sudo snap install yq` or [releases page](https://github.com/mikefarah/yq/releases) |
| Python 3 + matplotlib | `pip install matplotlib` (only needed for SVG charts) |
| Build tool | Maven / Gradle / Mill / sbt — see `benchmarks.yaml` for `install` commands |

---

## Running locally

```bash
# benchmark Maven on java-algorithms
./run_bench.sh --benchmark java-algorithms-maven

# benchmark Mill, store results in a custom directory
./run_bench.sh --benchmark java-algorithms-mill --results-dir /tmp/mill-results

# keep cloned repos across runs (speeds up repeated runs)
./run_bench.sh --benchmark java-algorithms-gradle --repos-dir ~/bench-repos
```

Results land in `results/` by default:

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

Each file is a standard hyperfine JSON export — use `cat results/java-algorithms/maven-clean-compile.json | jq .results[0].mean` to read the mean time in seconds.

---

## Results

Results are published to **GitHub Pages** after each CI run:
> `https://<your-org>.github.io/jvm-build-tools-bench/`

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
pip install matplotlib
python aggregate.py --results-dir results/ --output-dir aggregated/
```

ASCII bar charts are also printed to stdout during aggregation (handy for reading directly in CI logs).

> **Note:** Enable GitHub Pages in your repo settings with source branch `gh-pages` for the published URL to work.

---

## Adding a new tool

1. Add an entry to `benchmarks.yaml` with a `repo` and `build_tool_name` (copy an existing one as a template).
2. If the target repo doesn't have a native build file for this tool, add it under `build-files/<repo>/<tool>/`.
3. Add the tool's chart color in `tool_colors.py` so aggregated SVGs stay consistent.
4. That's it — GitHub Actions will pick it up automatically on the next run.

---

## Benchmark targets

| Repo | URL |
|---|---|
| TheAlgorithms/Java | https://github.com/TheAlgorithms/Java |
| TheAlgorithms/Scala | https://github.com/TheAlgorithms/Scala |
