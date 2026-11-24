#!/usr/bin/env bash
# Looks after the bot's pull requests that merge by themselves (the `automerge` label) but no longer can, once the
# default branch moved on:
# - In conflict with it: Claude merges the default branch into it, called by a conflict-N label, which the Claude start
#   job takes as its task, three times at most, and not again while an earlier call may still be at it
#   (CONFLICT_HOURS). The owner takes it (needs-human) after the third, or at once when the default branch changed a
#   workflow since the pull request began: a branch that brings in a workflow change is one GitHub refuses from the bot.
# - With no run of CI at all on its head, half an hour after it opened (GitHub lost the events of its push), and the
#   default branch ahead of it: GitHub brings it up to date (update-branch), a new commit whose CI starts.
# Pull requests that wait for the owner (needs-human), come from a fork or aren't the bot's are left alone.
# Usage: bot-pull-requests.sh <the bot's login> <the default branch>
# GH_REPO; GH_TOKEN, the bot's, writes, and READ_TOKEN, when given, reads (the workflow's token reads check runs, the
# bot's may not); MERGEABLE_WAIT (10 s between GitHub's tries at telling whether one merges), CONFLICT_HOURS (3) and
# NOW (seconds since the epoch) change its timing.
set -euo pipefail

bot="${1%\[bot\]}"
base="$2"
now="${NOW:-$(date +%s)}"
wait_seconds="${MERGEABLE_WAIT:-10}"
hours="${CONFLICT_HOURS:-3}"

reads() { GH_TOKEN="${READ_TOKEN:-${GH_TOKEN:-}}" gh "$@"; }

owner_takes() { # <number> <why>
  gh label create needs-human --color D93F0B --description "Automation could not handle this" --force > /dev/null &&
    gh pr edit "$1" --add-label needs-human > /dev/null &&
    gh pr comment "$1" --body "$2" > /dev/null &&
    echo "#$1 waits for the owner."
}

look_after() { # <a pull request, as gh's JSON>
  local pr="$1" number login labels sha mergeable compare behind workflows attempts n last next runs age
  number="$(jq -r .number <<< "$pr")"
  # gh names an app "app/<its slug>"
  login="$(jq -r .author.login <<< "$pr")"
  [ "${login#app/}" = "$bot" ] || [ "$login" = "${bot}[bot]" ] || return 0
  [ "$(jq -r .isCrossRepository <<< "$pr")" = "false" ] || return 0
  labels=",$(jq -r '[.labels[].name] | join(",")' <<< "$pr"),"
  case "$labels" in *,needs-human,*) return 0 ;; esac
  sha="$(jq -r .headRefOid <<< "$pr")"
  # GitHub works out whether a pull request merges after the default branch moves, a few seconds later
  mergeable=UNKNOWN
  for n in 1 2 3 4 5 6; do
    mergeable="$(reads pr view "$number" --json mergeable | jq -r .mergeable)" || return 1
    [ "$mergeable" = "UNKNOWN" ] || break
    [ "$n" = 6 ] || sleep "$wait_seconds"
  done
  # How far behind it is, and what the default branch changed since it began
  # set -e doesn't hold in a function called as a condition: each read that fails ends the look at this one
  compare="$(reads api "repos/$GH_REPO/compare/$sha...$base")" || return 1
  behind="$(jq -r '.ahead_by' <<< "$compare")" || return 1
  workflows="$(jq -r '[.files[]?.filename | select(startswith(".github/workflows/"))] | join(", ")' <<< "$compare")" || return 1
  case "$mergeable" in
    CONFLICTING)
      if [ -n "$workflows" ]; then
        owner_takes "$number" "This pull request conflicts with \`$base\`, which moved on, and \`$base\` changed workflows meanwhile ($workflows): GitHub refuses the bot a branch that brings them in, so the owner merges \`$base\` into it."
        return
      fi
      attempts=0
      for n in 1 2 3; do
        case "$labels" in *,conflict-$n,*) attempts=$n ;; esac
      done
      if [ "$attempts" -gt 0 ]; then
        last="$(reads api "repos/$GH_REPO/issues/$number/events?per_page=100" |
          jq -r '[.[] | select(.event == "labeled" and (.label.name | startswith("conflict-")))] | last | .created_at // empty')" ||
          return 1
        if [ -n "$last" ] && [ $((now - $(date -d "$last" +%s))) -lt $((hours * 3600)) ]; then
          echo "#$number: an agent was called to merge $base into it less than $hours hours ago."
          return 0
        fi
      fi
      if [ "$attempts" -ge 3 ]; then
        owner_takes "$number" "This pull request still conflicts with \`$base\` after three agents were called to merge it: the owner takes it from here."
        return
      fi
      next=$((attempts + 1))
      gh label create "conflict-$next" --color FBCA04 --description "Merge agent attempt $next" --force > /dev/null || return 1
      gh pr comment "$number" --body "This pull request conflicts with \`$base\`, which moved on: Claude merges \`$base\` into it (attempt $next of 3)." > /dev/null || return 1
      gh pr edit "$number" --add-label "conflict-$next" > /dev/null || return 1
      echo "#$number: Claude merges $base into it."
      ;;
    MERGEABLE)
      runs="$(reads api "repos/$GH_REPO/commits/$sha/check-runs?per_page=1" | jq -r '.total_count')" || return 1
      age=$((now - $(date -d "$(jq -r .createdAt <<< "$pr")" +%s))) || return 1
      case "$runs$behind" in *[!0-9]*) return 1 ;; esac
      if [ "$runs" -gt 0 ] || [ "$age" -lt 1800 ] || [ "$behind" -eq 0 ]; then
        return 0
      fi
      if [ -n "$workflows" ]; then
        owner_takes "$number" "No CI ran on this pull request, and bringing it up to date with \`$base\`, which starts CI, would bring in workflow changes ($workflows), which GitHub refuses the bot: the owner updates its branch."
        return
      fi
      gh pr update-branch "$number" > /dev/null || return 1
      echo "#$number had no CI run: GitHub brings it up to date with $base, which starts CI."
      ;;
    *)
      echo "#$number: GitHub hasn't worked out whether it merges; the next look will."
      ;;
  esac
}

status=0
while IFS= read -r pr; do
  [ -n "$pr" ] || continue
  if ! look_after "$pr"; then
    echo "::warning::#$(jq -r .number <<< "$pr") couldn't be looked after: see above."
    status=1
  fi
done < <(reads pr list --state open --label automerge --limit 100 \
  --json number,author,headRefOid,createdAt,labels,isCrossRepository | jq -c '.[]')
exit "$status"
