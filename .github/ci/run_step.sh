#!/usr/bin/env bash
# TEMPORARY (removed with the workflow).
# usage: run_step.sh <id> <gradle|full> <command...>
# Runs the command, keeps its output in $LOGS/<id>.log, turns it into check-run annotations via
# digest.py (the only channel that can be read back from the authoring sandbox) and exits with the
# command's own exit code.
set -u
id="$1"; mode="$2"; shift 2
here="$(cd "$(dirname "$0")" && pwd)"
LOGS="${LOGS:-/tmp/ci-logs}"
mkdir -p "$LOGS"
log="$LOGS/$id.log"

"$@" 2>&1 | tee "$log"
rc=${PIPESTATUS[0]}

python3 "$here/digest.py" step "$id" "$mode" "$rc" "$log" || true
exit "$rc"
