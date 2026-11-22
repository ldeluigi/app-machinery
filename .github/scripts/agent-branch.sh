#!/usr/bin/env bash
# Chooses the branch an agent works on for an issue. An agent that stopped before it finished (out of time or credit,
# an error) left its work on its `agent/issue-<number>-<time>` branch, with no pull request (the publish jobs): the
# next agent goes on from there. Only the issue's newest branch counts: one that ever had a pull request is done with
# (merged, closed, or worked on through its pull request), and so are the older ones, and the agent starts a new one.
# Prints the branch's name, then "continued" or "new", one per line.
# Usage: agent-branch.sh <issue number>
# Needs GH_TOKEN (contents: read, pull-requests: read) and GH_REPO.
set -euo pipefail

issue="$1"
prefix="agent/issue-$issue-"
# The names end with the time they were made (UTC, %Y%m%d-%H%M): the last in order is the newest. A list that can't be
# read gives a new branch, as before there were unfinished ones
newest="$(gh api "repos/$GH_REPO/git/matching-refs/heads/$prefix" --jq '.[].ref | ltrimstr("refs/heads/")' |
  sort | tail -n 1 || true)"
if [ -n "$newest" ]; then
  pulls="$(gh api "repos/$GH_REPO/pulls?state=all&head=${GH_REPO%%/*}:$newest&per_page=1" --jq length || echo 1)"
  if [ "$pulls" = "0" ]; then
    printf '%s\ncontinued\n' "$newest"
    exit 0
  fi
fi
printf '%s%s\nnew\n' "$prefix" "$(date -u +%Y%m%d-%H%M)"
