#!/usr/bin/env bash
# Opens the pull request of an agent's branch into the default branch, titled by pull-request-title.sh, with the labels
# (created when missing) and the body given. Prints its address.
# Usage: open-agent-pull-request.sh <branch> <the commit the branch starts from> <its tip>
# Needs GH_TOKEN (the bot App's: pull requests write), GH_REPO, BASE (the default branch), LABELS (comma-separated) and
# BODY.
set -euo pipefail

branch="$1"
title="$("$(dirname "$0")/pull-request-title.sh" "$2" "$3")"
args=()
IFS=',' read -ra names <<< "$LABELS"
for name in "${names[@]}"; do
  name="$(echo "$name" | xargs)"
  [ -n "$name" ] || continue
  gh label create "$name" > /dev/null 2>&1 || true
  args+=(--label "$name")
done
gh pr create --base "$BASE" --head "$branch" --title "$title" --body "$BODY" "${args[@]}"
