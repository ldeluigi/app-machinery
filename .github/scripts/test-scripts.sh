#!/usr/bin/env bash
# The scripts' own tests, which Self CI runs:
# - out-of-credit.sh tells the agents' messages about a used-up quota (the issue waits for the reset) from every other
#   error (a failure of the automation);
# - check-agent-commits.sh lets through only commits that follow the branch and leave the agents' settings alone;
# - wait-for-publish.sh waits for another run's agent exactly while its commits are still to be published.
# Usage: test-scripts.sh
set -euo pipefail

scripts="$(cd "$(dirname "$0")" && pwd)"
status=0
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

expect() { # <out-of-credit|error> <message>
  local found=error
  if printf '%s' "$2" | "$scripts/out-of-credit.sh"; then found=out-of-credit; fi
  if [ "$found" != "$1" ]; then
    echo "::error::out-of-credit.sh takes \"$2\" for ${found/error/another error}, not for ${1/error/another error}."
    status=1
  fi
}

# Claude Code, on a subscription and on an API key
expect out-of-credit "You've hit your limit · resets 3pm (UTC)"
expect out-of-credit "You've hit your weekly limit · resets Nov 9, 8am (UTC)"
expect out-of-credit "You've hit your session limit · resets 2pm (UTC)"
expect out-of-credit "You've hit your 5-hour limit · resets 6pm (UTC)"
expect out-of-credit "Claude usage limit reached. Your limit will reset at 3pm (UTC)."
expect out-of-credit "You're out of extra usage · resets 3pm (UTC)"
expect out-of-credit "You're out of usage credits. Switch to another model to continue."
expect out-of-credit "Credit balance is too low"
# The Copilot CLI
expect out-of-credit "402 You have no quota"
expect out-of-credit "You have exceeded your premium request allowance."
expect out-of-credit "You have used all of your AI credits for this month."

expect error "Bad credentials"
expect error "API Error: 500 Internal server error"
expect error "Claude Code stopped without giving a reason: see this step's log."
expect error "Copilot ran out of time: it was still working after 60 minutes."
expect error "The Copilot CLI stopped (exit code 1) without giving a reason: see this step's log."

# The agents' commits, as they travel: made in the agent's clone, bundled, fetched into a fresh clone and checked there
repo() { # <directory> <git arguments...>
  git -C "$1" -c user.name=Test -c user.email=test@example.com -c commit.gpgsign=false -c core.hooksPath=/dev/null \
    -c core.autocrlf=false "${@:2}"
}
commit() { # <directory> <file> <content>
  mkdir -p "$(dirname "$1/$2")"
  echo "$3" > "$1/$2"
  repo "$1" add -A
  repo "$1" commit --quiet -m "change $2"
}
git init --quiet --initial-branch main "$work/origin"
commit "$work/origin" app.txt one
commit "$work/origin" .claude/settings.json "{}"
protected=(.claude/settings.json .mcp.json)

# The agent's commits since it cloned, fetched into a fresh clone of the branch as it is by then: prints their tip
handed_over() { # <name> <the file the agent changes> [<a file the branch gets meanwhile>]
  local agent="$work/agent-$1" fresh="$work/fresh-$1" started
  git -c core.autocrlf=false clone --quiet "$work/origin" "$agent"
  started="$(repo "$agent" rev-parse HEAD)"
  commit "$agent" "$2" "the agent's"
  [ -z "${3:-}" ] || commit "$work/origin" "$3" "someone else's"
  repo "$agent" bundle create --quiet "$work/$1.bundle" "$started..HEAD"
  git -c core.autocrlf=false clone --quiet "$work/origin" "$fresh"
  repo "$fresh" -c fetch.fsckObjects=true fetch --quiet --no-tags "$work/$1.bundle" HEAD
  repo "$fresh" rev-parse FETCH_HEAD
}
expect_commits() { # <published|refused|nothing> <name> <new|existing> <tip>
  local found=refused start
  if start="$(cd "$work/fresh-$2" && "$scripts/check-agent-commits.sh" "$4" "$3" "${protected[@]}" 2> /dev/null)"; then
    found=published
    [ -n "$start" ] || found=nothing
  fi
  if [ "$found" != "$1" ]; then
    echo "::error::check-agent-commits.sh: \"$2\" on a branch that is $3 is $found, not $1."
    status=1
  fi
}

tip="$(handed_over app app.txt)"
expect_commits published app existing "$tip"
expect_commits published app new "$tip"
expect_commits nothing app existing "$(repo "$work/fresh-app" rev-parse HEAD)"
expect_commits refused settings existing "$(handed_over settings .claude/settings.json)"

# The branch gets a commit while the agent works: the agent's own new branch still starts where it did
tip="$(handed_over moved app.txt other.txt)"
expect_commits refused moved existing "$tip"
expect_commits published moved new "$tip"

# Commits of another history altogether
git init --quiet --initial-branch main "$work/elsewhere"
commit "$work/elsewhere" app.txt forged
repo "$work/elsewhere" bundle create --quiet "$work/elsewhere.bundle" HEAD
git -c core.autocrlf=false clone --quiet "$work/origin" "$work/fresh-elsewhere"
repo "$work/fresh-elsewhere" fetch --quiet --no-tags "$work/elsewhere.bundle" HEAD
expect_commits refused elsewhere new "$(repo "$work/fresh-elsewhere" rev-parse FETCH_HEAD)"

# The wait, with a gh that answers what the script asks: the other runs' ids, then one run's jobs, tab-separated
mkdir -p "$work/bin"
cat > "$work/bin/gh" << 'GH'
#!/usr/bin/env bash
case "$2" in
  */jobs\?*) cat "$JOBS" ;;
  */runs\?*) echo 7 ;;
esac
GH
chmod +x "$work/bin/gh"
expect_wait() { # <waits|starts> <the other run's jobs, each "name|status|conclusion">...
  local found=starts output
  printf '%s\n' "${@:2}" | tr '|' '\t' > "$work/jobs"
  output="$(PATH="$work/bin:$PATH" JOBS="$work/jobs" GH_REPO=owner/app GITHUB_RUN_ID=9 WAIT_SECONDS=0 GITHUB_OUTPUT="" \
    "$scripts/wait-for-publish.sh" Claude)"
  case "$output" in *"still hasn't published"*) found=waits ;; esac
  if [ "$found" != "$1" ]; then
    echo "::error::wait-for-publish.sh ${found} when the other run's jobs are: ${*:2}. It should be: $1."
    status=1
  fi
}

expect_wait waits "automation / Claude|completed|success"
expect_wait waits "automation / Claude|completed|failure" "automation / Claude: publish|in_progress|"
expect_wait waits "Claude|completed|success" "Claude: publish|queued|"
expect_wait starts "automation / Claude|completed|success" "automation / Claude: publish|completed|success"
expect_wait starts "automation / Claude|completed|cancelled" "automation / Claude: publish|completed|skipped"
expect_wait starts "automation / Claude|in_progress|"
expect_wait starts "automation / Claude|completed|skipped"
expect_wait starts "automation / Copilot|completed|success" "automation / Claude: start|completed|success"

[ "$status" -ne 0 ] || echo "The scripts pass their tests."
exit $status
