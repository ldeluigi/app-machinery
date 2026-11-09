#!/usr/bin/env bash
# Waits, before an agent starts on a branch, for another run's agent whose commits aren't published yet. That agent's
# job (<job>) is over, which let this one start (they share a concurrency group), but the job that pushes its commits
# ("<job>: publish") may still be on its way: started now, this agent would work on a branch without them, and its own
# commits couldn't be pushed after them.
# Any run counts, whichever issue or branch it works on: the wait is the minute a publish job takes.
# Writes "waited=true" to GITHUB_OUTPUT when it waited, for the caller to read the branch again.
# Usage: wait-for-publish.sh <job's name>
# Needs GH_TOKEN (actions: read), GH_REPO and GITHUB_RUN_ID; WAIT_SECONDS (600) and POLL_SECONDS (15) change the wait.
set -euo pipefail

job="$1"
limit="${WAIT_SECONDS:-600}"
pause="${POLL_SECONDS:-15}"

# The other runs still going where <job> ended (it ran: not skipped) and its publish job hasn't. A called workflow's
# jobs are named "<the caller's job> / <name>"
pending() {
  local run
  for run in $(gh api "repos/$GH_REPO/actions/runs?per_page=100" \
    --jq ".workflow_runs[] | select(.status != \"completed\" and .id != $GITHUB_RUN_ID) | .id"); do
    gh api "repos/$GH_REPO/actions/runs/$run/jobs?per_page=100" \
      --jq '.jobs[] | [.name, .status, .conclusion // ""] | @tsv' |
      awk -F '\t' -v job="$job" -v run="$run" '
        { name = $1; sub(/^.* \/ /, "", name) }
        name == job && $2 == "completed" && $3 != "skipped" { worked = 1 }
        name == job ": publish" && $2 == "completed" { published = 1 }
        END { if (worked && !published) print run }'
  done
}

waited=0
# A list that can't be read ends the wait: the agent starts, as it would have without this step
while runs="$(pending | paste -sd ' ' -)" && [ -n "$runs" ]; do
  if [ "$waited" -ge "$limit" ]; then
    echo "::warning::Run $runs still hasn't published its agent's commits after $((limit / 60)) minutes: starting without them."
    break
  fi
  echo "Waiting for run $runs to publish its agent's commits."
  sleep "$pause"
  waited=$((waited + pause))
done
if [ "$waited" -gt 0 ] && [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "waited=true" >> "$GITHUB_OUTPUT"
fi
