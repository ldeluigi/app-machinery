#!/usr/bin/env bash
# Opens the smaller issues an agent split issue <number> into, as the bot App, where no agent ran. They come from the
# agent's job as a file, so they are checked here first and taken as data: a JSON array of 2 to 10 objects, in the
# order they should be done, each with a `title`, a `body` (Markdown) and `after`, the indexes (from 0) of the new
# issues it waits for:
#   [{"title": "Tell the Guests apart", "body": "...", "after": []}, {"title": "...", "body": "...", "after": [0]}]
# Each becomes a sub-issue of <number>, which gets the `split` label. Those that wait for none start at once (the
# `claude` label, added as the bot App); the others once the ones they wait for are merged (start-waiting-issues.sh,
# through the `waits-for` marker of their body).
# Prints the new issues, one "#<number> <title>" per line.
# Usage: split-issue.sh <number> <file> <agent>
# Needs GH_TOKEN (the bot App's: issues write), GH_REPO and RUN_URL, the run the agent split the issue in.
set -euo pipefail

issue="$1"
file="$2"
agent="$3"

# shellcheck disable=SC2016 # jq's own variables
valid='
  type == "array" and length >= 2 and length <= 10 and
  (to_entries | all(
    (.value | type == "object") and
    (.value.title | type == "string" and length > 0 and length <= 200 and (test("[\n\r]") | not)) and
    (.value.body | type == "string" and length > 0 and length <= 30000) and
    (.key as $index | (.value.after // []) | type == "array" and
      all(type == "number" and . == floor and . >= 0 and . < $index))))'
if ! jq -e "$valid" "$file" > /dev/null 2>&1; then
  echo "::error title=The split couldn't be opened::$agent's list of issues isn't what the workflow asks for: 2 to 10 issues, each with a title, a body and the earlier ones it waits for." >&2
  exit 1
fi

gh label create split --color C5DEF5 --description "Split into smaller issues: they are its sub-issues" --force > /dev/null
gh label create claude --color D97757 --description "Claude's responsibility" > /dev/null 2>&1 || true
numbers=()
starting=()
count="$(jq length "$file")"
for ((index = 0; index < count; index++)); do
  title="$(jq -r ".[$index].title" "$file")"
  waits=""
  list=""
  for earlier in $(jq -r ".[$index].after // [] | .[]" "$file"); do
    waits="$waits ${numbers[$earlier]}"
    list="${list:+$list, }#${numbers[$earlier]}"
  done
  footer="Part of #$issue, split by $agent in [this run]($RUN_URL)."
  if [ -n "$waits" ]; then
    footer="$footer It starts once $list merged.
<!-- waits-for:$waits -->"
  fi
  body="$(jq -r ".[$index].body" "$file")

---
$footer"
  url="$(gh issue create --title "$title" --body "$body")"
  number="${url##*/}"
  numbers+=("$number")
  [ -n "$waits" ] || starting+=("$number")
  id="$(gh api "repos/$GH_REPO/issues/$number" --jq .id)"
  gh api -X POST "repos/$GH_REPO/issues/$issue/sub_issues" -F sub_issue_id="$id" --silent
  echo "#$number $title"
done
gh issue edit "$issue" --add-label split > /dev/null
for number in "${starting[@]}"; do
  gh issue edit "$number" --add-label claude > /dev/null
done
