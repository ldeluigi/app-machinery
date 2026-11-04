#!/usr/bin/env bash
# Stores an app's secrets and variables from a file on your computer, each where the workflows read it:
#   - the agents' and the bot's secrets as Actions secrets and Dependabot secrets (runs Dependabot starts read only those);
#   - the release keys in the `release` environment (only the default branch's runs reach it);
#   - the plain settings as Actions variables;
#   - any other NAME=value as an Actions secret.
# The file holds NAME=value lines (docs/02-new-app.md lists them); `NAME=@path` reads the value from that file, for the
# bot App's .pem key. Blank lines and lines starting with # are skipped. Nothing is printed but the names.
# Usage: set-secrets.sh <owner/repo> <file>      Needs `gh` signed in as the repository's owner.
set -euo pipefail

repo="$1"
file="$2"

shared_secrets=" GH_APP_PRIVATE_KEY CLAUDE_CODE_OAUTH_TOKEN ANTHROPIC_API_KEY COPILOT_GITHUB_TOKEN "
release_secrets=" UPLOAD_KEYSTORE_BASE64 UPLOAD_KEYSTORE_PASSWORD UPLOAD_KEY_ALIAS UPLOAD_KEY_PASSWORD PLAY_SERVICE_ACCOUNT_JSON ASC_KEY_ID ASC_ISSUER_ID ASC_PRIVATE_KEY "
variables=" GH_APP_CLIENT_ID GH_APP_LOGIN APP_ID APP_NAME GCP_WIF_PROVIDER GCP_SERVICE_ACCOUNT PLAY_TRACK APPLE_TEAM_ID IOS_TRACK "

while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in ''|'#'*) continue ;; esac
  name="${line%%=*}"
  value="${line#*=}"
  if [ "${value:0:1}" = "@" ]; then
    value="$(cat "${value:1}")"
  fi
  if [[ "$variables" == *" $name "* ]]; then
    gh variable set "$name" --repo "$repo" --body "$value"
    echo "$name: Actions variable"
  elif [[ "$release_secrets" == *" $name "* ]]; then
    printf '%s' "$value" | gh secret set "$name" --repo "$repo" --env release
    echo "$name: release environment secret"
  elif [[ "$shared_secrets" == *" $name "* ]]; then
    printf '%s' "$value" | gh secret set "$name" --repo "$repo"
    printf '%s' "$value" | gh secret set "$name" --repo "$repo" --app dependabot
    echo "$name: Actions and Dependabot secret"
  else
    printf '%s' "$value" | gh secret set "$name" --repo "$repo"
    echo "$name: Actions secret"
  fi
done < "$file"
