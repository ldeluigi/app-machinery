#!/usr/bin/env bash
# Checks an agent's commits before they are published, where no agent ran: in a fresh checkout of the branch the agent
# started from (HEAD), given the commit its job handed over (<tip>, fetched from its bundle and never checked out).
# - The commit they start from is worked out here, never taken from the agent's job: where <tip> leaves HEAD's
#   history. On a branch of the agent's own ("new") that is wherever it started; on an existing one it must be HEAD
#   itself, or the branch got commits while the agent worked and the agent's no longer follow it.
# - The commits may not change what only the owner changes (the paths given): what the agents themselves may do (an
#   agent that could change its own settings could give itself any command) and the licences the app may ship. A file
#   the commits leave as the default branch has it (BASE_REF, when set) is the owner's, come with a merge of it.
# Prints the commit they start from, or nothing when <tip> adds no commit.
# Usage: check-agent-commits.sh <tip> <new|existing> [path...]
# BASE_REF: the default branch as fetched (origin/main), whose files the commits may bring in.
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
  changed=""
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    # As the default branch has it: the owner's version, which a merge of the default branch brings in
    if [ -n "${BASE_REF:-}" ] && git diff --quiet "$BASE_REF" "$tip" -- "$file" 2> /dev/null; then continue; fi
    changed="${changed:+$changed }$file"
  done < <(git diff --name-only "$start" "$tip" -- "$@")
  if [ -n "$changed" ]; then
    echo "::error title=Not the agents' to change::The commits change files only the owner changes ($changed): nothing is pushed." >&2
    exit 1
  fi
fi
echo "$start"
