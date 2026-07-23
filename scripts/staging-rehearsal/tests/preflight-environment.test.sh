#!/usr/bin/env bash
set -euo pipefail
umask 077

TEST_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
SOURCE_REPO_ROOT=$(cd -- "$TEST_DIR/../../.." && pwd -P)
TEST_PARENT=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
TEST_ROOT=$(mktemp -d "$TEST_PARENT/gallr-preflight-environment.XXXXXX")
REPO_ROOT="$TEST_ROOT/repository"
PREFLIGHT="$REPO_ROOT/scripts/staging-rehearsal/preflight.sh"
FAKE_BIN="$TEST_ROOT/bin"
GIT_LOG="$TEST_ROOT/git.log"
UNSAFE_MARKER="$TEST_ROOT/unsafe-child-environment"
REMOTE_MARKER="$TEST_ROOT/remote-command-invoked"
REAL_GIT=$(command -v git)
REAL_MV=$(command -v mv)
REAL_NODE_SOURCE=$(node -p 'require("node:fs").realpathSync.native(process.execPath)')
REAL_NODE="$FAKE_BIN/node"
STAGING_REF=aaaaaaaaaaaaaaaaaaaa
PRODUCTION_REF=yhuhjxswjbrtmbpbrciq
ASSUME_UNCHANGED_PATH=scripts/staging-rehearsal/target-identity-policy.example
ASSUME_UNCHANGED_ACTIVE=false

cleanup() {
  local status=$1
  local failed_command=$2
  trap - EXIT
  trap '' HUP INT QUIT TERM
  if [[ "$ASSUME_UNCHANGED_ACTIVE" == true ]]; then
    "$REAL_GIT" -C "$REPO_ROOT" update-index --no-assume-unchanged -- \
      "$ASSUME_UNCHANGED_PATH" 2>/dev/null || true
  fi
  case "$TEST_ROOT" in
    "$TEST_PARENT"/gallr-preflight-environment.*) rm -rf -- "$TEST_ROOT" ;;
    *) printf 'Refusing unsafe test cleanup path: %s\n' "$TEST_ROOT" >&2 ;;
  esac
  if ((status != 0)); then
    printf 'preflight environment test failed after command: %s\n' \
      "$failed_command" >&2
  fi
  exit "$status"
}
trap 'cleanup "$?" "${BASH_COMMAND-unknown}"' EXIT
trap 'cleanup 129 "signal HUP"' HUP
trap 'cleanup 130 "signal INT"' INT
trap 'cleanup 131 "signal QUIT"' QUIT
trap 'cleanup 143 "signal TERM"' TERM

# Exercise Git metadata attacks only in a disposable repository. Copy the
# current tracked and reviewable untracked source bytes, commit that snapshot,
# and leave the caller's worktree, index, config, attributes, and refs untouched.
mkdir -m 700 "$REPO_ROOT"
while IFS= read -r -d '' relative_path; do
  mkdir -p "$REPO_ROOT/$(dirname "$relative_path")"
  cp -p "$SOURCE_REPO_ROOT/$relative_path" "$REPO_ROOT/$relative_path"
done < <(
  "$REAL_GIT" -C "$SOURCE_REPO_ROOT" \
    ls-files -z --cached --others --exclude-standard
)
"$REAL_GIT" -C "$REPO_ROOT" init -q
"$REAL_GIT" -C "$REPO_ROOT" config user.name 'Gallr Preflight Test'
"$REAL_GIT" -C "$REPO_ROOT" config user.email 'gallr-preflight@example.invalid'
"$REAL_GIT" -C "$REPO_ROOT" config commit.gpgsign false
"$REAL_GIT" -C "$REPO_ROOT" add -f -A
"$REAL_GIT" -C "$REPO_ROOT" commit -q -m 'synthetic preflight snapshot'
HEAD_COMMIT=$("$REAL_GIT" -C "$REPO_ROOT" rev-parse HEAD)

digest_text() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  else
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  fi
}

digest_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

file_mode() {
  local target=$1
  local value

  if value=$(stat -f '%Lp' "$target" 2>/dev/null); then
    printf '%s\n' "$value"
    return 0
  fi
  if value=$(stat -c '%a' "$target" 2>/dev/null); then
    printf '%s\n' "$value"
    return 0
  fi
  return 1
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
  reviewed_psql_path=${7-"$FAKE_BIN/psql"}
  reviewed_node_path=${8-"$REAL_NODE"}

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
    GALLR_REVIEWED_NODE_PATH="$reviewed_node_path"
    GALLR_REVIEWED_PSQL_PATH="$reviewed_psql_path"
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
cp "$REAL_NODE_SOURCE" "$REAL_NODE"
chmod 500 "$REAL_NODE"
NODE_LIBRARY_SOURCE=$(
  find "$(dirname "$REAL_NODE_SOURCE")/../lib" \
    -maxdepth 1 -type f -name 'libnode.*.dylib' -print -quit 2>/dev/null || true
)
if [[ -n "$NODE_LIBRARY_SOURCE" ]]; then
  mkdir -m 700 "$TEST_ROOT/lib"
  cp "$NODE_LIBRARY_SOURCE" "$TEST_ROOT/lib/"
  chmod 400 "$TEST_ROOT/lib/$(basename "$NODE_LIBRARY_SOURCE")"
fi
REAL_NODE_VERSION=$("$REAL_NODE" --version)
REAL_NODE_VERSION=${REAL_NODE_VERSION#v}

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
[ -z "\${GALLR_REVIEWED_NODE_PATH:-}" ] || fail
[ -z "\${GALLR_REVIEWED_PSQL_PATH:-}" ] || fail
[ -z "\${GOVERNANCE_MODE:-}" ] || fail
[ -z "\${SOLO_FIRST_CONFIRMATION:-}" ] || fail
[ -z "\${GIT_DIR:-}" ] || fail
[ -z "\${GIT_WORK_TREE:-}" ] || fail
[ "\${GIT_CONFIG_COUNT:-}" = 0 ] || fail
[ "\${GIT_CONFIG_GLOBAL:-}" = /dev/null ] || fail
[ "\${GIT_CONFIG_NOSYSTEM:-}" = 1 ] || fail
[ "\${1:-}" = --no-replace-objects ] || fail
[ "\${2:-}" = -c ] || fail
[ "\${3:-}" = core.fsmonitor=false ] || fail
[ "\${4:-}" = -c ] || fail
[ "\${5:-}" = core.hooksPath=/dev/null ] || fail
[ "\${6:-}" = -c ] || fail
[ "\${7:-}" = core.excludesFile=/dev/null ] || fail
[ "\${8:-}" = -c ] || fail
[ "\${9:-}" = core.attributesFile=/dev/null ] || fail
printf '%s\n' "\$*" >> "$GIT_LOG"
case " \$* " in
  *' status --porcelain=v1 '*) exit 0 ;;
  *' ls-files --error-unmatch '*) exit 0 ;;
esac
exec "$REAL_GIT" "\$@"
EOF
chmod 700 "$FAKE_BIN/git"

cat > "$FAKE_BIN/supabase" <<EOF
#!/bin/sh
fail() { : > "$UNSAFE_MARKER"; exit 95; }
[ -z "\${GALLR_GOVERNANCE_MODE:-}" ] || fail
[ -z "\${GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION:-}" ] || fail
[ -z "\${GALLR_REVIEWED_NODE_PATH:-}" ] || fail
[ -z "\${GALLR_REVIEWED_PSQL_PATH:-}" ] || fail
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

cat > "$FAKE_BIN/psql" <<EOF
#!/bin/sh
case "\${1-}" in
  --version) printf '%s\n' 'psql (PostgreSQL) 18.3' ;;
  --help)
    printf '%s\n' \
      '  -w, --no-password' \
      '  -1, --single-transaction'
    ;;
  *) : > "$REMOTE_MARKER"; exit 99 ;;
esac
EOF
chmod 700 "$FAKE_BIN/psql"

cat > "$FAKE_BIN/curl" <<EOF
#!/bin/sh
: > "$REMOTE_MARKER"
exit 99
EOF
chmod 700 "$FAKE_BIN/curl"

cat > "$FAKE_BIN/mv" <<EOF
#!/bin/sh
destination=
for argument do
  destination=\$argument
done
case "\$destination" in
  */publication-failure-evidence/rehearsal-plan.txt)
    if [ ! -e "$TEST_ROOT/publication-failure-injected" ]; then
      : > "$TEST_ROOT/publication-failure-injected"
      exit 97
    fi
    ;;
esac
exec "$REAL_MV" "\$@"
EOF
chmod 700 "$FAKE_BIN/mv"

# Unset/default governance preserves the separated-human schema-1 behavior.
DEFAULT_EVIDENCE="$TEST_ROOT/default-evidence"
run_preflight \
  "$DEFAULT_EVIDENCE" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT" >/dev/null
grep -Fxq 'manifest_schema=1' "$DEFAULT_EVIDENCE/operator-manifest.txt"
grep -Fxq "reviewed_node_path=$REAL_NODE" "$DEFAULT_EVIDENCE/operator-manifest.txt"
grep -Fxq \
  "reviewed_node_sha256=$(digest_file "$REAL_NODE")" \
  "$DEFAULT_EVIDENCE/operator-manifest.txt"
grep -Fxq "reviewed_node_version=$REAL_NODE_VERSION" \
  "$DEFAULT_EVIDENCE/operator-manifest.txt"
grep -Fxq 'reviewed_node_minimum_major=18' \
  "$DEFAULT_EVIDENCE/operator-manifest.txt"
grep -Fxq \
  "reviewed_psql_path=$FAKE_BIN/psql" \
  "$DEFAULT_EVIDENCE/operator-manifest.txt"
grep -Fxq \
  "reviewed_psql_sha256=$(digest_file "$FAKE_BIN/psql")" \
  "$DEFAULT_EVIDENCE/operator-manifest.txt"
grep -Fxq 'reviewed_psql_version=18.3' \
  "$DEFAULT_EVIDENCE/operator-manifest.txt"
grep -Fxq 'reviewed_psql_minimum_major=16' \
  "$DEFAULT_EVIDENCE/operator-manifest.txt"
! grep -Fq 'governance_mode=' "$DEFAULT_EVIDENCE/operator-manifest.txt"
! grep -Fq 'first_confirmation_sha256=' "$DEFAULT_EVIDENCE/operator-manifest.txt"

# Repository-local core.worktree must not redirect validation to a different,
# clean tree while the invoked preflight and later runners remain elsewhere.
ALTERNATE_WORKTREE="$TEST_ROOT/core-worktree-alternate"
"$REAL_GIT" clone -q "$REPO_ROOT" "$ALTERNATE_WORKTREE"
"$REAL_GIT" --git-dir="$REPO_ROOT/.git" config core.worktree "$ALTERNATE_WORKTREE"
assert_failed_with \
  'Git repository root does not match the checked-in preflight location' \
  run_preflight \
  "$TEST_ROOT/core-worktree-evidence" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT"
"$REAL_GIT" --git-dir="$REPO_ROOT/.git" config --unset core.worktree

# --no-replace-objects does not disable the deprecated info/grafts mechanism.
# Reject it before any ancestry-derived evidence such as ahead/behind counts.
GRAFTS_PATH="$REPO_ROOT/.git/info/grafts"
printf '%s\n' "$HEAD_COMMIT" > "$GRAFTS_PATH"
assert_failed_with \
  'repository-local Git graft metadata is forbidden' \
  run_preflight \
  "$TEST_ROOT/grafts-evidence" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT"
rm -f -- "$GRAFTS_PATH"

# Reject Git index flags that can hide working-tree changes from porcelain
# status before preflight creates any evidence.
"$REAL_GIT" -C "$REPO_ROOT" update-index --assume-unchanged -- \
  "$ASSUME_UNCHANGED_PATH"
ASSUME_UNCHANGED_ACTIVE=true
assert_failed_with \
  'tracked files must not use assume-unchanged' \
  run_preflight \
  "$TEST_ROOT/assume-unchanged-evidence" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT"
"$REAL_GIT" -C "$REPO_ROOT" update-index --no-assume-unchanged -- \
  "$ASSUME_UNCHANGED_PATH"
ASSUME_UNCHANGED_ACTIVE=false

# The caller cannot relabel an arbitrary project as production: the exact
# production ref must match the reviewed, checked-in digest.
REVIEWED_PRODUCTION_REF=$PRODUCTION_REF
PRODUCTION_REF=bbbbbbbbbbbbbbbbbbbb
assert_failed_with \
  'does not match the reviewed production trust anchor' \
  run_preflight \
  "$TEST_ROOT/wrong-production-anchor-evidence" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT"
PRODUCTION_REF=$REVIEWED_PRODUCTION_REF

# A local Git replacement ref can make ordinary `git show HEAD:path` return a
# different blob without changing HEAD, the index, or porcelain status. The
# preflight's reviewed-object reads must ignore that replacement and reject a
# production label that matches only the spoofed blob.
ANCHOR_RELATIVE_PATH=scripts/staging-rehearsal/production-project-ref.sha256
ANCHOR_BLOB=$(
  "$REAL_GIT" -C "$REPO_ROOT" rev-parse "$HEAD_COMMIT:$ANCHOR_RELATIVE_PATH"
)
SPOOFED_PRODUCTION_REF=cccccccccccccccccccc
SPOOFED_PRODUCTION_SHA256=$(digest_text "$SPOOFED_PRODUCTION_REF")
SPOOFED_ANCHOR_BLOB=$(
  printf '%s\n' "$SPOOFED_PRODUCTION_SHA256" |
    "$REAL_GIT" -C "$REPO_ROOT" hash-object -w --stdin
)
"$REAL_GIT" -C "$REPO_ROOT" replace "$ANCHOR_BLOB" "$SPOOFED_ANCHOR_BLOB"
[[ "$("$REAL_GIT" -C "$REPO_ROOT" show "$HEAD_COMMIT:$ANCHOR_RELATIVE_PATH")" == \
  "$SPOOFED_PRODUCTION_SHA256" ]]
PRODUCTION_REF=$SPOOFED_PRODUCTION_REF
assert_failed_with \
  'does not match the reviewed production trust anchor' \
  run_preflight \
  "$TEST_ROOT/replaced-production-anchor-evidence" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT"
PRODUCTION_REF=$REVIEWED_PRODUCTION_REF
"$REAL_GIT" -C "$REPO_ROOT" replace -d "$ANCHOR_BLOB" >/dev/null

# The protected scope is broader than the historical REQUIRED_FILES allowlist.
# Verify a real admin mutation path is compared byte-for-byte even when the
# instrumented fake makes every old porcelain-status check report clean.
FILTERED_PROTECTED_PATH=admin/src/repositories/SupabaseAdminExhibitionRepository.ts
FILTER_ROOT="$TEST_ROOT/local-clean-filter"
FILTER_ORIGINAL="$FILTER_ROOT/original"
FILTER_PROGRAM="$FILTER_ROOT/clean"
FILTER_SIDE_EFFECT="$FILTER_ROOT/filter-invoked"
mkdir -m 700 "$FILTER_ROOT"
cp -p "$REPO_ROOT/$FILTERED_PROTECTED_PATH" "$FILTER_ORIGINAL"
printf '%s\n' 'unreviewed admin bytes outside the required-file allowlist' \
  > "$REPO_ROOT/$FILTERED_PROTECTED_PATH"
assert_failed_with \
  "protected artifact bytes differ from the reviewed commit: $FILTERED_PROTECTED_PATH" \
  run_preflight \
  "$TEST_ROOT/non-required-admin-evidence" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT"
cp -p "$FILTER_ORIGINAL" "$REPO_ROOT/$FILTERED_PROTECTED_PATH"

# Repository-local info attributes and a clean filter can make ordinary
# hash/status checks report reviewed bytes for an altered file. Refresh the
# index stat cache so porcelain is demonstrably clean, then prove preflight
# hashes raw bytes and never invokes the filter itself.
cat > "$FILTER_PROGRAM" <<'EOF'
#!/bin/sh
filter_directory=${0%/*}
/bin/cat >/dev/null
: > "$filter_directory/filter-invoked"
exec /bin/cat "$filter_directory/original"
EOF
chmod 700 "$FILTER_PROGRAM"
[[ ! -e "$REPO_ROOT/.git/info/attributes" ]]
printf '%s filter=gallr-hide\n' "$FILTERED_PROTECTED_PATH" \
  > "$REPO_ROOT/.git/info/attributes"
"$REAL_GIT" -C "$REPO_ROOT" config \
  filter.gallr-hide.clean "\"$FILTER_PROGRAM\""
"$REAL_GIT" -C "$REPO_ROOT" config filter.gallr-hide.required true
printf '%s\n' 'unreviewed bytes hidden by a local clean filter' \
  > "$REPO_ROOT/$FILTERED_PROTECTED_PATH"
FILTERED_HEAD_BLOB=$(
  "$REAL_GIT" -C "$REPO_ROOT" rev-parse "$HEAD_COMMIT:$FILTERED_PROTECTED_PATH"
)
[[ "$("$REAL_GIT" -C "$REPO_ROOT" hash-object -- \
  "$FILTERED_PROTECTED_PATH")" == "$FILTERED_HEAD_BLOB" ]]
"$REAL_GIT" -C "$REPO_ROOT" add --renormalize -- \
  "$FILTERED_PROTECTED_PATH" >/dev/null
FILTERED_STATUS=$("$REAL_GIT" -C "$REPO_ROOT" status --porcelain=v1 -- \
  "$FILTERED_PROTECTED_PATH")
[[ -z "$FILTERED_STATUS" ]]
rm -f -- "$FILTER_SIDE_EFFECT"
assert_failed_with \
  "protected artifact bytes differ from the reviewed commit: $FILTERED_PROTECTED_PATH" \
  run_preflight \
  "$TEST_ROOT/clean-filter-bypass-evidence" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT"
[[ ! -e "$FILTER_SIDE_EFFECT" ]]
cp -p "$FILTER_ORIGINAL" "$REPO_ROOT/$FILTERED_PROTECTED_PATH"
"$REAL_GIT" -C "$REPO_ROOT" config --unset-all filter.gallr-hide.clean
"$REAL_GIT" -C "$REPO_ROOT" config --unset-all filter.gallr-hide.required
rm -f -- "$REPO_ROOT/.git/info/attributes"
# The filter-active status check can leave Linux Git's stat cache describing
# the short altered file. Re-add after restoring the reviewed bytes and
# removing the filter; identical bytes only refresh the cache, while any
# restoration error remains a staged difference that the assertion rejects.
"$REAL_GIT" -C "$REPO_ROOT" add -- "$FILTERED_PROTECTED_PATH"
[[ -z "$("$REAL_GIT" -C "$REPO_ROOT" status --porcelain=v1 -- \
  "$FILTERED_PROTECTED_PATH")" ]]

# A failure after publishing the first final name must remove only this
# invocation's files and leave the evidence directory immediately retryable.
PUBLICATION_FAILURE_EVIDENCE="$TEST_ROOT/publication-failure-evidence"
set +e
run_preflight \
  "$PUBLICATION_FAILURE_EVIDENCE" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT" >/dev/null 2>&1
PUBLICATION_FAILURE_STATUS=$?
set -e
[[ "$PUBLICATION_FAILURE_STATUS" -ne 0 ]]
[[ -e "$TEST_ROOT/publication-failure-injected" ]]
[[ -d "$PUBLICATION_FAILURE_EVIDENCE" ]]
[[ -z "$(find "$PUBLICATION_FAILURE_EVIDENCE" -mindepth 1 -maxdepth 1 -print -quit)" ]]
run_preflight \
  "$PUBLICATION_FAILURE_EVIDENCE" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT" >/dev/null
[[ "$(file_mode "$PUBLICATION_FAILURE_EVIDENCE/operator-manifest.txt")" == 444 ]]
[[ "$(file_mode "$PUBLICATION_FAILURE_EVIDENCE/rehearsal-plan.txt")" == 444 ]]

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

grep -Fq \
  'ls-files -v -- docs/adr/0004-solo-operator-cutover-governance.md' \
  "$GIT_LOG" || {
  printf 'Preflight did not require tracked ADR-0004 governance evidence.\n' >&2
  exit 1
}
grep -Fq \
  'ls-files -v -- scripts/staging-rehearsal/tests/postgrest-evidence-toolchain.test.sh' \
  "$GIT_LOG" || {
  printf 'Preflight did not require the PostgREST toolchain boundary test.\n' >&2
  exit 1
}
awk '
  index($0, "ls-files --stage --") &&
  index($0, "docs/adr/0004-solo-operator-cutover-governance.md") { found = 1 }
  END { exit(found ? 0 : 1) }
' "$GIT_LOG" || {
  printf 'Preflight did not compare the protected index including ADR-0004.\n' >&2
  exit 1
}
grep -Fq 'hash-object --no-filters --stdin-paths' "$GIT_LOG" || {
  printf 'Preflight did not hash protected raw worktree bytes.\n' >&2
  exit 1
}
! grep -Fq 'status --porcelain' "$GIT_LOG" || {
  printf 'Preflight invoked filter-aware Git porcelain status.\n' >&2
  exit 1
}

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

OLD_PSQL="$FAKE_BIN/psql-15"
cat > "$OLD_PSQL" <<'EOF'
#!/bin/sh
case "${1-}" in
  --version) printf '%s\n' 'psql (PostgreSQL) 15.14' ;;
  --help)
    printf '%s\n' \
      '  -w, --no-password' \
      '  -1, --single-transaction'
    ;;
  *) exit 99 ;;
esac
EOF
chmod 700 "$OLD_PSQL"
assert_failed_with \
  'reviewed psql client must be PostgreSQL 16 or newer' \
  run_preflight \
  "$TEST_ROOT/old-psql-evidence" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT" \
  '' \
  '' \
  "$OLD_PSQL"

NOOP_NODE="$FAKE_BIN/noop-node"
cat > "$NOOP_NODE" <<'EOF'
#!/bin/sh
case "${1-}" in
  --version) printf '%s\n' 'v18.0.0' ;;
  *) exit 0 ;;
esac
EOF
chmod 700 "$NOOP_NODE"
assert_failed_with \
  'reviewed Node.js runtime returned an invalid capability result' \
  run_preflight \
  "$TEST_ROOT/noop-node-evidence" \
  preflight-executor \
  preflight-reviewer \
  "$HEAD_COMMIT" \
  '' \
  '' \
  "$FAKE_BIN/psql" \
  "$NOOP_NODE"

[[ ! -e "$UNSAFE_MARKER" ]] || {
  printf 'A child inherited unsafe target, credential, or governance state.\n' >&2
  exit 1
}
[[ ! -e "$REMOTE_MARKER" ]] || {
  printf 'Preflight invoked a remote-capable command.\n' >&2
  exit 1
}

printf 'PASS: preflight preserves schema 1 and safely supports explicit solo-operator governance.\n'
