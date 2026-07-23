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
  '[ -z "${LD_AUDIT+x}" ]' \
  '[ -z "${LD_DEBUG+x}" ]' \
  '[ -z "${LD_PROFILE+x}" ]' \
  '[ -z "${GLIBC_TUNABLES+x}" ]' \
  '[ -z "${GCONV_PATH+x}" ]' \
  '[ -z "${LOCPATH+x}" ]' \
  '[ -z "${NLSPATH+x}" ]' \
  '[ -z "${LANGUAGE+x}" ]' \
  '[ -z "${LC_ADDRESS+x}" ]' \
  '[ -z "${LC_CTYPE+x}" ]' \
  '[ "$LANG" = C ]' \
  '[ "$LC_ALL" = C ]' \
  '[ -z "${TMP+x}" ]' \
  '[ -z "${TEMP+x}" ]' \
  '[ "$TMPDIR" = /tmp ]' \
  '[ "$(ulimit -c)" = 0 ]' \
  '[ -z "${PERL5OPT+x}" ]' \
  '[ -z "${PERL5LIB+x}" ]' \
  'for gallr_exported_name in $(compgen -e); do' \
  '  case "$gallr_exported_name" in SHELLOPTS|BASHOPTS) exit 94 ;; esac' \
  'done' \
  'case "$PATH" in' \
  '  /usr/bin:/bin:/usr/sbin:/sbin|/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin|/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin|/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:/usr/local/bin) ;;' \
  '  *) exit 93 ;;' \
  'esac' \
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
  export SHELLOPTS BASHOPTS

  LD_AUDIT=poisoned \
  LD_DEBUG=poisoned \
  LD_PROFILE=poisoned \
  GLIBC_TUNABLES=poisoned \
  GCONV_PATH="$TEST_ROOT/poisoned-gconv" \
  LOCPATH="$TEST_ROOT/poisoned-locale" \
  NLSPATH="$TEST_ROOT/poisoned-messages/%N" \
  LANGUAGE=poisoned \
  LANG=C.UTF-8 \
  LC_ADDRESS=C \
  LC_CTYPE=C \
  TMP="$TEST_ROOT/poisoned-tmp" \
  TEMP="$TEST_ROOT/poisoned-temp" \
  TMPDIR="$TEST_ROOT/poisoned-tmpdir" \
  PERL5OPT=poisoned \
  PERL5LIB=poisoned \
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

# Reproduce the credential-runner bootstrap shape with hostile dirname, git,
# and helper candidates at the front of the caller's PATH. The central
# launcher must select the fixed system tools and source the helper adjacent to
# the real script instead of the attacker tree.
POISON_BIN="$TEST_ROOT/poison-bin"
ATTACKER_ROOT="$TEST_ROOT/attacker-tree"
PROBE_ROOT="$TEST_ROOT/probe-tree"
POISON_PATH_MARKER="$TEST_ROOT/poison-path-ran"
ATTACKER_HELPER_MARKER="$TEST_ROOT/attacker-helper-ran"
PROBE_MARKER="$TEST_ROOT/bootstrap-probe-passed"
mkdir -m 0700 "$POISON_BIN" "$ATTACKER_ROOT" "$ATTACKER_ROOT/lib" \
  "$PROBE_ROOT" "$PROBE_ROOT/lib"

printf '%s\n' \
  '#!/bin/sh' \
  ": > \"$POISON_PATH_MARKER\"" \
  "printf '%s\\n' \"$ATTACKER_ROOT\"" \
  >"$POISON_BIN/dirname"
printf '%s\n' \
  '#!/bin/sh' \
  ": > \"$POISON_PATH_MARKER\"" \
  'exit 97' \
  >"$POISON_BIN/git"
chmod 0700 "$POISON_BIN/dirname" "$POISON_BIN/git"

printf '%s\n' \
  ": > \"$ATTACKER_HELPER_MARKER\"" \
  'GALLR_HELPER_ORIGIN=attacker' \
  >"$ATTACKER_ROOT/lib/reviewed-toolchain.sh"
printf '%s\n' \
  'GALLR_HELPER_ORIGIN=reviewed' \
  >"$PROBE_ROOT/lib/reviewed-toolchain.sh"
printf '%s\n' \
  '#!/bin/bash' \
  'set -euo pipefail' \
  'probe_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)' \
  'source "$probe_dir/lib/reviewed-toolchain.sh"' \
  '[ "$GALLR_HELPER_ORIGIN" = reviewed ]' \
  'git --version >/dev/null' \
  ': > "$GALLR_BOOTSTRAP_PROBE_MARKER"' \
  >"$PROBE_ROOT/run.sh"
chmod 0700 "$PROBE_ROOT/run.sh"

PATH="$POISON_BIN:$PATH" \
GALLR_BOOTSTRAP_PROBE_MARKER="$PROBE_MARKER" \
  "$LAUNCHER" "$PROBE_ROOT/run.sh"
[ -f "$PROBE_MARKER" ] || {
  printf '%s\n' 'the fixed-PATH bootstrap probe did not complete' >&2
  exit 1
}
[ ! -e "$POISON_PATH_MARKER" ] || {
  printf '%s\n' 'a hostile PATH bootstrap utility executed' >&2
  exit 1
}
[ ! -e "$ATTACKER_HELPER_MARKER" ] || {
  printf '%s\n' 'a helper outside the reviewed script tree was sourced' >&2
  exit 1
}

printf '%s\n' 'run-safe-bash startup-injection test: PASS'
