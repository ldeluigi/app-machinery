#!/usr/bin/env bash
# Applies the machinery's repository settings (settings/) to one repository: merge options, workflow permissions,
# Dependabot security updates, the `release` environment (default branch only), the automation's labels, and the
# default branch's ruleset (pull requests, required checks). Running it again changes only what drifted.
# Usage: apply-settings.sh <owner/repo> [ruleset.json]   (settings/ruleset.json by default)
# Needs GH_TOKEN with Administration: write on the repository (the bot App's, or the owner's own `gh` login).
set -euo pipefail

repo="$1"
settings="$(cd "$(dirname "$0")/../../settings" && pwd)"
ruleset="${2:-$settings/ruleset.json}"
default="$(gh api "repos/$repo" --jq .default_branch)"
echo "Settings for $repo (default branch $default)"

gh api -X PATCH "repos/$repo" --input "$settings/repository.json" --silent
gh api -X PUT "repos/$repo/actions/permissions/workflow" --input "$settings/workflow-permissions.json" --silent
gh api -X PUT "repos/$repo/vulnerability-alerts" --silent
gh api -X PUT "repos/$repo/automated-security-fixes" --silent

# The release signing keys live in this environment, which only the default branch's runs can use
gh api -X PUT "repos/$repo/environments/release" --silent --input - <<JSON
{"deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
JSON
if ! gh api "repos/$repo/environments/release/deployment-branch-policies" \
  --jq '.branch_policies[].name' | grep -qxF "$default"; then
  gh api -X POST "repos/$repo/environments/release/deployment-branch-policies" \
    -f name="$default" -f type=branch --silent
fi

while IFS=$'\t' read -r name color description; do
  [ -n "$name" ] || continue
  gh label create "$name" --color "$color" --description "$description" --force --repo "$repo" > /dev/null
done < "$settings/labels.tsv"

existing="$(gh api "repos/$repo/rulesets" --jq '.[] | select(.name == "Default") | .id')"
if [ -n "$existing" ]; then
  gh api -X PUT "repos/$repo/rulesets/$existing" --input "$ruleset" --silent
else
  gh api -X POST "repos/$repo/rulesets" --input "$ruleset" --silent
fi
echo "Done: $repo"
