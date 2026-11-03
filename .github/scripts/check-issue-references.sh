#!/usr/bin/env bash
# Fails when a commit message in the given revisions refers to an issue or pull request by number: `Closes #12`,
# `Fixes #12`, or a bare `#12`. The release notes, and the stores' "What's new" made from them, render every such
# reference as ", closes #12", which means nothing to the app's users: a pull request's description closes an issue
# instead. Merge commits are skipped, so is the "(#12)" GitHub appends to a squash-merged pull request's title, and so
# are Dependabot's commits, which quote the dependencies' changelogs.
# Usage: scripts/check-issue-references.sh <git rev-list arguments>, e.g. origin/master..HEAD or -1 HEAD
set -euo pipefail

[ $# -gt 0 ] || { echo "usage: $0 <git rev-list arguments>" >&2; exit 2; }
status=0

for commit in $(git rev-list --no-merges "$@"); do
  if [ "$(git log -1 --format='%an' "$commit")" = 'dependabot[bot]' ]; then
    continue
  fi
  # The subject without GitHub's squash-merge suffix, then the body and the footer
  message="$(git log -1 --format='%s' "$commit" | sed -E 's/ \(#[0-9]+\)$//'; git log -1 --format='%b' "$commit")"
  if found="$(printf '%s\n' "$message" | grep -n -E '(^|[^[:alnum:]_])#[0-9]+')"; then
    echo "::error::Commit ${commit:0:7} refers to an issue by number, which would end up in the stores' \"What's new\". Let the pull request's description close the issue instead."
    echo "$found"
    status=1
  fi
done

exit $status
