#!/usr/bin/env bash
# Takes the AI agents off issue $1 after they failed on it: removes the "copilot" label, so that the label can hand the
# issue to Copilot again later.
# With --retry-next-week (the agents ran out of credit) it also deletes their error comments and adds the
# "retry next week" label: agent-retry.yml takes it off on Monday, after the quotas reset, and the issue agent starts
# again.
# Needs GH_TOKEN (issues: write) and GH_REPO.
set -euo pipefail

issue="$1"
mode="${2:-}"

gh api -X DELETE "repos/$GH_REPO/issues/$issue/labels/copilot" --silent 2>/dev/null || true

if [ "$mode" = "--retry-next-week" ]; then
  # "Claude encountered an error after 2s", "Copilot encountered an error in this run"
  for comment in $(gh api "repos/$GH_REPO/issues/$issue/comments" --paginate \
    --jq '.[] | select(.user.type == "Bot" and (.body | contains("encountered an error"))) | .id'); do
    gh api -X DELETE "repos/$GH_REPO/issues/comments/$comment" --silent
  done
  gh label create "retry next week" --color FBCA04 \
    --description "The AI agents ran out of credit: they try again on Monday" --force
  gh issue edit "$issue" --add-label "retry next week"
fi
