#!/bin/sh

# Local-only staging rehearsal preflight.
#
# This program intentionally has no remote mode. It does not link a Supabase
# project, query a database, push migrations, or invoke curl. Supabase CLI calls
# are limited to local version/help inspection.

set -eu
case $- in
  *a*) set +a ;;
esac
umask 077

PROGRAM_NAME="staging-rehearsal-preflight"
MIN_SUPABASE_VERSION="2.81.3"

fail() {
  printf '%s: ERROR: %s\n' "$PROGRAM_NAME" "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

validate_project_ref() {
  ref_name=$1
  ref_value=$2
  ref_length=$(printf '%s' "$ref_value" | wc -c | tr -d '[:space:]')

  [ "$ref_length" -eq 20 ] ||
    fail "$ref_name must be a 20-character Supabase project reference"

  case "$ref_value" in
    *[!a-z0-9]*)
      fail "$ref_name may contain only lowercase ASCII letters and digits"
      ;;
  esac
}

validate_single_line() {
  value_name=$1
  value=$2

  [ -n "$value" ] || fail "$value_name is required"
  case "$value" in
    *'
'*)
      fail "$value_name must be a single-line value"
      ;;
  esac
  case "$value" in
    *"$(printf '\r')"*)
      fail "$value_name must not contain carriage returns"
      ;;
  esac
}

version_at_least() {
  awk -v actual="$1" -v minimum="$2" '
    BEGIN {
      split(actual, a, ".")
      split(minimum, m, ".")
      for (i = 1; i <= 3; i++) {
        av = a[i] + 0
        mv = m[i] + 0
        if (av > mv) exit 0
        if (av < mv) exit 1
      }
      exit 0
    }
  '
}

file_sha256() {
  if [ "$HASH_COMMAND" = "shasum" ]; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

text_sha256() {
  if [ "$HASH_COMMAND" = "shasum" ]; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  else
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  fi
}

template_declares_name() {
  awk -F= -v expected="$2" '
    $1 == expected { found = 1 }
    END { exit(found ? 0 : 1) }
  ' "$1"
}

environment_presence() {
  case "$1" in
    SUPABASE_ACCESS_TOKEN) printf '%s' "$PRESENCE_SUPABASE_ACCESS_TOKEN" ;;
    SUPABASE_DB_PASSWORD) printf '%s' "$PRESENCE_SUPABASE_DB_PASSWORD" ;;
    GALLR_STAGING_DATABASE_URL) printf '%s' "$PRESENCE_GALLR_STAGING_DATABASE_URL" ;;
    GALLR_STAGING_IDENTITY_POLICY_PATH) printf '%s' "$PRESENCE_GALLR_STAGING_IDENTITY_POLICY_PATH" ;;
    GALLR_SUPABASE_URL) printf '%s' "$PRESENCE_GALLR_SUPABASE_URL" ;;
    GALLR_SERVICE_ROLE_KEY) printf '%s' "$PRESENCE_GALLR_SERVICE_ROLE_KEY" ;;
    SUPABASE_URL) printf '%s' "$PRESENCE_SUPABASE_URL" ;;
    SUPABASE_ANON_KEY) printf '%s' "$PRESENCE_SUPABASE_ANON_KEY" ;;
    *) fail "unsupported environment-presence field: $1" ;;
  esac
}

directory_mode() {
  if stat -f '%Lp' "$1" >/dev/null 2>&1; then
    stat -f '%Lp' "$1"
  else
    stat -c '%a' "$1"
  fi
}

# Snapshot required inputs and the future-credential presence record before the
# first external child process. Remove export attributes by unsetting internal
# aliases before assignment, then clear every credential and Git routing input.
unset STAGING_REF PRODUCTION_REF EVIDENCE_INPUT REVIEWED_COMMIT
unset CHANGE_RECORD EXECUTOR REVIEWER RUN_ID
unset GOVERNANCE_MODE SOLO_FIRST_CONFIRMATION EXPECTED_SOLO_CONFIRMATION
unset FIRST_CONFIRMATION_SHA256 CANONICAL_EXECUTOR CANONICAL_REVIEWER
unset PRESENCE_SUPABASE_ACCESS_TOKEN PRESENCE_SUPABASE_DB_PASSWORD
unset PRESENCE_GALLR_STAGING_DATABASE_URL
unset PRESENCE_GALLR_STAGING_IDENTITY_POLICY_PATH
unset PRESENCE_GALLR_SUPABASE_URL PRESENCE_GALLR_SERVICE_ROLE_KEY
unset PRESENCE_SUPABASE_URL PRESENCE_SUPABASE_ANON_KEY

STAGING_REF=${GALLR_EXPECTED_STAGING_PROJECT_REF-}
PRODUCTION_REF=${GALLR_PRODUCTION_PROJECT_REF-}
EVIDENCE_INPUT=${GALLR_STAGING_EVIDENCE_DIR-}
REVIEWED_COMMIT=${GALLR_REVIEWED_COMMIT-}
CHANGE_RECORD=${GALLR_CHANGE_RECORD-}
EXECUTOR=${GALLR_EXECUTOR-}
REVIEWER=${GALLR_REVIEWER-}
RUN_ID=${GALLR_REHEARSAL_RUN_ID-}
GOVERNANCE_MODE=${GALLR_GOVERNANCE_MODE-separated_humans}
SOLO_FIRST_CONFIRMATION=${GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION-}

PRESENCE_SUPABASE_ACCESS_TOKEN=not_loaded
PRESENCE_SUPABASE_DB_PASSWORD=not_loaded
PRESENCE_GALLR_STAGING_DATABASE_URL=not_loaded
PRESENCE_GALLR_STAGING_IDENTITY_POLICY_PATH=not_loaded
PRESENCE_GALLR_SUPABASE_URL=not_loaded
PRESENCE_GALLR_SERVICE_ROLE_KEY=not_loaded
PRESENCE_SUPABASE_URL=not_loaded
PRESENCE_SUPABASE_ANON_KEY=not_loaded
[ "${SUPABASE_ACCESS_TOKEN+x}" = x ] && PRESENCE_SUPABASE_ACCESS_TOKEN=present
[ "${SUPABASE_DB_PASSWORD+x}" = x ] && PRESENCE_SUPABASE_DB_PASSWORD=present
[ "${GALLR_STAGING_DATABASE_URL+x}" = x ] && PRESENCE_GALLR_STAGING_DATABASE_URL=present
[ "${GALLR_STAGING_IDENTITY_POLICY_PATH+x}" = x ] && PRESENCE_GALLR_STAGING_IDENTITY_POLICY_PATH=present
[ "${GALLR_SUPABASE_URL+x}" = x ] && PRESENCE_GALLR_SUPABASE_URL=present
[ "${GALLR_SERVICE_ROLE_KEY+x}" = x ] && PRESENCE_GALLR_SERVICE_ROLE_KEY=present
[ "${SUPABASE_URL+x}" = x ] && PRESENCE_SUPABASE_URL=present
[ "${SUPABASE_ANON_KEY+x}" = x ] && PRESENCE_SUPABASE_ANON_KEY=present

unset GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF
unset GALLR_STAGING_EVIDENCE_DIR GALLR_REVIEWED_COMMIT GALLR_CHANGE_RECORD
unset GALLR_EXECUTOR GALLR_REVIEWER GALLR_REHEARSAL_RUN_ID
unset GALLR_GOVERNANCE_MODE GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION
unset SUPABASE_ACCESS_TOKEN SUPABASE_DB_PASSWORD
unset GALLR_STAGING_DATABASE_URL GALLR_STAGING_IDENTITY_POLICY_PATH
unset GALLR_SUPABASE_URL GALLR_SERVICE_ROLE_KEY DATABASE_URL
unset SUPABASE_URL SUPABASE_ANON_KEY SUPABASE_SERVICE_ROLE_KEY
unset SUPABASE_SECRET_KEY
unset PGAPPNAME PGCHANNELBINDING PGCLIENTENCODING PGCONNECT_TIMEOUT
unset PGDATABASE PGDATESTYLE PGGSSENCMODE PGGSSLIB PGHOST PGHOSTADDR
unset PGKRBSRVNAME PGLOADBALANCEHOSTS PGOPTIONS PGPASSFILE PGPASSWORD PGPORT
unset PGREQUIREAUTH PGREQUIREPEER PGSERVICE PGSERVICEFILE PGSSLCERT PGSSLCRL
unset PGREQUIRESSL PGSSLCERTMODE PGSSLCRLDIR PGSSLKEY
unset PGSSLMAXPROTOCOLVERSION PGSSLMINPROTOCOLVERSION
unset PGSSLMODE PGSSLNEGOTIATION PGSSLROOTCERT PGTARGETSESSIONATTRS
unset PGTCP_USER_TIMEOUT PGTZ PGUSER
unset NODE_OPTIONS NODE_PATH NODE_DEBUG NODE_DEBUG_NATIVE
unset NODE_EXTRA_CA_CERTS NODE_TLS_REJECT_UNAUTHORIZED NODE_USE_ENV_PROXY
unset BASH_ENV ENV CDPATH

unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY
unset GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_COMMON_DIR GIT_CEILING_DIRECTORIES
unset GIT_DISCOVERY_ACROSS_FILESYSTEM GIT_CONFIG GIT_CONFIG_PARAMETERS
unset GIT_CONFIG_SYSTEM GIT_EXEC_PATH GIT_EXTERNAL_DIFF GIT_DIFF_OPTS
unset GIT_SSH GIT_SSH_COMMAND GIT_ASKPASS GIT_SEQUENCE_EDITOR GIT_EDITOR
unset SAFE_PATH HASH_COMMAND
SAFE_PATH=$PATH
safe_git() {
  env -i \
    PATH="$SAFE_PATH" \
    HOME=/nonexistent \
    USER=staging-preflight \
    TMPDIR=/tmp \
    LC_ALL=C \
    GIT_CONFIG_COUNT=0 \
    GIT_CONFIG_GLOBAL=/dev/null \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_OPTIONAL_LOCKS=0 \
    GIT_PAGER=cat \
    PAGER=cat \
    git \
      -c core.fsmonitor=false \
      -c core.hooksPath=/dev/null \
      -c core.excludesFile=/dev/null \
      -c core.attributesFile=/dev/null \
      "$@"
}

require_command git
require_command env
require_command supabase
require_command node
require_command jq
require_command curl
require_command psql
require_command awk
require_command find
require_command grep
require_command sort
require_command stat

if command -v shasum >/dev/null 2>&1; then
  HASH_COMMAND="shasum"
elif command -v sha256sum >/dev/null 2>&1; then
  HASH_COMMAND="sha256sum"
else
  fail "required SHA-256 command is unavailable: install shasum or sha256sum"
fi

SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" >/dev/null 2>&1 && pwd)
[ -n "$SCRIPT_DIR" ] || fail "cannot resolve the script directory"

REPO_ROOT=$(safe_git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null) ||
  fail "the helper must run from a Git worktree"
REPO_ROOT=$(CDPATH= cd -P "$REPO_ROOT" >/dev/null 2>&1 && pwd)

[ -n "$RUN_ID" ] || RUN_ID=$(date -u '+%Y%m%dT%H%M%SZ-staging')

validate_single_line GALLR_EXPECTED_STAGING_PROJECT_REF "$STAGING_REF"
validate_single_line GALLR_PRODUCTION_PROJECT_REF "$PRODUCTION_REF"
validate_single_line GALLR_STAGING_EVIDENCE_DIR "$EVIDENCE_INPUT"
validate_single_line GALLR_REVIEWED_COMMIT "$REVIEWED_COMMIT"
validate_single_line GALLR_CHANGE_RECORD "$CHANGE_RECORD"
validate_single_line GALLR_EXECUTOR "$EXECUTOR"
validate_single_line GALLR_REVIEWER "$REVIEWER"
validate_single_line GALLR_REHEARSAL_RUN_ID "$RUN_ID"
validate_single_line GALLR_GOVERNANCE_MODE "$GOVERNANCE_MODE"

validate_project_ref GALLR_EXPECTED_STAGING_PROJECT_REF "$STAGING_REF"
validate_project_ref GALLR_PRODUCTION_PROJECT_REF "$PRODUCTION_REF"

[ "$STAGING_REF" != "$PRODUCTION_REF" ] ||
  fail "staging and production project references must be distinct"

case "$GOVERNANCE_MODE" in
  separated_humans)
    [ -z "$SOLO_FIRST_CONFIRMATION" ] ||
      fail "GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION is only valid in solo_operator mode"
    CANONICAL_EXECUTOR=$(printf '%s' "$EXECUTOR" | LC_ALL=C tr '[:upper:]' '[:lower:]')
    CANONICAL_REVIEWER=$(printf '%s' "$REVIEWER" | LC_ALL=C tr '[:upper:]' '[:lower:]')
    [ "$CANONICAL_EXECUTOR" != "$CANONICAL_REVIEWER" ] ||
      fail "executor and reviewer must be different people"
    FIRST_CONFIRMATION_SHA256=""
    ;;
  solo_operator)
    validate_single_line GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION "$SOLO_FIRST_CONFIRMATION"
    [ "$EXECUTOR" = "$REVIEWER" ] ||
      fail "solo-operator executor and reviewer must be the same exact identity"
    EXPECTED_SOLO_CONFIRMATION="INTENT STAGING $STAGING_REF NOT PRODUCTION $PRODUCTION_REF $REVIEWED_COMMIT ACCEPT_NO_INDEPENDENT_REVIEW"
    [ "$SOLO_FIRST_CONFIRMATION" = "$EXPECTED_SOLO_CONFIRMATION" ] ||
      fail "GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION does not match the exact solo-operator intent"
    FIRST_CONFIRMATION_SHA256=$(text_sha256 "$SOLO_FIRST_CONFIRMATION")
    ;;
  *)
    fail "unsupported GALLR_GOVERNANCE_MODE: expected separated_humans or solo_operator"
    ;;
esac

case "$RUN_ID" in
  *[!A-Za-z0-9._-]*)
    fail "GALLR_REHEARSAL_RUN_ID may contain only letters, digits, dot, underscore, and hyphen"
    ;;
esac

case "$EVIDENCE_INPUT" in
  /*) ;;
  *) fail "GALLR_STAGING_EVIDENCE_DIR must be an absolute path" ;;
esac

EVIDENCE_PARENT_INPUT=$(dirname "$EVIDENCE_INPUT")
EVIDENCE_LEAF=$(basename "$EVIDENCE_INPUT")
case "$EVIDENCE_LEAF" in
  ''|.|..) fail "GALLR_STAGING_EVIDENCE_DIR must name a dedicated run directory" ;;
esac

[ -d "$EVIDENCE_PARENT_INPUT" ] ||
  fail "the evidence directory parent must already exist"
EVIDENCE_PARENT=$(CDPATH= cd -P "$EVIDENCE_PARENT_INPUT" >/dev/null 2>&1 && pwd)
EVIDENCE_DIR="$EVIDENCE_PARENT/$EVIDENCE_LEAF"

case "$EVIDENCE_DIR" in
  "$REPO_ROOT"|"$REPO_ROOT"/*)
    fail "the evidence directory must be outside the repository"
    ;;
esac

[ ! -L "$EVIDENCE_INPUT" ] || fail "the evidence directory must not be a symbolic link"

HEAD_COMMIT=$(safe_git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null) ||
  fail "cannot resolve the repository commit"
[ "$REVIEWED_COMMIT" = "$HEAD_COMMIT" ] ||
  fail "GALLR_REVIEWED_COMMIT must exactly equal the current full commit"

REQUIRED_FILES='supabase/config.toml
supabase/migrations/000_profiles_bookmarks_cli_bridge.sql
supabase/migrations/20260721075225_legacy_import_and_compatibility_preview.sql
supabase/migrations/20260721120000_public_exhibition_catalog_v2.sql
supabase/tests/exhibition_catalog_v2_concurrency.sh
supabase/tests/database/005_legacy_import_compatibility.test.sql
supabase/tests/database/008_public_exhibition_catalog_v2.test.sql
supabase/functions/outbox-worker/.env.example
supabase/functions/outbox-worker/README.md
supabase/functions/outbox-worker/index.ts
scripts/legacy-import/README.md
scripts/legacy-import/legacy-import.mjs
scripts/legacy-import/legacy-import.test.mjs
scripts/staging-rehearsal/preflight.sh
scripts/staging-rehearsal/run-safe-bash.sh
scripts/staging-rehearsal/README.md
scripts/staging-rehearsal/TARGET-IDENTITY.md
scripts/staging-rehearsal/assert-disposable-clone-target.sh
scripts/staging-rehearsal/assert-linked-staging.sh
scripts/staging-rehearsal/install-disposable-clone-marker.sh
scripts/staging-rehearsal/concurrency/README.md
scripts/staging-rehearsal/concurrency/activation.sql
scripts/staging-rehearsal/concurrency/common.sh
scripts/staging-rehearsal/concurrency/postflight.sql
scripts/staging-rehearsal/concurrency/preflight.sql
scripts/staging-rehearsal/concurrency/run.sh
scripts/staging-rehearsal/concurrency/tests/guards.test.sh
scripts/staging-rehearsal/concurrency/writer.sql
scripts/staging-rehearsal/fixtures/README.md
scripts/staging-rehearsal/fixtures/baseline.sql
scripts/staging-rehearsal/fixtures/cleanup.sh
scripts/staging-rehearsal/fixtures/cleanup.sql
scripts/staging-rehearsal/fixtures/common.sh
scripts/staging-rehearsal/fixtures/provision.sh
scripts/staging-rehearsal/fixtures/provision.sql
scripts/staging-rehearsal/fixtures/tests/guards.test.sh
scripts/staging-rehearsal/fixtures/tests/lifecycle.test.sh
scripts/staging-rehearsal/fixtures/tracked-state.sql
scripts/staging-rehearsal/lib/validate-database-target.mjs
scripts/staging-rehearsal/lib/validate-database-target.test.mjs
scripts/staging-rehearsal/lib/validate-target-identity-policy.mjs
scripts/staging-rehearsal/lib/validate-target-identity-policy.test.mjs
scripts/staging-rehearsal/run-anonymous-access-checks.sh
scripts/staging-rehearsal/run-database-evidence.sh
scripts/staging-rehearsal/run-migration-writer-probe.sh
scripts/staging-rehearsal/run-postgrest-evidence.sh
scripts/staging-rehearsal/sql/anonymous-catalog-write-must-fail.sql
scripts/staging-rehearsal/sql/anonymous-positive.sql
scripts/staging-rehearsal/sql/anonymous-private-read-must-fail.sql
scripts/staging-rehearsal/sql/assert-disposable-clone-marker.sql
scripts/staging-rehearsal/sql/install-disposable-clone-marker.sql
scripts/staging-rehearsal/sql/migration-lock-observer.sql
scripts/staging-rehearsal/sql/migration-writer-probe.sql
scripts/staging-rehearsal/sql/post-migration-validation.sql
scripts/staging-rehearsal/sql/pre-migration-inventory.sql
scripts/staging-rehearsal/target-identity-policy.example
scripts/staging-rehearsal/tests/database-evidence-chain.test.sh
scripts/staging-rehearsal/tests/install-disposable-clone-marker.test.sh
scripts/staging-rehearsal/tests/migration-writer-probe.test.sh
scripts/staging-rehearsal/tests/preflight-environment.test.sh
scripts/staging-rehearsal/tests/run-safe-bash.test.sh
scripts/staging-rehearsal/tests/target-identity-guard.test.sh
scripts/production-cutover/README.md
scripts/production-cutover/assert-production-target.sh
scripts/production-cutover/lib/validate-production-database-target.mjs
scripts/production-cutover/lib/validate-production-database-target.test.mjs
scripts/production-cutover/tests/assert-production-target.test.sh
docs/exhibition-content-architecture.md
docs/legacy-exhibition-import-runbook.md
docs/public-exhibition-catalog-cutover-runbook.md
docs/adr/0003-transactional-public-exhibition-catalog.md
admin/.env.example
admin/package.json
admin/package-lock.json
web/.env.local.example
web/package.json
web/package-lock.json
web/scripts/fetch-exhibitions.js
web/scripts/fetch-showcase.js
web/scripts/lib/exhibition-reader-source.js
web/tests/fetch-exhibitions.integration.test.js
web/tests/fetch-exhibitions.integration-harness.test.js
web/tests/rebuild-workflow.test.js
.github/workflows/rebuild-web.yml
gas/FormEndpoint.gs
shared/build.gradle.kts
shared/src/commonMain/kotlin/com/gallr/shared/data/network/ExhibitionApiClient.kt
shared/src/commonMain/kotlin/com/gallr/shared/data/network/ExhibitionCatalogSource.kt
shared/src/commonMain/kotlin/com/gallr/shared/data/network/ExhibitionPagination.kt
shared/src/commonTest/kotlin/com/gallr/shared/data/network/ExhibitionCatalogSourceTest.kt
shared/src/commonTest/kotlin/com/gallr/shared/data/network/ExhibitionPaginationTest.kt
composeApp/build.gradle.kts
composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt
composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt
iosApp/iosApp.xcodeproj/project.pbxproj
iosApp/iosApp/ContentView.swift
iosApp/iosApp/Info.plist'

OLD_IFS=$IFS
IFS='
'
for relative_path in $REQUIRED_FILES; do
  [ -f "$REPO_ROOT/$relative_path" ] || fail "required artifact is missing: $relative_path"
  safe_git -C "$REPO_ROOT" ls-files --error-unmatch -- "$relative_path" >/dev/null 2>&1 ||
    fail "required artifact is not tracked by Git: $relative_path"
done

MIGRATION_FILES=$(find "$REPO_ROOT/supabase/migrations" -maxdepth 1 -type f -name '*.sql' -print | LC_ALL=C sort)
[ -n "$MIGRATION_FILES" ] || fail "no Supabase migrations were found"

MIGRATION_COUNT=0
for migration_path in $MIGRATION_FILES; do
  relative_path=${migration_path#"$REPO_ROOT"/}
  safe_git -C "$REPO_ROOT" ls-files --error-unmatch -- "$relative_path" >/dev/null 2>&1 ||
    fail "migration is not tracked by Git: $relative_path"
  MIGRATION_COUNT=$((MIGRATION_COUNT + 1))
done
IFS=$OLD_IFS

DIRTY_REQUIRED=$(safe_git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all -- \
  supabase/config.toml \
  supabase/migrations \
  supabase/tests \
  supabase/functions/outbox-worker \
  scripts/legacy-import \
  scripts/staging-rehearsal \
  scripts/production-cutover \
  docs/exhibition-content-architecture.md \
  docs/legacy-exhibition-import-runbook.md \
  docs/public-exhibition-catalog-cutover-runbook.md \
  docs/adr/0003-transactional-public-exhibition-catalog.md \
  admin \
  web/.env.local.example \
  web/package.json \
  web/package-lock.json \
  web/scripts/fetch-exhibitions.js \
  web/scripts/fetch-showcase.js \
  web/scripts/lib/exhibition-reader-source.js \
  web/tests/fetch-exhibitions.integration.test.js \
  web/tests/fetch-exhibitions.integration-harness.test.js \
  web/tests/rebuild-workflow.test.js \
  .github/workflows/rebuild-web.yml \
  gas/FormEndpoint.gs \
  shared \
  composeApp/build.gradle.kts \
  composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt \
  composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt \
  iosApp/iosApp.xcodeproj/project.pbxproj \
  iosApp/iosApp/ContentView.swift \
  iosApp/iosApp/Info.plist)

if [ -n "$DIRTY_REQUIRED" ]; then
  printf '%s: required artifacts are dirty or untracked:\n%s\n' \
    "$PROGRAM_NAME" "$DIRTY_REQUIRED" >&2
  fail "commit and review the required artifacts before staging rehearsal"
fi

template_declares_name "$REPO_ROOT/admin/.env.example" VITE_SUPABASE_URL ||
  fail "admin/.env.example does not declare VITE_SUPABASE_URL"
template_declares_name "$REPO_ROOT/admin/.env.example" VITE_SUPABASE_PUBLISHABLE_KEY ||
  fail "admin/.env.example does not declare VITE_SUPABASE_PUBLISHABLE_KEY"
template_declares_name "$REPO_ROOT/web/.env.local.example" SUPABASE_URL ||
  fail "web/.env.local.example does not declare SUPABASE_URL"
template_declares_name "$REPO_ROOT/web/.env.local.example" SUPABASE_ANON_KEY ||
  fail "web/.env.local.example does not declare SUPABASE_ANON_KEY"
template_declares_name "$REPO_ROOT/web/.env.local.example" GALLR_EXHIBITION_SOURCE ||
  fail "web/.env.local.example does not declare GALLR_EXHIBITION_SOURCE"
template_declares_name "$REPO_ROOT/web/.env.local.example" GALLR_REQUIRE_LIVE_DATA ||
  fail "web/.env.local.example does not declare GALLR_REQUIRE_LIVE_DATA"
template_declares_name "$REPO_ROOT/supabase/functions/outbox-worker/.env.example" OUTBOX_WORKER_TOKEN ||
  fail "outbox-worker/.env.example does not declare OUTBOX_WORKER_TOKEN"
template_declares_name "$REPO_ROOT/supabase/functions/outbox-worker/.env.example" SUPABASE_URL ||
  fail "outbox-worker/.env.example does not declare SUPABASE_URL"
template_declares_name "$REPO_ROOT/supabase/functions/outbox-worker/.env.example" SUPABASE_SECRET_KEY ||
  fail "outbox-worker/.env.example does not declare SUPABASE_SECRET_KEY"

SUPABASE_VERSION_RAW=$(CI=1 SUPABASE_DISABLE_UPDATE_CHECK=1 supabase --version 2>/dev/null) ||
  fail "Supabase CLI could not report its local version"
SUPABASE_VERSION=$(printf '%s\n' "$SUPABASE_VERSION_RAW" | awk '
  match($0, /[0-9]+\.[0-9]+\.[0-9]+/) {
    print substr($0, RSTART, RLENGTH)
    exit
  }
')
[ -n "$SUPABASE_VERSION" ] || fail "Supabase CLI returned an unrecognized version"
version_at_least "$SUPABASE_VERSION" "$MIN_SUPABASE_VERSION" ||
  fail "Supabase CLI must be at least version $MIN_SUPABASE_VERSION"

MIGRATION_HELP=$(CI=1 SUPABASE_DISABLE_UPDATE_CHECK=1 supabase migration list --help 2>&1)
TEST_HELP=$(CI=1 SUPABASE_DISABLE_UPDATE_CHECK=1 supabase test db --help 2>&1)
LINT_HELP=$(CI=1 SUPABASE_DISABLE_UPDATE_CHECK=1 supabase db lint --help 2>&1)
ADVISOR_HELP=$(CI=1 SUPABASE_DISABLE_UPDATE_CHECK=1 supabase db advisors --help 2>&1)
PUSH_HELP=$(CI=1 SUPABASE_DISABLE_UPDATE_CHECK=1 supabase db push --help 2>&1)
LINK_HELP=$(CI=1 SUPABASE_DISABLE_UPDATE_CHECK=1 supabase link --help 2>&1)

printf '%s' "$MIGRATION_HELP" | grep -q -- '--linked' ||
  fail "Supabase CLI migration list lacks --linked support"
printf '%s' "$TEST_HELP" | grep -q -- '--linked' ||
  fail "Supabase CLI test db lacks --linked support"
printf '%s' "$LINT_HELP" | grep -q -- '--linked' ||
  fail "Supabase CLI db lint lacks --linked support"
printf '%s' "$ADVISOR_HELP" | grep -q -- '--linked' ||
  fail "Supabase CLI db advisors lacks --linked support"
printf '%s' "$PUSH_HELP" | grep -q -- '--dry-run' ||
  fail "Supabase CLI db push lacks --dry-run support"
printf '%s' "$LINK_HELP" | grep -q -- '--project-ref' ||
  fail "Supabase CLI link lacks --project-ref support"

if [ -e "$EVIDENCE_DIR" ]; then
  [ -d "$EVIDENCE_DIR" ] || fail "the evidence path exists but is not a directory"
  [ ! -L "$EVIDENCE_DIR" ] || fail "the evidence directory must not be a symbolic link"
  FIRST_EVIDENCE_ENTRY=$(find "$EVIDENCE_DIR" -mindepth 1 -maxdepth 1 -print -quit)
  [ -z "$FIRST_EVIDENCE_ENTRY" ] || fail "the evidence directory must be new or empty"
else
  mkdir "$EVIDENCE_DIR" || fail "could not create the evidence directory"
fi

chmod 0700 "$EVIDENCE_DIR" || fail "could not set evidence directory mode to 0700"
EVIDENCE_DIR=$(CDPATH= cd -P "$EVIDENCE_DIR" >/dev/null 2>&1 && pwd)
case "$EVIDENCE_DIR" in
  "$REPO_ROOT"|"$REPO_ROOT"/*)
    fail "resolved evidence directory is inside the repository"
    ;;
esac
[ "$(directory_mode "$EVIDENCE_DIR")" = "700" ] ||
  fail "evidence directory mode is not 0700"

GENERATED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
STAGING_REF_SHA256=$(text_sha256 "$STAGING_REF")
PRODUCTION_REF_SHA256=$(text_sha256 "$PRODUCTION_REF")
BRANCH_NAME=$(safe_git -C "$REPO_ROOT" branch --show-current)
[ -n "$BRANCH_NAME" ] || BRANCH_NAME="detached"

if CACHED_UPSTREAM=$(safe_git -C "$REPO_ROOT" rev-parse --abbrev-ref '@{upstream}' 2>/dev/null); then
  CACHED_DIVERGENCE=$(safe_git -C "$REPO_ROOT" rev-list --left-right --count HEAD..."$CACHED_UPSTREAM")
  CACHED_AHEAD=$(printf '%s' "$CACHED_DIVERGENCE" | awk '{print $1}')
  CACHED_BEHIND=$(printf '%s' "$CACHED_DIVERGENCE" | awk '{print $2}')
else
  CACHED_UPSTREAM="none"
  CACHED_AHEAD="unknown"
  CACHED_BEHIND="unknown"
fi

MANIFEST_PATH="$EVIDENCE_DIR/operator-manifest.txt"
PLAN_PATH="$EVIDENCE_DIR/rehearsal-plan.txt"
MANIFEST_TEMP="$EVIDENCE_DIR/.operator-manifest.tmp.$$"
PLAN_TEMP="$EVIDENCE_DIR/.rehearsal-plan.tmp.$$"

cleanup_temporary_outputs() {
  rm -f "$MANIFEST_TEMP" "$PLAN_TEMP"
}
trap cleanup_temporary_outputs EXIT HUP INT TERM

{
  if [ "$GOVERNANCE_MODE" = "solo_operator" ]; then
    printf 'manifest_schema=2\n'
  else
    printf 'manifest_schema=1\n'
  fi
  printf 'run_id=%s\n' "$RUN_ID"
  printf 'generated_at_utc=%s\n' "$GENERATED_AT"
  printf 'target=staging\n'
  printf 'change_record=%s\n' "$CHANGE_RECORD"
  printf 'executor=%s\n' "$EXECUTOR"
  printf 'reviewer=%s\n' "$REVIEWER"
  if [ "$GOVERNANCE_MODE" = "solo_operator" ]; then
    printf 'governance_mode=solo_operator\n'
    printf 'human_reviewer_count=0\n'
    printf 'automation_is_independent_human_review=false\n'
    printf 'residual_risk_accepted=true\n'
    printf 'minimum_cooldown_seconds=900\n'
    printf 'destructive_actions=forbidden\n'
    printf 'first_confirmation_sha256=%s\n' "$FIRST_CONFIRMATION_SHA256"
  fi
  printf 'repository_commit=%s\n' "$HEAD_COMMIT"
  printf 'repository_branch=%s\n' "$BRANCH_NAME"
  printf 'cached_upstream=%s\n' "$CACHED_UPSTREAM"
  printf 'cached_ahead=%s\n' "$CACHED_AHEAD"
  printf 'cached_behind=%s\n' "$CACHED_BEHIND"
  printf 'required_artifacts_tracked=true\n'
  printf 'required_artifacts_clean=true\n'
  printf 'staging_and_production_refs_distinct=true\n'
  printf 'staging_project_ref_sha256=%s\n' "$STAGING_REF_SHA256"
  printf 'production_project_ref_sha256=%s\n' "$PRODUCTION_REF_SHA256"
  printf 'project_ref_values_recorded=false\n'
  printf 'supabase_cli_version=%s\n' "$SUPABASE_VERSION"
  printf 'supabase_cli_minimum=%s\n' "$MIN_SUPABASE_VERSION"
  printf 'migration_count=%s\n' "$MIGRATION_COUNT"
  printf 'evidence_directory_mode=0700\n'
  printf 'remote_contact_performed=false\n'
  printf '\n[required_input_environment_names]\n'
  printf 'GALLR_EXPECTED_STAGING_PROJECT_REF=provided_value_omitted\n'
  printf 'GALLR_PRODUCTION_PROJECT_REF=provided_value_omitted\n'
  printf 'GALLR_STAGING_EVIDENCE_DIR=provided_value_omitted\n'
  printf 'GALLR_REVIEWED_COMMIT=provided_and_matched\n'
  printf 'GALLR_CHANGE_RECORD=provided\n'
  printf 'GALLR_EXECUTOR=provided\n'
  printf 'GALLR_REVIEWER=provided\n'
  if [ "$GOVERNANCE_MODE" = "solo_operator" ]; then
    printf 'GALLR_GOVERNANCE_MODE=solo_operator\n'
    printf 'GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION=provided_value_omitted\n'
  fi
  printf '\n[future_remote_environment_presence]\n'
  for env_name in \
    SUPABASE_ACCESS_TOKEN \
    SUPABASE_DB_PASSWORD \
    GALLR_STAGING_DATABASE_URL \
    GALLR_STAGING_IDENTITY_POLICY_PATH \
    GALLR_SUPABASE_URL \
    GALLR_SERVICE_ROLE_KEY \
    SUPABASE_URL \
    SUPABASE_ANON_KEY
  do
    printf '%s=%s\n' "$env_name" "$(environment_presence "$env_name")"
  done
  printf '\n[environment_template_contracts]\n'
  printf 'admin=VITE_SUPABASE_URL,VITE_SUPABASE_PUBLISHABLE_KEY\n'
  printf 'web=SUPABASE_URL,SUPABASE_ANON_KEY,GALLR_EXHIBITION_SOURCE,GALLR_REQUIRE_LIVE_DATA\n'
  printf 'outbox_worker=OUTBOX_WORKER_TOKEN,SUPABASE_URL,SUPABASE_SECRET_KEY\n'
  printf '\n[migration_sha256]\n'
  IFS='
'
  for migration_path in $MIGRATION_FILES; do
    relative_path=${migration_path#"$REPO_ROOT"/}
    printf '%s  %s\n' "$(file_sha256 "$migration_path")" "$relative_path"
  done
  IFS=$OLD_IFS
} > "$MANIFEST_TEMP"

{
  printf 'Staging-clone rehearsal plan\n'
  printf '==============================\n\n'
  printf 'This file is a plan, not an execution log. The preflight made no remote contact.\n'
  printf 'Project-reference values are intentionally omitted; compare their SHA-256 fingerprints\n'
  printf 'with the approved change record before any later remote command.\n\n'
  printf '1. Obtain approval for the exact repository commit and migration hashes in operator-manifest.txt.\n'
  printf '2. Provision or refresh an isolated, restorable staging clone; confirm it is not production.\n'
  printf '3. In a separately authorized terminal, link the CLI explicitly to the staging project.\n'
  if [ "$GOVERNANCE_MODE" = "solo_operator" ]; then
    printf '4. Prepare the solo-operator target policy, observe its 900-second cooldown, and install its expiring marker on the disposable clone only.\n'
  else
    printf '4. Prepare the independent two-approver target policy and install its expiring marker on the disposable clone only.\n'
  fi
  printf '5. Confirm the staging PostgreSQL major version, backup identifier, operators, rollback thresholds, and marker identity.\n'
  printf '6. Capture linked migration history before deployment. Inspect every difference around the 005b numeric bridge.\n'
  printf '7. Generate and review a linked database-push dry run. Never use --include-all without a migration-by-migration review.\n'
  printf '8. Re-run the target-identity gate, capture the full legacy payload fingerprint, then apply only reviewed migrations while observer and rollback-only writer sessions record locks and duration.\n'
  printf '9. Validate the migration, run the reviewed legacy import stage/apply/reconcile cycle, and prove exact post-import parity while runtime remains Sheet-owned.\n'
  printf '10. Run linked pgTAP, lint, and security/performance advisors before ownership activation.\n'
  printf '11. Stop the Sheet writer, run marker-bound queued-writer activation, then exercise the admin lifecycle in canonical-owned mode.\n'
  printf '12. Provision the marker-bound sealed 1,205-row fixture, run final database/PostgREST evidence, clean the exact manifest, and prove baseline restoration.\n'
  printf '13. Obtain reviewer acceptance before planning any production action.\n\n'
  printf 'STOP conditions: identical target fingerprints; unreviewed migration history; unexplained data drift;\n'
  printf 'missing lock timing; anonymous draft access; failed checksum/integrity checks; or any evidence of a production target.\n'
} > "$PLAN_TEMP"

mv "$MANIFEST_TEMP" "$MANIFEST_PATH"
mv "$PLAN_TEMP" "$PLAN_PATH"
chmod 0444 "$MANIFEST_PATH" "$PLAN_PATH"
trap - EXIT HUP INT TERM

printf '%s: PASS\n' "$PROGRAM_NAME"
printf 'Evidence directory: %s\n' "$EVIDENCE_DIR"
printf 'Created: operator-manifest.txt, rehearsal-plan.txt\n'
printf 'Remote contact performed: no\n'
