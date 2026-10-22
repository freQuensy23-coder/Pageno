#!/usr/bin/env bash
# Runs the Telegram E2E against the build under test and against the known-bad control build.
# Both runs always execute; the suite fails if either expectation is not met.
set -u
out=${1:-telegram-e2e-results}
status=0
python3 ci/e2e/run_telegram_e2e.py --apk "$(ls pageno-head/*.apk | head -1)" \
  --label head --expect pass --out "$out" || status=1
python3 ci/e2e/run_telegram_e2e.py --apk "$(ls pageno-known-bad/*.apk | head -1)" \
  --label known-bad --expect bug --out "$out" || status=1
exit $status
