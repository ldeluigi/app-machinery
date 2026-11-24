#!/usr/bin/env bash
# Prints the jobs of a workflow run (its latest attempt) that stopped on their own, one id per line: GitHub stopped them
# at their time limit (timeout-minutes), or their runner stopped answering or was taken away. GitHub reports such a
# job, and its run, as cancelled, just as it reports the ones a newer run (their concurrency group) or a person
# cancelled, which are best left alone. A job that ran out of time says so in its annotation. One whose runner went
# says only "The operation was canceled.", as a cancelled run's do; but cancelling a run cancels every job running
# then, so a job that was running when this one stopped, and went on to finish, shows the run wasn't cancelled. (A job
# whose condition holds when its run is cancelled, `always()`, would go on as well: the workflows have none.)
# Usage: timed-out.sh <run id>
# Needs GH_TOKEN (actions: read and checks: read) and GH_REPO.
set -euo pipefail

run="$1"
# id|conclusion|start|end of each job, the times in ISO 8601, UTC, which sort as text; | rather than a tab, which read
# would take as one with the empty fields around it
jobs="$(gh api "repos/$GH_REPO/actions/runs/$run/jobs?per_page=100" --paginate \
  --jq '.jobs[] | [.id, .conclusion // "", .started_at // "", .completed_at // ""] | map(tostring) | join("|")')"
while IFS='|' read -r job conclusion _ ended; do
  [ "$conclusion" = "cancelled" ] || continue
  # Read whole, then searched: under pipefail, grep ending the pipe early would count as a failure
  messages="$(gh api "repos/$GH_REPO/check-runs/$job/annotations?per_page=100" --paginate \
    --jq '.[] | select(.annotation_level == "failure") | .message')"
  if grep -q 'exceeded the maximum execution time' <<< "$messages"; then
    echo "$job"
  elif [ -n "$ended" ] && awk -F '|' -v job="$job" -v ended="$ended" '
    $1 != job && ($2 == "success" || $2 == "failure") && $3 != "" && $3 < ended && $4 > ended { went_on = 1 }
    END { exit !went_on }' <<< "$jobs"; then
    echo "$job"
  fi
done <<< "$jobs"
