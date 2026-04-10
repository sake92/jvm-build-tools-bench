#!/usr/bin/env bash
# run_bench.sh — run JVM build tool benchmarks defined in benchmarks.yaml
#
# Usage:
#   ./run_bench.sh --tool <name> [--results-dir <dir>] [--repos-dir <dir>]
#
# Prerequisites:
#   - hyperfine  (https://github.com/sharkdp/hyperfine)
#   - yq >= 4    (https://github.com/mikefarah/yq)
#   - git, java, and the build tool itself

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="$SCRIPT_DIR/benchmarks.yaml"

# ── defaults ──────────────────────────────────────────────────────────────
TOOL=""
RESULTS_DIR="$SCRIPT_DIR/results"
REPOS_DIR="$SCRIPT_DIR/tmp/repos"

# ── argument parsing ───────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tool)        TOOL="$2";        shift 2 ;;
    --results-dir) RESULTS_DIR="$2"; shift 2 ;;
    --repos-dir)   REPOS_DIR="$2";   shift 2 ;;    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$TOOL" ]]; then
  echo "Usage: $0 --tool <name> [--results-dir <dir>] [--repos-dir <dir>]" >&2
  exit 1
fi

# Resolve to absolute paths so they remain valid after pushd into the repo dir
RESULTS_DIR="$(mkdir -p "$RESULTS_DIR" && cd "$RESULTS_DIR" && pwd)"
REPOS_DIR="$(mkdir -p "$REPOS_DIR" && cd "$REPOS_DIR" && pwd)"

# ── helpers ───────────────────────────────────────────────────────────────
yq_tool() { yq ".tools[] | select(.name == \"$TOOL\") | $1" "$CONFIG"; }
yq_repo() { local repo_name="$1"; yq ".repos[] | select(.name == \"$repo_name\") | $2" "$CONFIG"; }

# Return empty string instead of "null" for optional fields
yq_opt() { local val; val=$(yq_tool "$1"); [[ "$val" == "null" ]] && echo "" || echo "$val"; }

die() { echo "ERROR: $*" >&2; exit 1; }

# ── read tool config ───────────────────────────────────────────────────────
REPO_NAME=$(yq_tool ".repo")
[[ "$REPO_NAME" == "null" || -z "$REPO_NAME" ]] && die "Tool '$TOOL' not found in $CONFIG"

REPO_URL=$(yq_repo "$REPO_NAME" ".url")
REPO_REF=$(yq_repo "$REPO_NAME" ".ref")

SETUP=$(yq_opt ".setup")
COMPILE_CLEAN=$(yq_tool ".compile_clean")
COMPILE_INCR=$(yq_tool ".compile_incremental")
SHUTDOWN=$(yq_opt ".shutdown")

WARMUP=$(yq_tool ".hyperfine_warmup")
RUNS=$(yq_tool ".hyperfine_runs")

# incremental_files as a bash array (yq outputs one per line with -r style)
mapfile -t INCR_FILES < <(yq ".tools[] | select(.name == \"$TOOL\") | .incremental_files[]" "$CONFIG")

echo "=== JVM Build Tools Bench ==="
echo "Tool:     $TOOL"
echo "Repo:     $REPO_NAME ($REPO_URL @ $REPO_REF)"
echo "Results:  $RESULTS_DIR"
echo ""

# ── clone / update the target repo ────────────────────────────────────────
REPO_DIR="$REPOS_DIR/$REPO_NAME"
mkdir -p "$REPOS_DIR"

# Detect whether ref is a full commit SHA (40 hex chars) or a branch/tag name.
# SHA refs can't be used with --branch or origin/<ref>, so we clone the default
# branch and then fetch + checkout the specific commit.
is_sha() { [[ "$1" =~ ^[0-9a-f]{40}$ ]]; }

if [[ -d "$REPO_DIR/.git" ]]; then
  echo ">>> Updating $REPO_NAME to $REPO_REF..."
  git -C "$REPO_DIR" fetch --quiet origin
  if is_sha "$REPO_REF"; then
    # Fetch the specific commit (not always reachable after a shallow fetch)
    git -C "$REPO_DIR" fetch --quiet origin "$REPO_REF" || true
    git -C "$REPO_DIR" checkout --quiet --detach "$REPO_REF"
  else
    git -C "$REPO_DIR" checkout --quiet "$REPO_REF"
    git -C "$REPO_DIR" reset --quiet --hard "origin/$REPO_REF"
  fi
else
  echo ">>> Cloning $REPO_NAME..."
  if is_sha "$REPO_REF"; then
    # Clone default branch, then checkout the pinned commit
    git clone --quiet "$REPO_URL" "$REPO_DIR"
    git -C "$REPO_DIR" checkout --quiet --detach "$REPO_REF"
  else
    git clone --quiet --branch "$REPO_REF" --depth 1 "$REPO_URL" "$REPO_DIR"
  fi
fi

# ── overlay build files if present (build-files/<repo>/<tool>/) ──────────
OVERLAY_DIR="$SCRIPT_DIR/build-files/$REPO_NAME/$TOOL"
if [[ -d "$OVERLAY_DIR" ]]; then
  echo ">>> Overlaying build files from $OVERLAY_DIR..."
  cp -r "$OVERLAY_DIR/." "$REPO_DIR/"
fi

# ── prepare results directory ──────────────────────────────────────────────
mkdir -p "$RESULTS_DIR/$REPO_NAME"
CLEAN_JSON="$RESULTS_DIR/$REPO_NAME/${TOOL}-clean-compile.json"
INCR_JSON="$RESULTS_DIR/$REPO_NAME/${TOOL}-incremental-compile.json"

# ── run setup (dep download / daemon warm-up) ──────────────────────────────
pushd "$REPO_DIR" > /dev/null

if [[ -n "$SETUP" ]]; then
  echo ">>> Setup: $SETUP"
  eval "$SETUP"
fi

# ── benchmark: clean compile ──────────────────────────────────────────────
echo ""
echo ">>> Benchmarking clean compile (warmup=$WARMUP, runs=$RUNS)..."
hyperfine \
  --shell bash \
  --warmup "$WARMUP" \
  --runs "$RUNS" \
  --export-json "$CLEAN_JSON" \
  "$COMPILE_CLEAN"

# ── benchmark: incremental compile ───────────────────────────────────────
echo ""
echo ">>> Touching ${#INCR_FILES[@]} file(s) for incremental run..."
for f in "${INCR_FILES[@]}"; do
  touch "$REPO_DIR/$f"
done

echo ">>> Benchmarking incremental compile (warmup=1, runs=$RUNS)..."
hyperfine \
  --shell bash \
  --warmup 1 \
  --runs "$RUNS" \
  --export-json "$INCR_JSON" \
  "$COMPILE_INCR"

popd > /dev/null

# ── shutdown ──────────────────────────────────────────────────────────────
if [[ -n "$SHUTDOWN" ]]; then
  echo ""
  echo ">>> Shutdown: $SHUTDOWN"
  pushd "$REPO_DIR" > /dev/null
  eval "$SHUTDOWN" || true
  popd > /dev/null
fi

echo ""
echo "=== Done ==="
echo "Results written to:"
echo "  $CLEAN_JSON"
echo "  $INCR_JSON"
