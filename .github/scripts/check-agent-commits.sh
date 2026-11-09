#!/usr/bin/env bash
# Checks an agent's commits before they are published, where no agent ran: in a fresh checkout of the branch the agent
# started from (HEAD), given the commit its job handed over (<tip>, fetched from its bundle and never checked out).
# - The commit they start from is worked out here, never taken from the agent's job: where <tip> leaves HEAD's
#   history. On a branch of the agent's own ("new") that is wherever it started; on an existing one it must be HEAD
#   itself, or the branch got commits while the agent worked and the agent's no longer follow it.
# - The commits may not change what the agents themselves may do (the paths given): an agent that could change its own
#   settings could give itself any command.
# Prints the commit they start from, or nothing when <tip> adds no commit.
# Usage: check-agent-commits.sh <tip> <new|existing> [path...]
set -euo pipefail

tip="$1"
kind="$2"
shift 2

head="$(git rev-parse HEAD)"
start="$(git merge-base "$tip" "$head" || true)"
if [ -z "$start" ]; then
  echo "::error title=Not this branch's commits::The agent's commits share no history with the branch it started from: nothing is pushed." >&2
  exit 1
fi
if [ "$kind" != "new" ] && [ "$start" != "$head" ]; then
  echo "::error title=The branch moved::The branch got commits while the agent worked, so the agent's no longer follow it: nothing is pushed. Call the agent again." >&2
  exit 1
fi
[ "$(git rev-list --count "$start..$tip")" -gt 0 ] || exit 0
if [ "$#" -gt 0 ]; then
  changed="$(git diff --name-only "$start" "$tip" -- "$@" | paste -sd ' ' -)"
  if [ -n "$changed" ]; then
    echo "::error title=Not the agents' to change::The commits change what the agents may do ($changed), which only the owner changes: nothing is pushed." >&2
    exit 1
  fi
fi
echo "$start"
