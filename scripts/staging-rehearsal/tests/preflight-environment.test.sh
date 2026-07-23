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
HEAD_COMMIT=$($REAL_GIT -C "$REPO_ROOT" rev-parse HEAD)
STAGING_REF=aaaaaaaaaaaaaaaaaaaa
PRODUCTION_REF=bbbbbbbbbbbbbbbbbbbb

cleanup() {
  case "$TEST_ROOT" in
    "$TEST_PARENT"/gallr-preflight-environment.*) rm -rf -- "$TEST_ROOT" ;;
    *) printf 'Refusing unsafe test cleanup path: %s\n' "$TEST_ROOT" >&2 ;;
  esac
}
trap cleanup EXIT HUP INT TERM

digest_text() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  else
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  fi
}

assert_failed_with() {
  expected=$1
  shift
  set +e
  output=$("$@" 2>&1)
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || {
    printf 'Preflight unexpectedly succeeded; expected: %s\n' "$expected" >&2
    exit 1
  }
  grep -Fq "$expected" <<< "$output"
}

run_preflight() {
  evidence_dir=$1
  executor=$2
  reviewer=$3
  reviewed_commit=$4
  governance_mode=${5-}
  first_confirmation=${6-}

  env_args=(
    PATH="$FAKE_BIN:$PATH"
    GIT_LOG="$GIT_LOG"
    GIT_DIR="$TEST_ROOT/rogue-git-dir"
    GIT_WORK_TREE="$TEST_ROOT/rogue-worktree"
    GIT_CONFIG_COUNT=1
    GIT_CONFIG_KEY_0=core.fsmonitor
    GIT_CONFIG_VALUE_0="$FAKE_BIN/curl"
    GALLR_EXPECTED_STAGING_PROJECT_REF="$STAGING_REF"
    GALLR_PRODUCTION_PROJECT_REF="$PRODUCTION_REF"
    GALLR_STAGING_EVIDENCE_DIR="$evidence_dir"
    GALLR_REVIEWED_COMMIT="$reviewed_commit"
    GALLR_CHANGE_RECORD=CR-PREFLIGHT-ENVIRONMENT
    GALLR_EXECUTOR="$executor"
    GALLR_REVIEWER="$reviewer"
    GALLR_REHEARSAL_RUN_ID=preflight-environment
    GALLR_STAGING_DATABASE_URL=must-not-reach-child
    DATABASE_URL=must-not-reach-child
    SUPABASE_ANON_KEY=must-not-reach-child
    STAGING_REF=must-not-reach-child
    PRODUCTION_REF=must-not-reach-child
    GOVERNANCE_MODE=must-not-reach-child
    SOLO_FIRST_CONFIRMATION=must-not-reach-child
  )
  if [[ -n "$governance_mode" ]]; then
    env_args+=(GALLR_GOVERNANCE_MODE="$governance_mode")
  fi
  if [[ -n "$first_confirmation" ]]; then
    env_args+=(GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION="$first_confirmation")
  fi

  env "${env_args[@]}" "$PREFLIGHT"
}

mkdir -m 700 "$FAKE_BIN"

cat > "$FAKE_BIN/git" <<EOF
#!/bin/sh
fail() { : > "$UNSAFE_MARKER"; exit 95; }
[ -z "\${GALLR_STAGING_DATABASE_URL:-}" ] || fail
[ -z "\${DATABASE_URL:-}" ] || fail
[ -z "\${SUPABASE_ANON_KEY:-}" ] || fail
[ -z "\${STAGING_REF:-}" ] || fail
[ -z "\${PRODUCTION_REF:-}" ] || fail
[ -z "\${GALLR_GOVERNANCE_MODE:-}" ] || fail
[ -z "\${GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION:-}" ] || fail
[ -z "\${GOVERNANCE_MODE:-}" ] || fail
[ -z "\${SOLO_FIRST_CONFIRMATION:-}" ] || fail
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
case " \$* " in
  *' status --porcelain=v1 '*) exit 0 ;;
esac
exec "$REAL_GIT" "\$@"
EOF
chmod 700 "$FAKE_BIN/git"

cat > "$FAKE_BIN/supabase" <<EOF
#!/bin/sh
fail() { : > "$UNSAFE_MARKER"; exit 95; }
[ -z "\${GALLR_GOVERNANCE_MODE:-}" ] || fail
[ -z "\${GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION:-}" ] || fail
[ -z "\${GOVERNANCE_MODE:-}" ] || fail
[ -z "\${SOLO_FIRST_CONFIRMATION:-}" ] || fail
case "\$*" in
  --version) printf '2.81.3\n' ;;
  'migration list --help') printf '%s\n' '--linked' ;;
  'test db --help') printf '%s\n' '--linked' ;;
  'db lint --help') printf '%s\n' '--linked' ;;
  'db advisors --help') printf '%s\n' '--linked' ;;
  'db push --help') printf '%s\n' '--dry-run' ;;
  'link --help') printf '%s\n' '--project-ref' ;;
  *) : > "$REMOTE_MARKER"; exit 99 ;;
esac
EOF
chmod 700 "$FAKE_BIN/supabase"

for command_name in psql curl; do
  cat > "$FAKE_BIN/$command_name" <<EOF
#!/bin/sh
: > "$REMOTE_MARKER"
exit 99
EOF
  chmod 700 "$FAKE_BIN/$command_name"
done

# Unset/default governance preserves the separated-human schema-1 behavior.
DEFAULT_EVIDENCE="$TEST_ROOT/default-evidence"
run_preflight \
  "$DEFAULT_EVIDENCE" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT" >/dev/null
grep -Fxq 'manifest_schema=1' "$DEFAULT_EVIDENCE/operator-manifest.txt"
! grep -Fq 'governance_mode=' "$DEFAULT_EVIDENCE/operator-manifest.txt"
! grep -Fq 'first_confirmation_sha256=' "$DEFAULT_EVIDENCE/operator-manifest.txt"

# Separated-human identities are compared case-insensitively.
assert_failed_with \
  'executor and reviewer must be different people' \
  run_preflight \
  "$TEST_ROOT/case-collision-evidence" \
  Preflight-Reviewer \
  preflight-reviewer \
  0000000000000000000000000000000000000000

SOLO_CONFIRMATION="INTENT STAGING $STAGING_REF NOT PRODUCTION $PRODUCTION_REF $HEAD_COMMIT ACCEPT_NO_INDEPENDENT_REVIEW"
SOLO_EVIDENCE="$TEST_ROOT/solo-evidence"
run_preflight \
  "$SOLO_EVIDENCE" \
  hanshin-lee \
  hanshin-lee \
  "$HEAD_COMMIT" \
  solo_operator \
  "$SOLO_CONFIRMATION" >/dev/null

SOLO_MANIFEST="$SOLO_EVIDENCE/operator-manifest.txt"
grep -Fxq 'manifest_schema=2' "$SOLO_MANIFEST"
grep -Fxq 'governance_mode=solo_operator' "$SOLO_MANIFEST"
grep -Fxq 'human_reviewer_count=0' "$SOLO_MANIFEST"
grep -Fxq 'automation_is_independent_human_review=false' "$SOLO_MANIFEST"
grep -Fxq 'residual_risk_accepted=true' "$SOLO_MANIFEST"
grep -Fxq 'minimum_cooldown_seconds=900' "$SOLO_MANIFEST"
grep -Fxq 'destructive_actions=forbidden' "$SOLO_MANIFEST"
grep -Fxq "first_confirmation_sha256=$(digest_text "$SOLO_CONFIRMATION")" "$SOLO_MANIFEST"
! grep -Fq "$SOLO_CONFIRMATION" "$SOLO_MANIFEST"
! grep -Fq "$STAGING_REF" "$SOLO_MANIFEST"
! grep -Fq "$PRODUCTION_REF" "$SOLO_MANIFEST"

LINK_LINE=$(grep -nF 'link the CLI explicitly to the staging project' "$SOLO_EVIDENCE/rehearsal-plan.txt" | cut -d: -f1)
MARKER_LINE=$(grep -nF 'install its expiring marker' "$SOLO_EVIDENCE/rehearsal-plan.txt" | cut -d: -f1)
[[ "$LINK_LINE" -lt "$MARKER_LINE" ]] || {
  printf 'The rehearsal plan must link the CLI before installing the marker.\n' >&2
  exit 1
}

assert_failed_with \
  'solo-operator executor and reviewer must be the same exact identity' \
  run_preflight \
  "$TEST_ROOT/solo-distinct-evidence" \
  hanshin-lee \
  another-operator \
  0000000000000000000000000000000000000000 \
  solo_operator \
  "INTENT STAGING $STAGING_REF NOT PRODUCTION $PRODUCTION_REF 0000000000000000000000000000000000000000 ACCEPT_NO_INDEPENDENT_REVIEW"

assert_failed_with \
  'GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION does not match the exact solo-operator intent' \
  run_preflight \
  "$TEST_ROOT/solo-wrong-confirmation-evidence" \
  hanshin-lee \
  hanshin-lee \
  0000000000000000000000000000000000000000 \
  solo_operator \
  'wrong confirmation'

assert_failed_with \
  'unsupported GALLR_GOVERNANCE_MODE' \
  run_preflight \
  "$TEST_ROOT/unsupported-mode-evidence" \
  preflight-executor \
  preflight-reviewer \
  0000000000000000000000000000000000000000 \
  invented_mode

[[ ! -e "$UNSAFE_MARKER" ]] || {
  printf 'A child inherited unsafe target, credential, or governance state.\n' >&2
  exit 1
}
[[ ! -e "$REMOTE_MARKER" ]] || {
  printf 'Preflight invoked a remote-capable command.\n' >&2
  exit 1
}

printf 'PASS: preflight preserves schema 1 and safely supports explicit solo-operator governance.\n'
