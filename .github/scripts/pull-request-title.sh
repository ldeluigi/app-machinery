#!/usr/bin/env bash
# The title of an agent's pull request: the subject of the first commit of its branch, which the agents write to name
# the whole change, leaving out the commits that only saved an agent's unfinished work (save-unfinished-work.sh). When
# the branch has nothing but those, the first of them.
# Usage: pull-request-title.sh <the commit the branch starts from> <its tip>
set -euo pipefail

first=""
# Split on a control character, not a tab: read would merge a tab after an empty trailer into the subject's
while IFS=$'\x1f' read -r unfinished subject; do
  [ -n "$first" ] || first="$subject"
  if [ -z "$unfinished" ]; then
    echo "$subject"
    exit 0
  fi
done < <(git log --reverse --format='%(trailers:key=Unfinished-Work,valueonly,separator=%x2C)%x1f%s' "$1..$2")
echo "$first"
