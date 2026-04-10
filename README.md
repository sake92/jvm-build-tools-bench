# jvm-build-tools-bench

Benchmarks comparing JVM build tools (Maven, Gradle, Mill, sbt, …) on real-world open-source repositories using [hyperfine](https://github.com/sharkdp/hyperfine).

Two scenarios are measured per tool:

| Scenario | Description |
|---|---|
| **clean compile** | Full rebuild from scratch (`clean` + compile) |
| **incremental compile** | Touch a set of source files, recompile |

Results are exported as hyperfine JSON and uploaded as GitHub Actions artifacts.  
After all tools finish, an `aggregate` job produces a unified comparison.

---

## How it works

1. **`benchmarks.yaml`** — single source of truth: which repos to clone, which tools to run, and what commands to execute.
2. **`run_bench.sh`** — bash runner (requires `yq` + `hyperfine`). 
Clones the target repo, optionally overlays extra build files, runs setup, then benchmarks clean and incremental compilation.
3. **`aggregate.py`** — aggregates all per-tool hyperfine JSON results into a unified comparison (see [Results](#results)).
4. **`.github/workflows/bench.yml`** — CI workflow. 
The tool list is read dynamically from `benchmarks.yaml` to build a matrix; each tool runs on a **separate fresh runner** for maximum isolation. 
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
# benchmark Maven
./run_bench.sh --tool maven

# benchmark Mill, store results in a custom directory
./run_bench.sh --tool mill --results-dir /tmp/mill-results

# keep cloned repos across runs (speeds up repeated runs)
./run_bench.sh --tool gradle --repos-dir ~/bench-repos
```

Results land in `results/` by default:

```
results/
├── maven-clean.json
├── maven-incremental.json
├── gradle-clean.json
└── ...
```

Each file is a standard hyperfine JSON export — use `cat results/maven-clean.json | jq .results[0].mean` to read the mean time in seconds.

---

## Results

Results are published to **GitHub Pages** after each CI run:
> `https://<your-org>.github.io/jvm-build-tools-bench/`

The page has one collapsible section per repo with SVG bar charts for each scenario.

You can also download the **`results-aggregated`** artifact from the Actions tab. It contains:

| File | Contents |
|---|---|
| `index.html` | Static results page (charts + links) |
| `<repo>/summary.json` | Mean / stddev / min / max per tool per scenario |
| `<repo>/summary.md` | Markdown comparison table (one section per scenario) |
| `<repo>/chart-<scenario>.svg` | Horizontal bar chart per scenario |

To run aggregation locally after collecting results:

```bash
pip install matplotlib
python aggregate.py --results-dir results/ --output-dir aggregated/
```

ASCII bar charts are also printed to stdout during aggregation (handy for reading directly in CI logs).

> **Note:** Enable GitHub Pages in your repo settings with source branch `gh-pages` for the published URL to work.

---

## Adding a new tool

1. Add an entry to `benchmarks.yaml` (copy an existing one as a template).
2. If the target repo doesn't have a native build file for this tool, add it under `build-files/<repo>/<tool>/`.
3. That's it — GitHub Actions will pick it up automatically on the next run.

---

## Benchmark targets

| Repo | URL |
|---|---|
| TheAlgorithms/Java | https://github.com/TheAlgorithms/Java |
| TheAlgorithms/Scala | https://github.com/TheAlgorithms/Scala |
