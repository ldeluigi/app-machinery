#!/usr/bin/env bash
# The scripts' own tests, which Self CI runs:
# - out-of-credit.sh tells the agents' messages about a used-up quota (the issue waits for the reset) from every other
#   error (a failure of the automation);
# - check-agent-commits.sh lets through only commits that follow the branch and leave the agents' settings alone;
# - wait-for-publish.sh waits for another run's agent exactly while its commits are still to be published;
# - timed-out.sh tells the jobs GitHub stopped at their time limit from the ones a newer run or a person cancelled;
# - save-unfinished-work.sh commits what an agent that stopped left uncommitted, but what it may not change;
# - pull-request-title.sh names a pull request by its branch's first commit that isn't such a save;
# - agent-branch.sh goes on from an issue's branch left unfinished, and starts a new one otherwise;
# - split-issue.sh opens the issues an agent split one into, as sub-issues, and starts the ones that wait for none;
# - start-waiting-issues.sh starts the others once what they wait for is merged, and closes a split issue when done.
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

# The time limits, with a gh that answers what the script asks: the run's cancelled jobs, then each one's failure
# annotations
cat > "$work/bin/gh" << 'GH'
#!/usr/bin/env bash
case "$2" in
  */runs/*/jobs\?*) ls "$ANNOTATIONS" ;;
  */check-runs/*/annotations\?*) job="${2#*/check-runs/}" && cat "$ANNOTATIONS/${job%%/*}" ;;
esac
GH
expect_timed_out() { # <the jobs it prints, space-separated> <each cancelled job's annotation, "id|message">...
  local found entry
  rm -rf "$work/annotations" && mkdir "$work/annotations"
  for entry in "${@:2}"; do echo "${entry#*|}" >> "$work/annotations/${entry%%|*}"; done
  found="$(PATH="$work/bin:$PATH" ANNOTATIONS="$work/annotations" GH_REPO=owner/app \
    "$scripts/timed-out.sh" 7 | paste -sd ' ' -)"
  if [ "$found" != "$1" ]; then
    echo "::error::timed-out.sh prints \"$found\" for the cancelled jobs: ${*:2}. It should print \"$1\"."
    status=1
  fi
}

expect_timed_out "11" "11|The job has exceeded the maximum execution time of 45m0s"
expect_timed_out "11" "11|The job running on runner GitHub Actions 3 has exceeded the maximum execution time of 45 minutes."
expect_timed_out "" "11|Canceling since a higher priority waiting request for ci-refs/pull/9/merge-pull_request exists"
expect_timed_out "" "11|The operation was canceled."
expect_timed_out "12" "11|The operation was canceled." "12|The job has exceeded the maximum execution time of 60m0s"
expect_timed_out "11 12" "11|The job has exceeded the maximum execution time of 45m0s" \
  "12|The job has exceeded the maximum execution time of 60m0s"
expect_timed_out ""

# An agent that stopped before it finished: what it left uncommitted is saved, but for what it may not change
git -c core.autocrlf=false clone --quiet "$work/origin" "$work/unfinished"
started="$(repo "$work/unfinished" rev-parse HEAD)"
echo "half done" > "$work/unfinished/app.txt"
echo "new" > "$work/unfinished/new.txt"
echo '{"allow":["*"]}' > "$work/unfinished/.claude/settings.json"
mkdir -p "$work/unfinished/.github/workflows" "$work/unfinished/.machinery"
echo "on: push" > "$work/unfinished/.github/workflows/ci.yml"
echo "machinery" > "$work/unfinished/.machinery/tool"
(cd "$work/unfinished" && "$scripts/save-unfinished-work.sh" Claude "${protected[@]}" > /dev/null)
saved="$(repo "$work/unfinished" show --name-only --format= HEAD | sort | paste -sd ' ' -)"
if [ "$(repo "$work/unfinished" rev-list --count "$started..HEAD")" != "1" ] || [ "$saved" != "app.txt new.txt" ]; then
  echo "::error::save-unfinished-work.sh saved \"$saved\": it should save app.txt and new.txt alone, in one commit."
  status=1
fi
if [ "$(repo "$work/unfinished" log -1 --format='%(trailers:key=Unfinished-Work,valueonly,separator=%x2C)')" != "Claude" ]; then
  echo "::error::save-unfinished-work.sh doesn't mark its commit with Unfinished-Work: Claude."
  status=1
fi
git -C "$work/unfinished" checkout --quiet -- .claude/settings.json
rm -rf "$work/unfinished/.github" "$work/unfinished/.machinery"
(cd "$work/unfinished" && "$scripts/save-unfinished-work.sh" Copilot "${protected[@]}" > /dev/null)
if [ "$(repo "$work/unfinished" rev-list --count "$started..HEAD")" != "1" ]; then
  echo "::error::save-unfinished-work.sh made a commit when nothing was left uncommitted."
  status=1
fi

# The pull request's title: the branch's first commit, leaving out the ones that saved unfinished work
expect_title() { # <title> <each commit's subject, "saved" before the ones that saved unfinished work>...
  local dir="$work/title-$RANDOM" subject found
  git -c core.autocrlf=false clone --quiet "$work/origin" "$dir"
  started="$(repo "$dir" rev-parse HEAD)"
  for subject in "${@:2}"; do
    echo "$subject" >> "$dir/app.txt"
    repo "$dir" add -A
    if [ "${subject#saved }" != "$subject" ]; then
      repo "$dir" commit --quiet -m "${subject#saved }" -m "Unfinished-Work: Claude"
    else
      repo "$dir" commit --quiet -m "$subject"
    fi
  done
  found="$(cd "$dir" && "$scripts/pull-request-title.sh" "$started" HEAD)"
  if [ "$found" != "$1" ]; then
    echo "::error::pull-request-title.sh names the branch \"$found\", not \"$1\", for: ${*:2}"
    status=1
  fi
}

expect_title "feat(game): replay the song" "feat(game): replay the song" "fix: a test"
expect_title "feat(game): replay the song" "saved chore: save the work Claude left unfinished" \
  "feat(game): replay the song" "fix: a test"
expect_title "chore: save the work Claude left unfinished" "saved chore: save the work Claude left unfinished"

# The branch for an issue, with a gh that answers what the script asks: the issue's branches, then each one's pull
# requests
cat > "$work/bin/gh" << 'GH'
#!/usr/bin/env bash
case "$2" in
  */git/matching-refs/*) cat "$BRANCHES" ;;
  */pulls\?*) branch="${2#*head=owner:}" && grep -cxF "${branch%%&*}" "$WITH_PULLS" || true ;;
esac
GH
expect_branch() { # <continued|new> <the branch it continues, or the start of the new one's name> <the issue's branches,
  # each "name" or "name|pull request">...
  local entry found how
  : > "$work/branches" && : > "$work/with-pulls"
  for entry in "${@:3}"; do
    echo "${entry%%|*}" >> "$work/branches"
    [ "${entry#*|}" = "$entry" ] || echo "${entry%%|*}" >> "$work/with-pulls"
  done
  { read -r found; read -r how; } < <(PATH="$work/bin:$PATH" BRANCHES="$work/branches" WITH_PULLS="$work/with-pulls" \
    GH_REPO=owner/app "$scripts/agent-branch.sh" 12)
  if [ "$how" != "$1" ] || [ "${found#"$2"}" = "$found" ]; then
    echo "::error::agent-branch.sh gives $how branch $found for the branches: ${*:3}. It should be $1, $2."
    status=1
  fi
}

expect_branch new agent/issue-12-2
expect_branch continued agent/issue-12-20261121-2222 agent/issue-12-20261121-2222
expect_branch new agent/issue-12-2 "agent/issue-12-20261121-2222|7"
expect_branch continued agent/issue-12-20261122-0041 "agent/issue-12-20261121-2222|7" agent/issue-12-20261122-0041
expect_branch new agent/issue-12-2 agent/issue-12-20261121-2222 "agent/issue-12-20261122-0041|8"

# A gh that answers `gh api <path>` from $FIXTURES/<path, without its query, "/" made "_">.json through the call's own
# --jq, opens issues numbered from 101, and writes every other call, and each call that changes something, to calls
cat > "$work/bin/gh" << 'GH'
#!/usr/bin/env bash
if [ "$1" = "api" ]; then
  shift
  method=GET path="" jq="" fields=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      -X) method="$2" && shift 2 ;;
      --jq) jq="$2" && shift 2 ;;
      -F | -f) fields="$fields $2" && shift 2 ;;
      --paginate | --silent) shift ;;
      *) path="$1" && shift ;;
    esac
  done
  if [ "$method" != "GET" ]; then
    echo "$method $path$fields" >> "$FIXTURES/calls"
    exit 0
  fi
  key="${path%%\?*}"
  [ -f "$FIXTURES/${key//\//_}.json" ] || exit 1
  jq -r "${jq:-.}" "$FIXTURES/${key//\//_}.json"
  exit 0
fi
echo "$*" > "$FIXTURES/last"
case "$1 $2" in
  "label create") ;;
  "issue create")
    number="$(cat "$FIXTURES/next" 2> /dev/null || echo 101)"
    echo $((number + 1)) > "$FIXTURES/next"
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --title) echo "$2" > "$FIXTURES/title-$number" && shift 2 ;;
        --body) echo "$2" > "$FIXTURES/body-$number" && shift 2 ;;
        *) shift ;;
      esac
    done
    echo "{\"id\": $((number + 1000)), \"state\": \"open\"}" > "$FIXTURES/repos_owner_app_issues_$number.json"
    echo "issue create $number" >> "$FIXTURES/calls"
    echo "https://github.com/owner/app/issues/$number"
    ;;
  *) echo "$*" >> "$FIXTURES/calls" ;;
esac
GH
fixtures() { rm -rf "$work/fixtures" && mkdir "$work/fixtures" && : > "$work/fixtures/calls"; }
called() { grep -qxF "$1" "$work/fixtures/calls"; }
run_gh() { PATH="$work/bin:$PATH" FIXTURES="$work/fixtures" GH_REPO=owner/app RUN_URL=https://run BOT='bot[bot]' "$@"; }
expect_called() { # <what the test is about> <yes|no> <call>
  local found=no
  if called "$3"; then found=yes; fi
  if [ "$found" = "yes" ] && [ "$2" = "no" ]; then
    echo "::error::$1: \"$3\" was called, and shouldn't be."
    status=1
  elif [ "$found" = "no" ] && [ "$2" = "yes" ]; then
    echo "::error::$1: \"$3\" wasn't called."
    status=1
  fi
}

# The split: three issues, the second waiting for the first, the third for both
fixtures
cat > "$work/split.json" << 'JSON'
[
  {"title": "Tell the Guests apart", "body": "Give each Guest an id.", "after": []},
  {"title": "Name every phone", "body": "The roster.", "after": [0]},
  {"title": "Pause everyone", "body": "The link loss.", "after": [0, 1]}
]
JSON
if ! printed="$(run_gh "$scripts/split-issue.sh" 50 "$work/split.json" Claude)"; then
  echo "::error::split-issue.sh refused a list it should take."
  status=1
fi
[ "$printed" = "$(printf '#101 Tell the Guests apart\n#102 Name every phone\n#103 Pause everyone')" ] ||
  { echo "::error::split-issue.sh printed \"$printed\"."; status=1; }
expect_called "split-issue.sh" yes "issue create 103"
expect_called "split-issue.sh" yes "POST repos/owner/app/issues/50/sub_issues sub_issue_id=1101"
expect_called "split-issue.sh" yes "POST repos/owner/app/issues/50/sub_issues sub_issue_id=1103"
expect_called "split-issue.sh" yes "issue edit 50 --add-label split"
expect_called "split-issue.sh, the first one starts" yes "issue edit 101 --add-label claude"
expect_called "split-issue.sh, the second one waits" no "issue edit 102 --add-label claude"
if ! grep -qxF "<!-- waits-for: 101 102 -->" "$work/fixtures/body-103" ||
  ! grep -qF "It starts once #101, #102 merged." "$work/fixtures/body-103" ||
  grep -qF "waits-for" "$work/fixtures/body-101" || ! grep -qF "Part of #50" "$work/fixtures/body-101"; then
  echo "::error::split-issue.sh doesn't write what each issue waits for, or what it is part of."
  status=1
fi
for bad in '[{"title": "Alone", "body": "x", "after": []}]' \
  '[{"title": "A", "body": "x", "after": [1]}, {"title": "B", "body": "y", "after": []}]' \
  '[{"title": "A", "body": "x"}, {"title": "", "body": "y"}]' '{"title": "A"}' 'not json'; do
  fixtures
  echo "$bad" > "$work/split.json"
  if run_gh "$scripts/split-issue.sh" 50 "$work/split.json" Claude > /dev/null 2>&1 || called "issue create 101"; then
    echo "::error::split-issue.sh took a list it should refuse: $bad"
    status=1
  fi
done

# A pull request closed #101: of the issues waiting, only those whose every wait is over start
fixtures
cat > "$work/fixtures/repos_owner_app_issues.json" << 'JSON'
[
  {"number": 102, "body": "x\n<!-- waits-for: 101 -->", "labels": []},
  {"number": 103, "body": "x\n<!-- waits-for: 101 104 -->", "labels": []},
  {"number": 105, "body": "x\n<!-- waits-for: 101 -->", "labels": [{"name": "claude"}]},
  {"number": 106, "body": "no marker", "labels": []},
  {"number": 107, "body": "x\n<!-- waits-for: 101 108 -->", "labels": []},
  {"number": 109, "body": "x\n<!-- waits-for: 101 -->", "labels": [], "pull_request": {}}
]
JSON
echo '{"state": "open"}' > "$work/fixtures/repos_owner_app_issues_104.json"
echo '{"state": "closed"}' > "$work/fixtures/repos_owner_app_issues_108.json"
echo '{"number": 50, "state": "open", "labels": [{"name": "split"}]}' > "$work/fixtures/repos_owner_app_issues_101_parent.json"
echo '[{"number": 101, "state": "open"}, {"number": 102, "state": "open"}]' > "$work/fixtures/repos_owner_app_issues_50_sub_issues.json"
run_gh "$scripts/start-waiting-issues.sh" 101 > /dev/null
expect_called "start-waiting-issues.sh, all it waits for is done" yes "issue edit 102 --add-label claude"
expect_called "start-waiting-issues.sh, one more is closed already" yes "issue edit 107 --add-label claude"
expect_called "start-waiting-issues.sh, still waiting" no "issue edit 103 --add-label claude"
expect_called "start-waiting-issues.sh, an agent has it" no "issue edit 105 --add-label claude"
expect_called "start-waiting-issues.sh, not waiting" no "issue edit 106 --add-label claude"
expect_called "start-waiting-issues.sh, a pull request" no "issue edit 109 --add-label claude"
expect_called "start-waiting-issues.sh, a part still open" no "issue close 50 --reason completed"
echo '[{"number": 101, "state": "open"}, {"number": 102, "state": "closed"}]' > "$work/fixtures/repos_owner_app_issues_50_sub_issues.json"
run_gh "$scripts/start-waiting-issues.sh" 101 > /dev/null
expect_called "start-waiting-issues.sh, every part done" yes "issue close 50 --reason completed"
fixtures
run_gh "$scripts/start-waiting-issues.sh" 101 > /dev/null 2>&1 ||
  { echo "::error::start-waiting-issues.sh fails when nothing waits and #101 has no parent."; status=1; }

[ "$status" -ne 0 ] || echo "The scripts pass their tests."
exit $status
