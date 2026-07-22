#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
LAUNCHER="$REPO_ROOT/scripts/staging-rehearsal/run-safe-bash.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/gallr-safe-bash.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT

SENTINEL_PATH="$TEST_ROOT/sentinel"
STARTUP_LEAK_PATH="$TEST_ROOT/startup-leak"
FUNCTION_LEAK_PATH="$TEST_ROOT/function-leak"
TARGET_MARKER_PATH="$TEST_ROOT/target-started"
STARTUP_FILE="$TEST_ROOT/poisoned-startup.sh"
TARGET_SCRIPT="$TEST_ROOT/target.sh"

printf '%s\n' 'startup-injection-sentinel' >"$SENTINEL_PATH"

printf '%s\n' \
  'IFS= read -r gallr_startup_secret <"$GALLR_SENTINEL_PATH"' \
  'printf "%s\n" "$gallr_startup_secret" >"$GALLR_STARTUP_LEAK_PATH"' \
  >"$STARTUP_FILE"

printf '%s\n' \
  '#!/bin/bash' \
  'set -euo pipefail' \
  '[ ! -e "$GALLR_STARTUP_LEAK_PATH" ]' \
  '[ ! -e "$GALLR_FUNCTION_LEAK_PATH" ]' \
  '[ -z "${BASH_ENV+x}" ]' \
  '[ -z "${ENV+x}" ]' \
  '[ -z "${CDPATH+x}" ]' \
  '[ "$#" -eq 2 ]' \
  '[ "$1" = fixture-mode ]' \
  '[ "$2" = "argument with spaces" ]' \
  'if declare -F gallr_exported_probe >/dev/null; then gallr_exported_probe; fi' \
  '[ ! -e "$GALLR_FUNCTION_LEAK_PATH" ]' \
  'printf "%s\n" target-started-cleanly >"$GALLR_TARGET_MARKER_PATH"' \
  >"$TARGET_SCRIPT"
chmod 0700 "$TARGET_SCRIPT"

(
  gallr_exported_probe() {
    local gallr_function_secret
    IFS= read -r gallr_function_secret <"$GALLR_SENTINEL_PATH"
    printf '%s\n' "$gallr_function_secret" >"$GALLR_FUNCTION_LEAK_PATH"
  }
  export -f gallr_exported_probe
  /usr/bin/env | grep -Fq 'gallr_exported_probe' || {
    printf '%s\n' 'test setup did not export the injected function' >&2
    exit 1
  }
  export BASH_ENV="$STARTUP_FILE"
  export ENV="$STARTUP_FILE"
  export CDPATH="$TEST_ROOT"
  export GALLR_SENTINEL_PATH="$SENTINEL_PATH"
  export GALLR_STARTUP_LEAK_PATH="$STARTUP_LEAK_PATH"
  export GALLR_FUNCTION_LEAK_PATH="$FUNCTION_LEAK_PATH"
  export GALLR_TARGET_MARKER_PATH="$TARGET_MARKER_PATH"

  "$LAUNCHER" "$TARGET_SCRIPT" fixture-mode 'argument with spaces'
)

[ ! -e "$STARTUP_LEAK_PATH" ] || {
  printf '%s\n' 'BASH_ENV or ENV executed before the target script' >&2
  exit 1
}
[ ! -e "$FUNCTION_LEAK_PATH" ] || {
  printf '%s\n' 'an exported shell function executed before the target script' >&2
  exit 1
}
[ "$(cat "$TARGET_MARKER_PATH")" = 'target-started-cleanly' ] || {
  printf '%s\n' 'the clean target script did not run' >&2
  exit 1
}

printf '%s\n' 'run-safe-bash startup-injection test: PASS'
