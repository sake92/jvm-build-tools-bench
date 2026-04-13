#!/usr/bin/env python3
"""
aggregate.py — aggregate hyperfine benchmark results for jvm-build-tools-bench

Expected input layout (produced by run_bench.sh):
  <results-dir>/
    <repo>/
      <tool>-<scenario>.json   (hyperfine JSON export)

Repos and tools are discovered dynamically from the directory structure and
filenames. Scenario names are matched against a small canonical set so
multi-word names like `clean-compile` stay stable.

Outputs (written to --output-dir/<repo>/<scenario>/):
  summary.json  structured data: mean/stddev/min/max per tool for one scenario
  summary.md    markdown comparison table for one scenario
  chart.svg     horizontal bar chart for one scenario (requires matplotlib)

Also writes --output-dir/index.html: per-repo collapsible sections with lazy-loaded SVGs.

ASCII bar charts are also printed to stdout (useful in CI logs, no extra deps).

Usage:
  python aggregate.py --results-dir results/ [--output-dir aggregated/]
"""

import argparse
import datetime
import json
import sys
from collections import defaultdict
from pathlib import Path

from tool_colors import get_tool_color

SCENARIO_ALIASES = {
    "clean-compile": "clean-compile",
    "incremental-compile": "incremental-compile",
    "clean": "clean-compile",
    "incremental": "incremental-compile",
}


# ── CLI ──────────────────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--results-dir", required=True,
                   help="Directory with <repo>/<tool>-<scenario>.json files")
    p.add_argument("--output-dir", default="aggregated",
                   help="Where to write output files (default: aggregated/)")
    return p.parse_args()


# ── Data loading ──────────────────────────────────────────────────────────────

def parse_result_stem(stem: str) -> tuple[str, str] | None:
    for suffix, scenario in sorted(
        SCENARIO_ALIASES.items(), key=lambda entry: len(entry[0]), reverse=True
    ):
        needle = f"-{suffix}"
        if stem.endswith(needle):
            tool = stem[: -len(needle)]
            if tool:
                return tool, scenario
    return None

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

        parsed = parse_result_stem(stem)
        if parsed is None:
            print(f"  Skipping {path} (unknown scenario suffix)", file=sys.stderr)
            continue

        tool, scenario = parsed
        get_tool_color(tool)

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

def write_summary_json(repo: str, scenario: str, entries: list[dict], output_dir: Path):
    out = output_dir / "summary.json"
    out.write_text(json.dumps({
        "repo": repo,
        "scenario": scenario,
        "entries": entries,
    }, indent=2))
    print(f"  Wrote {out}")


# ── Markdown table ────────────────────────────────────────────────────────────

def fmt_ms(seconds: float) -> str:
    return f"{seconds * 1000:.1f} ms"

def write_summary_md(repo: str, scenario: str, entries: list[dict], output_dir: Path):
    lines = [f"# JVM Build Tools Benchmark — {repo} — {scenario.replace('-', ' ').title()}\n"]
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

    bars = ax.barh(
        tools, means,
        xerr=errors,
        capsize=4,
        color=[get_tool_color(tool) for tool in tools],
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
    out = output_dir / "chart.svg"
    fig.savefig(out, format="svg", bbox_inches="tight")
    plt.close(fig)
    print(f"  Wrote {out}")


# ── HTML index ───────────────────────────────────────────────────────────────

_HTML_CSS = """
  body { font-family: system-ui, sans-serif; max-width: 960px; margin: 2rem auto; padding: 0 1rem; color: #222; }
  h1 { font-size: 1.6rem; }
  details { border: 1px solid #ddd; border-radius: 6px; margin: 1rem 0; }
  summary { cursor: pointer; padding: 0.75rem 1rem; font-size: 1.1rem; font-weight: 600;
            background: #f5f5f5; border-radius: 6px; list-style: none; }
  summary::-webkit-details-marker { display: none; }
  summary::before { content: "▶ "; font-size: 0.8em; color: #666; }
  details[open] summary::before { content: "▼ "; }
  .repo-body { padding: 1rem; }
  .charts { display: flex; flex-wrap: wrap; gap: 1rem; }
  figure { margin: 0; flex: 1 1 400px; }
  figure img { width: 100%; height: auto; border: 1px solid #eee; border-radius: 4px; }
  figcaption { font-size: 0.85rem; color: #555; margin-top: 0.3rem; text-align: center; }
  .links { margin-top: 0.75rem; font-size: 0.9rem; }
  .links a { margin-right: 1rem; color: #0066cc; }
  footer { margin-top: 2rem; font-size: 0.8rem; color: #999; }
"""

def write_index_html(all_data: dict, output_dir: Path):
    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    sections = []

    for i, (repo, data) in enumerate(sorted(all_data.items())):
        open_attr = " open" if i == 0 else ""
        charts_html = "\n".join(
            f'      <figure>\n'
            f'        <img src="{repo}/{scenario}/chart.svg" alt="{scenario} chart" loading="lazy">\n'
            f'        <figcaption>{scenario.replace("-", " ").title()} '
            f'(<a href="{repo}/{scenario}/summary.json">json</a>, '
            f'<a href="{repo}/{scenario}/summary.md">md</a>)</figcaption>\n'
            f'      </figure>'
            for scenario in sorted(data.keys())
        )
        sections.append(f"""\
  <details{open_attr}>
    <summary>{repo}</summary>
    <div class="repo-body">
      <div class="charts">
{charts_html}
      </div>
    </div>
  </details>""")

    html = f"""\
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>JVM Build Tools Benchmark</title>
  <style>{_HTML_CSS}  </style>
</head>
<body>
  <h1>JVM Build Tools Benchmark</h1>
  <p>Compilation time comparison across build tools on real-world repos.
     Each chart shows mean time (lower is better).</p>

{"".join(chr(10) + s for s in sections)}

  <footer>Generated {timestamp}</footer>
</body>
</html>
"""
    out = output_dir / "index.html"
    out.write_text(html)
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
    try:
        all_data = load_results(results_dir)
    except ValueError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

    if not all_data:
        print("No benchmark results found.", file=sys.stderr)
        sys.exit(1)

    for repo, data in all_data.items():
        print(f"\n=== {repo} ===")
        for scenario, entries in data.items():
            scenario_out = output_dir / repo / scenario
            scenario_out.mkdir(parents=True, exist_ok=True)
            write_summary_json(repo, scenario, entries, scenario_out)
            write_summary_md(repo, scenario, entries, scenario_out)
            ascii_bar_chart(repo, scenario, entries)
            write_svg_chart(repo, scenario, entries, scenario_out)

    output_dir.mkdir(parents=True, exist_ok=True)
    write_index_html(all_data, output_dir)
    print("\nDone.")


if __name__ == "__main__":
    main()
