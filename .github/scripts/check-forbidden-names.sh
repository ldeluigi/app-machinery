#!/usr/bin/env bash
# Fails when a name the app must never mention appears in its repository: in its files or, given a revision range, in
# its commit messages (a competitor's trademark, say). The names are regular expressions, one a line, in the app's
# config/forbidden-names.txt. Write them with character classes, like n[a]me, so that the list doesn't contain the
# names itself. Without the file, no name is forbidden.
# Usage: check-forbidden-names.sh [<revision-range>], from the app repository's root.
set -euo pipefail

list="config/forbidden-names.txt"
if [ ! -f "$list" ]; then
  echo "No $list: no name is forbidden."
  exit 0
fi
pattern="$({ grep -vE '^[[:space:]]*(#|$)' "$list" || true; } | paste -sd '|' -)"
[ -n "$pattern" ] || exit 0
status=0

if git grep -n -i -I -E "$pattern" -- . ":(exclude)$list"; then
  echo "::error::A forbidden name is in the files above."
  status=1
fi

if [ $# -gt 0 ] && git log --format='%H %s%n%b' "$1" | grep -n -i -E "$pattern"; then
  echo "::error::A forbidden name is in the commit messages of $1."
  status=1
fi

exit $status
