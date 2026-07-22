#!/usr/bin/env bash
set -euo pipefail
umask 077

TEST_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
PREFLIGHT=$(cd -- "$TEST_DIR/.." && pwd -P)/preflight.sh
REPO_ROOT=$(cd -- "$TEST_DIR/../../.." && pwd -P)
TEST_PARENT=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
TEST_ROOT=$(mktemp -d "$TEST_PARENT/gallr-preflight-environment.XXXXXX")
FAKE_BIN="$TEST_ROOT/bin"
GIT_LOG="$TEST_ROOT/git.log"
UNSAFE_MARKER="$TEST_ROOT/unsafe-child-environment"
REMOTE_MARKER="$TEST_ROOT/remote-command-invoked"
REAL_GIT=$(command -v git)

cleanup() {
  case "$TEST_ROOT" in
    "$TEST_PARENT"/gallr-preflight-environment.*) rm -rf -- "$TEST_ROOT" ;;
    *) printf 'Refusing unsafe test cleanup path: %s\n' "$TEST_ROOT" >&2 ;;
  esac
}
trap cleanup EXIT HUP INT TERM

mkdir -m 700 "$FAKE_BIN"

cat > "$FAKE_BIN/git" <<EOF
#!/bin/sh
fail() { : > "$UNSAFE_MARKER"; exit 95; }
[ -z "\${GALLR_STAGING_DATABASE_URL:-}" ] || fail
[ -z "\${DATABASE_URL:-}" ] || fail
[ -z "\${SUPABASE_ANON_KEY:-}" ] || fail
[ -z "\${STAGING_REF:-}" ] || fail
[ -z "\${PRODUCTION_REF:-}" ] || fail
[ -z "\${GIT_DIR:-}" ] || fail
[ -z "\${GIT_WORK_TREE:-}" ] || fail
[ "\${GIT_CONFIG_COUNT:-}" = 0 ] || fail
[ "\${GIT_CONFIG_GLOBAL:-}" = /dev/null ] || fail
[ "\${GIT_CONFIG_NOSYSTEM:-}" = 1 ] || fail
[ "\${1:-}" = -c ] || fail
[ "\${2:-}" = core.fsmonitor=false ] || fail
[ "\${3:-}" = -c ] || fail
[ "\${4:-}" = core.hooksPath=/dev/null ] || fail
[ "\${5:-}" = -c ] || fail
[ "\${6:-}" = core.excludesFile=/dev/null ] || fail
[ "\${7:-}" = -c ] || fail
[ "\${8:-}" = core.attributesFile=/dev/null ] || fail
printf 'git\n' >> "$GIT_LOG"
exec "$REAL_GIT" "\$@"
EOF
chmod 700 "$FAKE_BIN/git"

for command_name in psql supabase curl; do
  cat > "$FAKE_BIN/$command_name" <<EOF
#!/bin/sh
: > "$REMOTE_MARKER"
exit 99
EOF
  chmod 700 "$FAKE_BIN/$command_name"
done

set +e
output=$(
  env \
    PATH="$FAKE_BIN:$PATH" \
    GIT_LOG="$GIT_LOG" \
    GIT_DIR="$TEST_ROOT/rogue-git-dir" \
    GIT_WORK_TREE="$TEST_ROOT/rogue-worktree" \
    GIT_CONFIG_COUNT=1 \
    GIT_CONFIG_KEY_0=core.fsmonitor \
    GIT_CONFIG_VALUE_0="$FAKE_BIN/curl" \
    GALLR_EXPECTED_STAGING_PROJECT_REF=aaaaaaaaaaaaaaaaaaaa \
    GALLR_PRODUCTION_PROJECT_REF=bbbbbbbbbbbbbbbbbbbb \
    GALLR_STAGING_EVIDENCE_DIR="$TEST_ROOT/evidence-not-created" \
    GALLR_REVIEWED_COMMIT=0000000000000000000000000000000000000000 \
    GALLR_CHANGE_RECORD=CR-PREFLIGHT-ENVIRONMENT \
    GALLR_EXECUTOR=preflight-executor \
    GALLR_REVIEWER=preflight-reviewer \
    GALLR_REHEARSAL_RUN_ID=preflight-environment \
    GALLR_STAGING_DATABASE_URL=must-not-reach-child \
    DATABASE_URL=must-not-reach-child \
    SUPABASE_ANON_KEY=must-not-reach-child \
    STAGING_REF=must-not-reach-child \
    PRODUCTION_REF=must-not-reach-child \
      "$PREFLIGHT" 2>&1
)
status=$?
set -e

[[ "$status" -ne 0 ]] || {
  printf 'Preflight unexpectedly accepted an unreviewed commit.\n' >&2
  exit 1
}
grep -Fq 'GALLR_REVIEWED_COMMIT must exactly equal the current full commit' <<< "$output"
[[ ! -e "$UNSAFE_MARKER" ]] || {
  printf 'A Git child inherited unsafe target or credential state.\n' >&2
  exit 1
}
[[ ! -e "$REMOTE_MARKER" ]] || {
  printf 'Preflight invoked a remote-capable command before local identity failed.\n' >&2
  exit 1
}
[[ "$(wc -l < "$GIT_LOG" | tr -d ' ')" == 2 ]] || {
  printf 'Expected exactly repository-root and HEAD Git checks.\n' >&2
  exit 1
}
[[ ! -e "$TEST_ROOT/evidence-not-created" ]] || {
  printf 'Preflight created evidence before commit identity passed.\n' >&2
  exit 1
}

printf 'PASS: preflight sanitizes Git and credential state before repository identity checks.\n'
