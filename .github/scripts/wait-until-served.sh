#!/usr/bin/env bash
# Waits until the machinery's site serves every file of the plugins' version <version>. GitHub Pages builds the site and
# spreads it through its CDN some minutes after the push, and an app that asks for the version before then is told "not
# found", an answer the CDN may keep for ten minutes: a release is done only once its plugins are served. The files are
# those the publish put under <dir> (the site's maven/), fetched from <url>, the same place on the site. Fails when they
# aren't all served within WAIT_SECONDS.
# Usage: wait-until-served.sh <dir> <url> <version>
# WAIT_SECONDS (1200) and POLL_SECONDS (20) change the wait.
set -euo pipefail

dir="$1"
url="${2%/}"
version="$3"
limit="${WAIT_SECONDS:-1200}"
pause="${POLL_SECONDS:-20}"

mapfile -t files < <(cd "$dir" && find . -path "*/$version/*" -type f | sed 's|^\./||' | sort)
if [ "${#files[@]}" -eq 0 ]; then
  echo "::error title=No plugins to wait for::No file of version $version under $dir."
  exit 1
fi

waited=0
while :; do
  missing=()
  for file in "${files[@]}"; do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$url/$file" || true)"
    [ "$code" = "200" ] || missing+=("$file")
  done
  if [ "${#missing[@]}" -eq 0 ]; then
    echo "The site serves the ${#files[@]} files of $version."
    exit 0
  fi
  if [ "$waited" -ge "$limit" ]; then
    echo "::error title=The plugins aren't served::After ${limit} s the site still doesn't serve ${#missing[@]} of the ${#files[@]} files of $version, among them ${missing[0]}."
    exit 1
  fi
  echo "The site doesn't serve ${#missing[@]} of the ${#files[@]} files of $version yet."
  sleep "$pause"
  waited=$((waited + pause))
done
