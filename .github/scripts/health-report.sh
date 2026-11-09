#!/usr/bin/env bash
# Appends the monthly health report of the app checked out here to $1 (Markdown): Gradle deprecations, the credentials
# about to expire (an issue for the owner 30 days ahead), and the pull requests stuck waiting.
# Needs GH_TOKEN (issues: write) and GH_REPO; COPILOT_GITHUB_TOKEN when the app has one.
set -euo pipefail

report="$1"
mkdir -p "$(dirname "$report")"
{
  echo
  echo "## Gradle deprecations"
  echo
  # Without the tokens: the build runs the code of the app and of everything it depends on
  env -u GH_TOKEN -u GITHUB_TOKEN -u COPILOT_GITHUB_TOKEN ./gradlew help --warning-mode=all 2>&1 |
    grep -i "deprecat" | sort -u | sed 's/^/- /' || echo "- none"
  echo
  echo "## Credentials"
  echo
  if [ -n "${COPILOT_GITHUB_TOKEN:-}" ]; then
    expiry="$(curl -sI -H "Authorization: Bearer $COPILOT_GITHUB_TOKEN" https://api.github.com/user \
      | grep -i '^github-authentication-token-expiration:' | cut -d' ' -f2- | tr -d '\r' || true)"
    if [ -n "$expiry" ]; then
      days=$(( ( $(date -d "$expiry" +%s) - $(date +%s) ) / 86400 ))
      echo "- COPILOT_GITHUB_TOKEN expires in $days days ($expiry)"
      title="Renew COPILOT_GITHUB_TOKEN"
      if [ "$days" -lt 30 ] && [ -z "$(gh issue list --state open --search "in:title \"$title\"" --json number --jq '.[0].number // empty')" ]; then
        gh issue create --title "$title (expires in $days days)" \
          --body "Create a new fine-grained token from the Copilot account and store it again: app-machinery's docs/04-renewals.md says how." || true
      fi
    else
      echo "- COPILOT_GITHUB_TOKEN: no expiry reported"
    fi
  else
    echo "- COPILOT_GITHUB_TOKEN: not set, so Copilot doesn't take over when Claude can't"
  fi
  echo
  echo "## Stuck items"
  echo
  # Numbers, not titles: the review agent reads this report, and a title can be anyone's (GitHub links the numbers)
  gh pr list --label needs-human --json number --jq '.[] | "- #\(.number) (needs-human)"'
  gh pr list --author "app/dependabot" --json number,createdAt \
    --jq '.[] | select((now - (.createdAt | fromdateiso8601)) > 1209600) | "- #\(.number) (open for more than 14 days)"'
} >> "$report"
cat "$report"
