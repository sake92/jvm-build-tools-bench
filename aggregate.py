#!/usr/bin/env python3
"""
aggregate.py — aggregate hyperfine benchmark results for jvm-build-tools-bench

Expected input layout (produced by run_bench.sh):
  <results-dir>/
    <repo>/
      <tool>-<scenario>.json   (hyperfine JSON export)

Repos, tools, and scenarios are all discovered dynamically from the directory
structure and filenames — no hardcoding needed.

Outputs (written to --output-dir/<repo>/):
  summary.json          structured data: mean/stddev/min/max per scenario per tool
  summary.md            markdown comparison tables, one section per scenario
  chart-<scenario>.svg  horizontal bar chart per scenario (requires matplotlib)

ASCII bar charts are also printed to stdout (useful in CI logs, no extra deps).

Usage:
  python aggregate.py --results-dir results/ [--output-dir aggregated/]
"""

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path


# ── CLI ──────────────────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--results-dir", required=True,
                   help="Directory with <repo>/<tool>-<scenario>.json files")
    p.add_argument("--output-dir", default="aggregated",
                   help="Where to write output files (default: aggregated/)")
    return p.parse_args()


# ── Data loading ──────────────────────────────────────────────────────────────

def load_results(results_dir: Path) -> dict[str, dict[str, list[dict]]]:
    """
    Walk results_dir and parse every <repo>/<tool>-<scenario>.json.
    Returns { repo: { scenario: [ {tool, mean, stddev, min, max, median}, ... ] } }
    sorted by mean within each scenario.
    Repo is the immediate parent directory name of each JSON file.
    """
    # { repo: { scenario: { tool: entry } } }
    by_repo: dict[str, dict[str, dict[str, dict]]] = defaultdict(lambda: defaultdict(dict))

    for path in sorted(results_dir.rglob("*.json")):
        repo = path.parent.name   # immediate parent dir = repo name
        stem = path.stem          # e.g. "maven-clean"

        if "-" not in stem:
            print(f"  Skipping {path} (no '-' in stem)", file=sys.stderr)
            continue

        tool, scenario = stem.rsplit("-", 1)

        try:
            data = json.loads(path.read_text())
        except (json.JSONDecodeError, OSError) as e:
            print(f"  Warning: could not read {path}: {e}", file=sys.stderr)
            continue

        results = data.get("results", [])
        if not results:
            print(f"  Warning: no results in {path}", file=sys.stderr)
            continue

        r = results[0]
        by_repo[repo][scenario][tool] = {
            "tool":   tool,
            "mean":   r.get("mean", 0.0),
            "stddev": r.get("stddev", 0.0),
            "min":    r.get("min", 0.0),
            "max":    r.get("max", 0.0),
            "median": r.get("median", r.get("mean", 0.0)),
        }

    # Sort each scenario's entries by mean (fastest first)
    return {
        repo: {
            scenario: sorted(entries.values(), key=lambda e: e["mean"])
            for scenario, entries in sorted(scenarios.items())
        }
        for repo, scenarios in sorted(by_repo.items())
    }


# ── JSON summary ──────────────────────────────────────────────────────────────

def write_summary_json(data: dict, output_dir: Path):
    out = output_dir / "summary.json"
    out.write_text(json.dumps(data, indent=2))
    print(f"  Wrote {out}")


# ── Markdown table ────────────────────────────────────────────────────────────

def fmt_ms(seconds: float) -> str:
    return f"{seconds * 1000:.1f} ms"

def write_summary_md(repo: str, data: dict, output_dir: Path):
    lines = [f"# JVM Build Tools Benchmark — {repo}\n"]

    for scenario, entries in data.items():
        lines.append(f"## {scenario.replace('-', ' ').title()}\n")
        lines.append("| Tool | Mean | Stddev | Min | Max |")
        lines.append("|------|-----:|-------:|----:|----:|")
        for e in entries:
            lines.append(
                f"| {e['tool']} "
                f"| {fmt_ms(e['mean'])} "
                f"| ± {fmt_ms(e['stddev'])} "
                f"| {fmt_ms(e['min'])} "
                f"| {fmt_ms(e['max'])} |"
            )
        lines.append("")

    out = output_dir / "summary.md"
    out.write_text("\n".join(lines))
    print(f"  Wrote {out}")


# ── ASCII bar chart ───────────────────────────────────────────────────────────

BAR_WIDTH = 40
BAR_CHAR = "█"

def ascii_bar_chart(repo: str, scenario: str, entries: list[dict]):
    if not entries:
        return

    max_mean = max(e["mean"] for e in entries)
    max_tool_len = max(len(e["tool"]) for e in entries)

    print(f"\n  [{repo}] {scenario} (mean time, lower is better)")
    for e in entries:
        bar_len = int(round((e["mean"] / max_mean) * BAR_WIDTH)) if max_mean > 0 else 0
        bar = BAR_CHAR * bar_len
        label = e["tool"].ljust(max_tool_len)
        mean_ms = e["mean"] * 1000
        stddev_ms = e["stddev"] * 1000
        print(f"    {label}  {bar:<{BAR_WIDTH}}  {mean_ms:8.1f} ms ± {stddev_ms:.1f}")


# ── SVG / matplotlib chart ────────────────────────────────────────────────────

def write_svg_chart(repo: str, scenario: str, entries: list[dict], output_dir: Path):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print(f"  matplotlib not available — skipping SVG chart for {scenario}", file=sys.stderr)
        return

    tools = list(reversed([e["tool"] for e in entries]))
    means = list(reversed([e["mean"] * 1000 for e in entries]))
    errors = list(reversed([e["stddev"] * 1000 for e in entries]))

    fig_height = max(3, len(tools) * 0.6 + 1.2)
    fig, ax = plt.subplots(figsize=(10, fig_height))

    colors = plt.cm.tab10.colors
    bars = ax.barh(
        tools, means,
        xerr=errors,
        capsize=4,
        color=[colors[i % len(colors)] for i in range(len(tools))],
        edgecolor="white",
        linewidth=0.5,
        error_kw={"elinewidth": 1.2, "ecolor": "#555"},
    )

    for bar, mean in zip(bars, means):
        ax.text(
            bar.get_width() + max(means) * 0.01,
            bar.get_y() + bar.get_height() / 2,
            f"{mean:.1f} ms",
            va="center", ha="left", fontsize=9,
        )

    ax.set_xlabel("Mean time (ms) — lower is better")
    ax.set_title(f"{repo}: {scenario.replace('-', ' ').title()}")
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.set_xlim(0, max(means) * 1.2)

    plt.tight_layout()
    out = output_dir / f"chart-{scenario}.svg"
    fig.savefig(out, format="svg", bbox_inches="tight")
    plt.close(fig)
    print(f"  Wrote {out}")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    args = parse_args()
    results_dir = Path(args.results_dir)
    output_dir = Path(args.output_dir)

    if not results_dir.exists():
        print(f"Error: results directory not found: {results_dir}", file=sys.stderr)
        sys.exit(1)

    print(f"Scanning {results_dir} for benchmark results...")
    all_data = load_results(results_dir)

    if not all_data:
        print("No benchmark results found.", file=sys.stderr)
        sys.exit(1)

    for repo, data in all_data.items():
        print(f"\n=== {repo} ===")
        repo_out = output_dir / repo
        repo_out.mkdir(parents=True, exist_ok=True)

        write_summary_json(data, repo_out)
        write_summary_md(repo, data, repo_out)

        for scenario, entries in data.items():
            ascii_bar_chart(repo, scenario, entries)
            write_svg_chart(repo, scenario, entries, repo_out)

    print("\nDone.")


if __name__ == "__main__":
    main()
