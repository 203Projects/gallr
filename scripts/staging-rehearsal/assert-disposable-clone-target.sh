#!/usr/bin/env bash

# Fail-closed target identity check for any staging rehearsal that will mutate
# data. This script makes exactly one read-only database connection after all
# local identity sources agree. It never creates or updates a marker.

set -euo pipefail
umask 077

if [[ $- == *x* ]]; then
  set +x
fi
if [[ $- == *a* ]]; then
  set +a
fi

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_env() {
  [[ -n "${!1:-}" ]] || fail "$1 is required"
}

clear_libpq_environment() {
  unset PGAPPNAME PGCHANNELBINDING PGCLIENTENCODING PGCONNECT_TIMEOUT
  unset PGDATABASE PGDATESTYLE PGGSSENCMODE PGGSSLIB PGHOST PGHOSTADDR
  unset PGKRBSRVNAME PGLOADBALANCEHOSTS PGOPTIONS PGPASSFILE PGPASSWORD PGPORT
  unset PGREQUIREAUTH PGREQUIREPEER PGSERVICE PGSERVICEFILE PGSSLCERT PGSSLCRL
  unset PGREQUIRESSL PGSSLCERTMODE PGSSLCRLDIR PGSSLKEY
  unset PGSSLMAXPROTOCOLVERSION PGSSLMINPROTOCOLVERSION
  unset PGSSLMODE PGSSLNEGOTIATION PGSSLROOTCERT PGTARGETSESSIONATTRS
  unset PGTCP_USER_TIMEOUT PGTZ PGUSER
}

for command_name in awk git node psql; do
  command -v "$command_name" >/dev/null 2>&1 ||
    fail "required command is unavailable: $command_name"
done

require_env GALLR_EXPECTED_STAGING_PROJECT_REF
require_env GALLR_PRODUCTION_PROJECT_REF
require_env GALLR_STAGING_DATABASE_URL
require_env GALLR_STAGING_REHEARSAL_CONFIRM
require_env GALLR_STAGING_EVIDENCE_DIR
require_env GALLR_STAGING_IDENTITY_POLICY_PATH

# Snapshot target inputs before the first child process, then remove them from
# the inherited environment. Trusted children receive only the exact values
# they need through explicit per-command assignments.
unset staging_ref production_ref database_url staging_confirmation
unset evidence_dir_input policy_path
unset policy_record marker_id policy_issued_at_utc valid_until_utc
unset staging_ref_sha256 production_ref_sha256 repository_commit
unset operator_manifest_sha256 change_record approver_one approver_two
unset policy_sha256 extra_field marker_evidence
staging_ref="$GALLR_EXPECTED_STAGING_PROJECT_REF"
production_ref="$GALLR_PRODUCTION_PROJECT_REF"
database_url="$GALLR_STAGING_DATABASE_URL"
staging_confirmation="$GALLR_STAGING_REHEARSAL_CONFIRM"
evidence_dir_input="$GALLR_STAGING_EVIDENCE_DIR"
policy_path="$GALLR_STAGING_IDENTITY_POLICY_PATH"
unset GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF
unset GALLR_STAGING_DATABASE_URL GALLR_STAGING_REHEARSAL_CONFIRM
unset GALLR_STAGING_EVIDENCE_DIR GALLR_STAGING_IDENTITY_POLICY_PATH
unset DATABASE_URL GALLR_SERVICE_ROLE_KEY
unset SUPABASE_ACCESS_TOKEN SUPABASE_URL SUPABASE_ANON_KEY
unset SUPABASE_SERVICE_ROLE_KEY SUPABASE_SECRET_KEY

# Prevent inherited Git routing/configuration from selecting a different
# repository/index/object store or launching a configured fsmonitor helper.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY
unset GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_COMMON_DIR GIT_CEILING_DIRECTORIES
unset GIT_DISCOVERY_ACROSS_FILESYSTEM GIT_CONFIG GIT_CONFIG_PARAMETERS
unset GIT_CONFIG_SYSTEM
export GIT_CONFIG_COUNT=0 GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_OPTIONAL_LOCKS=0
safe_git() {
  command git \
    -c core.fsmonitor=false \
    -c core.hooksPath=/dev/null \
    -c core.excludesFile=/dev/null \
    "$@"
}

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
expected_repo_root="$(cd -- "$script_dir/../.." && pwd -P)"
repo_root="$(safe_git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null)" ||
  fail 'could not resolve the repository root'
repo_root="$(cd -- "$repo_root" && pwd -P)"
[[ "$repo_root" == "$expected_repo_root" ]] ||
  fail 'Git repository root does not match the checked-in target guard location'
current_commit="$(safe_git -C "$repo_root" rev-parse HEAD 2>/dev/null)" ||
  fail 'could not resolve the repository commit'

linked_guard="$script_dir/assert-linked-staging.sh"
database_validator="$script_dir/lib/validate-database-target.mjs"
policy_validator="$script_dir/lib/validate-target-identity-policy.mjs"
marker_sql="$script_dir/sql/assert-disposable-clone-marker.sql"
for required_file in \
  "$linked_guard" "$database_validator" "$policy_validator" "$marker_sql"; do
  [[ -f "$required_file" && ! -L "$required_file" ]] ||
    fail 'a required target-identity guard file is missing or is a symbolic link'
done

operator_manifest_path="$evidence_dir_input/operator-manifest.txt"

# First bind the labels to the clean reviewed checkout, linked project, and
# preflight manifest. The credential-bearing URI is not inherited by this
# non-connecting guard.
if ! (
  clear_libpq_environment
  BASH_ENV=/dev/null ENV=/dev/null \
  GALLR_EXPECTED_STAGING_PROJECT_REF="$staging_ref" \
  GALLR_PRODUCTION_PROJECT_REF="$production_ref" \
  GALLR_STAGING_REHEARSAL_CONFIRM="$staging_confirmation" \
  GALLR_STAGING_EVIDENCE_DIR="$evidence_dir_input" \
    bash --noprofile --norc "$linked_guard" >/dev/null
); then
  fail 'linked project did not match the reviewed staging manifest'
fi

GALLR_VALIDATION_PROJECT_REF="$staging_ref" \
GALLR_VALIDATION_DATABASE_URL="$database_url" \
GALLR_VALIDATION_REQUIRE_DIRECT=true \
NODE_OPTIONS='' NODE_PATH='' \
  node "$database_validator" ||
  fail 'database URL did not resolve to the reviewed direct staging target'

unset policy_record marker_id policy_issued_at_utc valid_until_utc
unset staging_ref_sha256 production_ref_sha256 repository_commit
unset operator_manifest_sha256 change_record approver_one approver_two
unset policy_sha256 extra_field
if ! policy_record=$(
  GALLR_IDENTITY_POLICY_PATH="$policy_path" \
  GALLR_IDENTITY_REPO_ROOT="$repo_root" \
  GALLR_IDENTITY_OPERATOR_MANIFEST_PATH="$operator_manifest_path" \
  GALLR_IDENTITY_EXPECTED_STAGING_REF="$staging_ref" \
  GALLR_IDENTITY_PRODUCTION_REF="$production_ref" \
  GALLR_IDENTITY_CURRENT_COMMIT="$current_commit" \
  NODE_OPTIONS='' NODE_PATH='' \
    node "$policy_validator"
); then
  fail 'independent staging identity policy validation failed'
fi

[[ "$(printf '%s\n' "$policy_record" | awk 'END { print NR }')" == '1' ]] ||
  fail 'identity policy validator returned an invalid record'
IFS=$'\t' read -r \
  marker_id \
  policy_issued_at_utc \
  valid_until_utc \
  staging_ref_sha256 \
  production_ref_sha256 \
  repository_commit \
  operator_manifest_sha256 \
  change_record \
  approver_one \
  approver_two \
  policy_sha256 \
  extra_field <<< "$policy_record"
[[ -z "${extra_field:-}" ]] ||
  fail 'identity policy validator returned unexpected fields'
for parsed_value in \
  "$marker_id" "$policy_issued_at_utc" "$valid_until_utc" \
  "$staging_ref_sha256" "$production_ref_sha256" "$repository_commit" \
  "$operator_manifest_sha256" "$change_record" "$approver_one" \
  "$approver_two" "$policy_sha256"; do
  [[ -n "$parsed_value" ]] || fail 'identity policy validator returned an empty field'
done

# Narrow the local-artifact race between the first linked check and the marker
# query. This second pass must still see the exact clean checkout, manifest,
# migrations, and linked project immediately before the database session.
if ! (
  clear_libpq_environment
  BASH_ENV=/dev/null ENV=/dev/null \
  GALLR_EXPECTED_STAGING_PROJECT_REF="$staging_ref" \
  GALLR_PRODUCTION_PROJECT_REF="$production_ref" \
  GALLR_STAGING_REHEARSAL_CONFIRM="$staging_confirmation" \
  GALLR_STAGING_EVIDENCE_DIR="$evidence_dir_input" \
    bash --noprofile --norc "$linked_guard" >/dev/null
); then
  fail 'linked project or reviewed staging artifacts changed during identity validation'
fi

# Do not pass raw refs or GALLR credential variables to psql. The validated URL
# is supplied only as PGDATABASE, never as a command-line argument.
clear_libpq_environment

if ! marker_evidence=$(
  PGDATABASE="$database_url" \
  PGCONNECT_TIMEOUT=15 \
  PGSSLMODE=verify-full \
  PGPASSFILE=/dev/null \
  PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000' \
  PGAPPNAME='gallr-disposable-clone-identity-check' \
    psql -X --no-password --single-transaction -Atq -F $'\t' \
      --set=ON_ERROR_STOP=1 \
      -v "expected_marker_id=$marker_id" \
      -v "expected_policy_issued_at_utc=$policy_issued_at_utc" \
      -v "expected_valid_until_utc=$valid_until_utc" \
      -v "expected_staging_ref_sha256=$staging_ref_sha256" \
      -v "expected_production_ref_sha256=$production_ref_sha256" \
      -v "expected_repository_commit=$repository_commit" \
      -v "expected_operator_manifest_sha256=$operator_manifest_sha256" \
      -v "expected_policy_sha256=$policy_sha256" \
      -v "expected_change_record=$change_record" \
      -v "expected_approver_one=$approver_one" \
      -v "expected_approver_two=$approver_two" \
      -f "$marker_sql" 2>/dev/null
); then
  fail 'read-only disposable-clone marker query failed'
fi
clear_libpq_environment

expected_evidence=$'relation\t1\ttrue\nmarker\t1\t1'
[[ "$marker_evidence" == "$expected_evidence" ]] ||
  fail 'database is missing the exact approved disposable-clone marker'

printf 'PASS: independent policy and disposable-clone marker identify staging\n'
