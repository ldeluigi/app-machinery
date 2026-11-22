#!/usr/bin/env bash
# Commits what an agent left uncommitted when it stopped before it finished (out of time, an error), in its own job,
# so that its work goes to its branch with the commits it made and the next agent on the issue goes on from it. Leaves
# out the machinery's checkout, `.github` (the bot can't push workflow changes) and the paths given: what the agents'
# commits may not change, which publish-commits refuses. The commit carries an `Unfinished-Work: <agent>` trailer:
# pull-request-title.sh never takes the pull request's title from it.
# Usage: save-unfinished-work.sh <agent> [path...]
set -euo pipefail

agent="$1"
shift
excluded=(':!.machinery' ':!.github')
for path in "$@"; do excluded+=(":!$path"); done

git add --all -- . "${excluded[@]}"
if git diff --cached --quiet; then
  echo "$agent left nothing uncommitted."
  exit 0
fi
# Without the checkout's hooks, which would build the app: the agent's job may have little time left
git -c core.hooksPath=/dev/null -c commit.gpgsign=false -c user.name="github-actions[bot]" \
  -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
  commit --quiet -m "chore: save the work $agent left unfinished" -m "Unfinished-Work: $agent"
echo "Saved what $agent left uncommitted:"
git show --stat --format= HEAD
