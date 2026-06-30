#!/usr/bin/env bash
#
# remote-build.sh - Trigger the Translation CI workflow on GitHub Actions and
# stream the result locally, using ~0 local memory (no Gradle/JVM runs here).
#
# This is the "Level R" remote-compiler lane for the overlay translation
# refactor: GitHub's runner does the heavy AGP/Kotlin/Compose build, while this
# script only triggers it, watches it, and pulls artifacts back.
#
# Usage:
#   scripts/remote-build.sh [options]
#
# Options:
#   -p, --push            Trigger by pushing the current branch instead of
#                         workflow_dispatch (default: dispatch, no git noise).
#   -d, --download        Download artifacts when the run ends
#                         (APK on success, test report on failure).
#   -r, --ref <branch>    Branch to run against (default: current branch).
#   -w, --workflow <file> Workflow file to run (default: translation-ci.yml).
#       --no-watch        Trigger only; do not block waiting for completion.
#   -h, --help            Show this help and exit.
#
# Requires: gh (authenticated) and git.

set -euo pipefail

WORKFLOW="translation-ci.yml"
REF=""
DOWNLOAD=false
WATCH=true
TRIGGER="dispatch" # dispatch | push

if [[ -t 1 ]]; then
    C_OK=$'\e[32m'; C_ERR=$'\e[31m'; C_DIM=$'\e[2m'; C_BOLD=$'\e[1m'; C_RST=$'\e[0m'
else
    C_OK=""; C_ERR=""; C_DIM=""; C_BOLD=""; C_RST=""
fi

log()  { printf '%s==>%s %s\n' "$C_BOLD" "$C_RST" "$*"; }
ok()   { printf '%s[OK]%s %s\n' "$C_OK" "$C_RST" "$*"; }
die()  { printf '%serror:%s %s\n' "$C_ERR" "$C_RST" "$*" >&2; exit 1; }

usage() { sed -n '2,/^$/{/^#/!q;s/^# \{0,1\}//;p}' "$0"; exit 0; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        -p|--push)     TRIGGER="push"; shift ;;
        -d|--download) DOWNLOAD=true; shift ;;
        -r|--ref)      REF="${2:?--ref needs a branch}"; shift 2 ;;
        -w|--workflow) WORKFLOW="${2:?--workflow needs a file}"; shift 2 ;;
        --no-watch)    WATCH=false; shift ;;
        -h|--help)     usage ;;
        *)             die "unknown option: $1 (try --help)" ;;
    esac
done

# --- preflight -------------------------------------------------------------
command -v gh  >/dev/null 2>&1 || die "gh CLI not found. Install: https://cli.github.com/"
command -v git >/dev/null 2>&1 || die "git not found."
gh auth status >/dev/null 2>&1 || die "gh not authenticated. Run: gh auth login"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "not inside a git repository."

[[ -z "$REF" ]] && REF="$(git rev-parse --abbrev-ref HEAD)"

latest_run_id() {
    gh run list --workflow="$WORKFLOW" --branch "$REF" --limit 1 \
        --json databaseId --jq '.[0].databaseId // empty' 2>/dev/null || true
}

# --- trigger ---------------------------------------------------------------
prev_id="$(latest_run_id)"

if [[ "$TRIGGER" == "push" ]]; then
    log "Pushing '$REF' to origin to trigger $WORKFLOW ..."
    git push origin "$REF"
else
    log "Dispatching $WORKFLOW on '$REF' ..."
    gh workflow run "$WORKFLOW" --ref "$REF" \
        || die "dispatch failed (is the workflow on '$REF' and Actions enabled?)"
fi

# --- locate the new run ----------------------------------------------------
log "Waiting for a new run to register ${C_DIM}(up to ~120s)${C_RST} ..."
run_id=""
for _ in $(seq 1 40); do
    sleep 3
    cur_id="$(latest_run_id)"
    if [[ -n "$cur_id" && "$cur_id" != "$prev_id" ]]; then
        run_id="$cur_id"; break
    fi
done
[[ -z "$run_id" ]] && die "timed out waiting for a new run. Check: gh run list --workflow=$WORKFLOW"

run_url="$(gh run view "$run_id" --json url --jq '.url' 2>/dev/null || true)"
ok "run #$run_id started"
[[ -n "$run_url" ]] && printf '    %s%s%s\n' "$C_DIM" "$run_url" "$C_RST"

if ! $WATCH; then
    log "--no-watch set; not waiting. Tail it with: gh run watch $run_id"
    exit 0
fi

# --- watch -----------------------------------------------------------------
log "Streaming run #$run_id (Ctrl-C only stops watching, not the run) ..."
set +e
gh run watch "$run_id" --exit-status --interval 5
status=$?
set -e

if [[ $status -eq 0 ]]; then
    ok "run #$run_id succeeded"
else
    printf '%s[FAIL]%s run #%s concluded with failures - failed step logs:\n' "$C_ERR" "$C_RST" "$run_id"
    gh run view "$run_id" --log-failed || true
fi

# --- download --------------------------------------------------------------
if $DOWNLOAD; then
    outdir="build-artifacts/$run_id"
    mkdir -p "$outdir"
    log "Downloading artifacts -> $outdir"
    gh run download "$run_id" --dir "$outdir" 2>/dev/null \
        && ok "artifacts in $outdir" \
        || log "no artifacts on this run"
fi

exit $status
