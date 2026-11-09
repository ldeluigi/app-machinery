#!/usr/bin/env bash
# The scripts' own tests, which Self CI runs: out-of-credit.sh tells the agents' messages about a used-up quota (the
# issue waits for the reset) from every other error (a failure of the automation).
# Usage: test-scripts.sh
set -euo pipefail

scripts="$(cd "$(dirname "$0")" && pwd)"
status=0

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

[ "$status" -ne 0 ] || echo "The scripts pass their tests."
exit $status
