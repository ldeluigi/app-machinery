#!/usr/bin/env bash
# Prints the jobs of a workflow run (its latest attempt) that GitHub stopped at their time limit (timeout-minutes), one
# id per line. GitHub reports such a job, and its run, as cancelled, just as it reports the ones a newer run (their
# concurrency group) or a person cancelled, which are best left alone: only the job's annotation tells them apart. A
# job runs out of time when something in it hangs, or when its runner stops answering, which leaves it without a log.
# Usage: timed-out.sh <run id>
# Needs GH_TOKEN (actions: read and checks: read) and GH_REPO.
set -euo pipefail

run="$1"
for job in $(gh api "repos/$GH_REPO/actions/runs/$run/jobs?per_page=100" --paginate \
  --jq '.jobs[] | select(.conclusion == "cancelled") | .id'); do
  # Read whole, then searched: under pipefail, grep ending the pipe early would count as a failure
  messages="$(gh api "repos/$GH_REPO/check-runs/$job/annotations?per_page=100" --paginate \
    --jq '.[] | select(.annotation_level == "failure") | .message')"
  if grep -q 'exceeded the maximum execution time' <<< "$messages"; then
    echo "$job"
  fi
done
