#!/usr/bin/env bash
# Succeeds when an AI agent's error message, on standard input, says its plan ran out: Claude's usage limit ("You've
# hit your limit", "usage limit reached") or API credit, Copilot's AI credits or premium requests. Only the quota's
# reset fixes those, so the issue waits for it instead of failing.
set -euo pipefail

grep -qiE 'usage limit|limit reached|hit your limit|out of (extra )?usage|credit|quota|premium requests?'
