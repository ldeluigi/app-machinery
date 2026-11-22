#!/usr/bin/env bash
# When a pull request merged: starts the issues a split left waiting (split-issue.sh) once everything they wait for is
# closed, counting as closed the issues the pull request closes (<closed>), as GitHub may not have closed them yet.
# Only the bot's own open issues count, through the `waits-for` marker of their body, and only those no agent took yet
# (no `claude` or `copilot` label): they get the `claude` label, added as the bot App, which starts Claude. A split
# issue whose sub-issues are all closed is closed as done.
# Usage: start-waiting-issues.sh <closed>...
# Needs GH_TOKEN (the bot App's: issues write), GH_REPO and BOT, the bot App's login (`name[bot]`).
set -euo pipefail

closed=" $* "
is_closed() { # <number>
  [ "${closed#* "$1" }" != "$closed" ] || [ "$(gh api "repos/$GH_REPO/issues/$1" --jq .state)" = "closed" ]
}

# The bot's open issues no agent took yet, with what each waits for
# shellcheck disable=SC2016 # jq's own variables
waiting='
  .[] | select(.pull_request == null) |
  select([.labels[].name] | (index("claude") or index("copilot")) | not) |
  (.body // "" | capture("<!-- waits-for:(?<waits>[0-9 ]+) -->")?) as $marker |
  select($marker != null) | "\(.number) \($marker.waits)"'
while read -r number waits; do
  [ -n "$number" ] || continue
  ready=true
  for other in $waits; do
    if ! is_closed "$other"; then
      ready=false
      break
    fi
  done
  if [ "$ready" = "true" ]; then
    gh issue edit "$number" --add-label claude > /dev/null
    echo "Started #$number: what it waited for ($waits) is done."
  fi
done < <(gh api "repos/$GH_REPO/issues?state=open&creator=$(jq -rn --arg bot "$BOT" '$bot | @uri')&per_page=100" \
  --paginate --jq "$waiting")

for number in "$@"; do
  parent="$(gh api "repos/$GH_REPO/issues/$number/parent" --jq '"\(.number) \(.state) \([.labels[].name] | index("split") != null)"' 2>/dev/null || true)"
  read -r parent state split <<< "$parent"
  if [ -z "${parent:-}" ] || [ "$state" != "open" ] || [ "$split" != "true" ]; then
    continue
  fi
  open=false
  for part in $(gh api "repos/$GH_REPO/issues/$parent/sub_issues?per_page=100" --paginate --jq '.[] | select(.state == "open") | .number'); do
    if ! is_closed "$part"; then
      open=true
      break
    fi
  done
  if [ "$open" = "false" ]; then
    gh issue close "$parent" --reason completed > /dev/null
    echo "Closed #$parent: all the issues it was split into are done."
  fi
done
