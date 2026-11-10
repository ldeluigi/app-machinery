#!/usr/bin/env bash
# Stores an app's secrets and variables, each where the workflows read it:
#   - the agents' and the bot's secrets as Actions secrets and Dependabot secrets (runs Dependabot starts read only those);
#   - the release keys in the `release` environment (only the default branch's runs reach it);
#   - the plain settings as Actions variables;
#   - any other name as an Actions secret.
# Usage, with `gh` signed in as the repositories' owner:
#   set-secrets.sh <owner/repo>            asks for each value in turn: paste it, or press Enter to keep what is there
#   set-secrets.sh <owner/repo> NAME...    asks for these only: a renewed token, or a secret of the app's own
#   set-secrets.sh <owner/repo> <file>     reads NAME=value lines instead; `NAME=@path` reads the value from that file
#                                          (the upload key's .jks as it is). Blank lines and lines starting with # are
#                                          skipped.
# Several repositories, separated by commas (<owner/a>,<owner/b>), get the same values: a renewed token in one go.
# Secrets are never shown: nothing is printed but their names.
set -euo pipefail

usage="Usage: set-secrets.sh <owner/repo>[,<owner/repo>...] [NAME... | <file>]"
[ $# -gt 0 ] || { echo "$usage" >&2; exit 1; }
IFS=',' read -ra repos <<< "$1"
shift

shared_secrets=" GH_APP_PRIVATE_KEY CLAUDE_CODE_OAUTH_TOKEN ANTHROPIC_API_KEY COPILOT_GITHUB_TOKEN "
release_secrets=" UPLOAD_KEYSTORE_BASE64 UPLOAD_KEYSTORE_PASSWORD UPLOAD_KEY_ALIAS UPLOAD_KEY_PASSWORD PLAY_SERVICE_ACCOUNT_JSON ASC_KEY_ID ASC_ISSUER_ID ASC_PRIVATE_KEY "
variables=" GH_APP_CLIENT_ID GH_APP_LOGIN GCP_WIF_PROVIDER GCP_SERVICE_ACCOUNT PLAY_TRACK APPLE_TEAM_ID IOS_TRACK "
# Asked for as a file (dragged onto the window) or pasted over several lines
multiline=" GH_APP_PRIVATE_KEY PLAY_SERVICE_ACCOUNT_JSON ASC_PRIVATE_KEY "
# A binary file, stored as base64
keystore=" UPLOAD_KEYSTORE_BASE64 "

from_file() { # <name> <path>: the file's content, the keystore's as base64 unless it is already
  # A byte outside base64's alphabet makes it binary (tr, unlike grep, counts NUL bytes too)
  if [[ "$keystore" == *" $1 "* ]] && [ -n "$(LC_ALL=C tr -d 'A-Za-z0-9+/=\r\n\t ' < "$2" | head -c 1 | tr '\0' x)" ]; then
    base64 < "$2" | tr -d '\r\n'
  else
    cat "$2"
  fi
}

store() { # <name> <value>, in every repository
  local name="$1" value="$2" repo where
  for repo in "${repos[@]}"; do
    if [[ "$variables" == *" $name "* ]]; then
      gh variable set "$name" --repo "$repo" --body "$value" > /dev/null
      where="Actions variable"
    elif [[ "$release_secrets" == *" $name "* ]]; then
      if ! gh api "repos/$repo/environments/release" --silent 2> /dev/null; then
        echo "$name: NOT STORED. $repo has no release environment yet: apply the settings first (docs/02-new-app.md, step 3), then run this again." >&2
        continue
      fi
      printf '%s' "$value" | gh secret set "$name" --repo "$repo" --env release > /dev/null
      where="release environment secret"
    elif [[ "$shared_secrets" == *" $name "* ]]; then
      printf '%s' "$value" | gh secret set "$name" --repo "$repo" > /dev/null
      printf '%s' "$value" | gh secret set "$name" --repo "$repo" --app dependabot > /dev/null
      where="Actions and Dependabot secret"
    else
      printf '%s' "$value" | gh secret set "$name" --repo "$repo" > /dev/null
      where="Actions secret"
    fi
    if [ ${#repos[@]} -gt 1 ]; then where="$where in $repo"; fi
    echo "$name: $where"
  done
}

# From a file
if [ $# -eq 1 ] && [[ ! "$1" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
  [ -f "$1" ] || { echo "No file $1. $usage" >&2; exit 1; }
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in '' | '#'*) continue ;; esac
    name="${line%%=*}"
    value="${line#*=}"
    if [ "${value:0:1}" = "@" ]; then
      value="$(from_file "$name" "${value:1}")"
    fi
    store "$name" "$value"
  done < "$1"
  exit 0
fi

# Asking, one value at a time
[ -t 0 ] || { echo "Run it in a terminal, to paste the values, or give it a file. $usage" >&2; exit 1; }
for name in "$@"; do
  [[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]] || { echo "$name isn't a name like CLAUDE_CODE_OAUTH_TOKEN. $usage" >&2; exit 1; }
done
wanted=" $* "
trap 'stty echo 2>/dev/null || true' EXIT

# What is there already, shown for one repository
here=""
if [ ${#repos[@]} -eq 1 ]; then here="${repos[0]}"; fi
list() { # [gh secret list's options...]: the names, between spaces
  [ -n "$here" ] || return 0
  printf ' %s ' "$({ gh secret list --repo "$here" "$@" --json name --jq '.[].name' 2>/dev/null || true; } | tr '\n' ' ')"
}
actions_secrets="$(list)"
environment_secrets="$(list --env release)"

# A dragged file arrives as its path, quoted when it has spaces, and in Windows' form from some terminals
as_path() {
  local path="$1"
  path="${path#[\'\"]}"
  path="${path%[\'\"]}"
  if [[ "$path" =~ ^[A-Za-z]:[\\/] ]] && command -v cygpath > /dev/null; then
    path="$(cygpath -u "$path")"
  fi
  printf '%s' "$path"
}

ask() { # <name> <pattern a right value matches, or empty> <what to paste>
  local name="$1" pattern="$2" hint="$3" value line path current="" answer
  if [[ "$variables" == *" $name "* ]]; then
    if [ -n "$here" ]; then current="$(gh variable get "$name" --repo "$here" 2>/dev/null || true)"; fi
    printf '\n%s: %s\n' "$name" "$hint"
    read -r -p "  ${current:+[now $current] }> " value
    value="${value%$'\r'}"
  else
    if [ -z "$here" ]; then
      current=""
    elif [[ "$actions_secrets$environment_secrets" == *" $name "* ]]; then
      current=" (set already)"
    else
      current=" (not set yet)"
    fi
    printf '\n%s%s: %s\n' "$name" "$current" "$hint"
    if [[ "$multiline$keystore" == *" $name "* ]]; then
      echo "  Drag its file onto this window and press Enter, or paste it and press Enter on an empty line."
    fi
    printf '  Hidden as you paste > '
    stty -echo
    IFS= read -r value
    value="${value%$'\r'}"
    path="$(as_path "$value")"
    if [ -n "$value" ] && [[ "$multiline$keystore" == *" $name "* ]] && [ -f "$path" ]; then
      value="$(from_file "$name" "$path")"
      printf '\n  read %s' "$path"
    elif [ -n "$value" ] && [[ "$multiline" == *" $name "* ]]; then
      while IFS= read -r line; do
        line="${line%$'\r'}"
        [ -n "$line" ] || break
        value="$value"$'\n'"$line"
      done
    fi
    stty echo
    [ -z "$value" ] || printf '\n  %s characters' "${#value}"
    echo
  fi
  if [ -z "$value" ]; then
    echo "  unchanged"
    return
  fi
  if [ -n "$pattern" ] && [[ ! "$value" =~ $pattern ]]; then
    read -r -p "  That doesn't look like $hint. Store it anyway? [y/N] " answer
    [[ "$answer" == [yY]* ]] || { echo "  not stored"; return; }
  fi
  printf '  '
  store "$name" "$value"
}

section=""
asked=" "
question() { # <name> <pattern> <hint>, asked when no names were given or this one was
  [ "$wanted" = "  " ] || [[ "$wanted" == *" $1 "* ]] || return 0
  if [ -n "$section" ]; then
    printf '\n== %s ==\n' "$section"
    section=""
  fi
  asked="$asked$1 "
  ask "$@"
}

echo "Secrets and variables of ${repos[*]}. Paste each value and press Enter; Enter alone keeps what is there."

section="The bot App (docs/01-once-per-account.md, step 2)"
question GH_APP_CLIENT_ID '^Iv' "its Client ID, on the App's page (Iv23...)"
question GH_APP_LOGIN '\[bot\]$' "its name followed by [bot], like my-bot[bot]"
question GH_APP_PRIVATE_KEY 'PRIVATE KEY' "its private key, the .pem file"

section="The agents (docs/01-once-per-account.md, steps 4 and 5)"
question CLAUDE_CODE_OAUTH_TOKEN '^sk-ant-' "the token claude setup-token printed (sk-ant-oat01-...)"
question ANTHROPIC_API_KEY '^sk-ant-' "an Anthropic API key, only if you use one instead of that token (sk-ant-api...)"
question COPILOT_GITHUB_TOKEN '^github_pat_' "the fine-grained token for Copilot (github_pat_...)"

section="The Android upload key (docs/02-new-app.md, step 4)"
question UPLOAD_KEYSTORE_BASE64 '^[A-Za-z0-9+/=]+$' "the upload.jks file"
question UPLOAD_KEYSTORE_PASSWORD '' "its password"
question UPLOAD_KEY_ALIAS '' "the key's alias: upload, unless you chose another"
question UPLOAD_KEY_PASSWORD '' "the key's password: the same, unless you chose another"

section="Google Play (docs/01-once-per-account.md, step 7, and docs/02-new-app.md, step 8)"
question GCP_WIF_PROVIDER '^projects/[0-9]+/locations/global/workloadIdentityPools/' \
  "projects/<project-number>/locations/global/workloadIdentityPools/github/providers/github"
question GCP_SERVICE_ACCOUNT '@.+\.iam\.gserviceaccount\.com$' "publisher@<project-id>.iam.gserviceaccount.com"
question PLAY_TRACK '^(internal|alpha|beta|production)$' \
  "the track releases go to: internal, alpha, beta or production (none before the app is on Google Play)"

section="Apple (docs/01-once-per-account.md, step 8)"
question APPLE_TEAM_ID '^[A-Z0-9]{10}$' "the Team ID, in Membership details (10 letters and digits)"
question ASC_KEY_ID '^[A-Z0-9]{10}$' "the App Store Connect API key's Key ID (10 letters and digits)"
question ASC_ISSUER_ID '^[0-9a-f-]{36}$' "the Issuer ID, above the list of keys"
question ASC_PRIVATE_KEY 'PRIVATE KEY' "the key's AuthKey_....p8 file"

# Names of the app's own
for name in "$@"; do
  [[ "$asked" == *" $name "* ]] || ask "$name" '' "a secret of the app's own"
done
echo
echo "Done. The workflows' next runs use the new values."
