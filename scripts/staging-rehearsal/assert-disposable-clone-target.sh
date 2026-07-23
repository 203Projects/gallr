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
unset governance_mode operator_identity first_confirmation_sha256
unset expected_execution_confirmation_sha256 effective_first_attestation_utc
unset minimum_cooldown_seconds destructive_actions
unset policy_sha256 extra_field marker_evidence field_count record_remainder
staging_ref="$GALLR_EXPECTED_STAGING_PROJECT_REF"
production_ref="$GALLR_PRODUCTION_PROJECT_REF"
database_url="$GALLR_STAGING_DATABASE_URL"
staging_confirmation="$GALLR_STAGING_REHEARSAL_CONFIRM"
evidence_dir_input="$GALLR_STAGING_EVIDENCE_DIR"
policy_path="$GALLR_STAGING_IDENTITY_POLICY_PATH"
unset GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF
unset GALLR_STAGING_DATABASE_URL GALLR_STAGING_REHEARSAL_CONFIRM
unset GALLR_STAGING_EVIDENCE_DIR GALLR_STAGING_IDENTITY_POLICY_PATH
unset GALLR_GOVERNANCE_MODE GALLR_SOLO_OPERATOR_FIRST_CONFIRMATION
unset GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_CONFIRMATION
unset GALLR_REVIEWED_COMMIT GALLR_CHANGE_RECORD
unset GALLR_EXECUTOR GALLR_REVIEWER GALLR_REHEARSAL_RUN_ID
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
unset governance_mode operator_identity first_confirmation_sha256
unset expected_execution_confirmation_sha256 effective_first_attestation_utc
unset minimum_cooldown_seconds destructive_actions
unset policy_sha256 extra_field field_count record_remainder
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
  fail 'staging identity policy validation failed'
fi

[[ "$(printf '%s\n' "$policy_record" | awk 'END { print NR }')" == '1' ]] ||
  fail 'identity policy validator returned an invalid record'
field_count=1
record_remainder="${policy_record}"
while [[ "${record_remainder}" == *$'\t'* ]]; do
  record_remainder="${record_remainder#*$'\t'}"
  field_count=$((field_count + 1))
done
case "${field_count}" in
  11)
    governance_mode='separated_humans'
    IFS=$'\t' read -r \
      marker_id policy_issued_at_utc valid_until_utc \
      staging_ref_sha256 production_ref_sha256 repository_commit \
      operator_manifest_sha256 change_record approver_one approver_two \
      policy_sha256 <<< "${policy_record}"
    operator_identity=''
    first_confirmation_sha256=''
    expected_execution_confirmation_sha256=''
    effective_first_attestation_utc=''
    minimum_cooldown_seconds=''
    destructive_actions=''
    ;;
  15)
    IFS=$'\t' read -r \
      marker_id policy_issued_at_utc valid_until_utc \
      staging_ref_sha256 production_ref_sha256 repository_commit \
      operator_manifest_sha256 change_record governance_mode operator_identity \
      first_confirmation_sha256 expected_execution_confirmation_sha256 \
      effective_first_attestation_utc minimum_cooldown_seconds policy_sha256 \
      <<< "${policy_record}"
    approver_one=''
    approver_two=''
    destructive_actions='forbidden'
    [[ "${governance_mode}" == 'solo_operator' \
       && "${minimum_cooldown_seconds}" == '900' ]] ||
      fail 'identity policy validator returned an invalid solo-operator record'
    ;;
  *) fail 'identity policy validator returned unexpected fields' ;;
esac
for parsed_value in \
  "$marker_id" "$policy_issued_at_utc" "$valid_until_utc" \
  "$staging_ref_sha256" "$production_ref_sha256" "$repository_commit" \
  "$operator_manifest_sha256" "$change_record" "$governance_mode" \
  "$policy_sha256"; do
  [[ -n "$parsed_value" ]] || fail 'identity policy validator returned an empty field'
done
if [[ "${governance_mode}" == 'separated_humans' ]]; then
  [[ -n "${approver_one}" && -n "${approver_two}" ]] ||
    fail 'identity policy validator returned an empty approver field'
else
  for parsed_value in \
    "${operator_identity}" "${first_confirmation_sha256}" \
    "${expected_execution_confirmation_sha256}" \
    "${effective_first_attestation_utc}" "${minimum_cooldown_seconds}"; do
    [[ -n "${parsed_value}" ]] ||
      fail 'identity policy validator returned an empty solo-operator field'
  done
fi

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
      -v "expected_governance_mode=$governance_mode" \
      -v "expected_approver_one=$approver_one" \
      -v "expected_approver_two=$approver_two" \
      -v "expected_operator_identity=$operator_identity" \
      -v "expected_first_confirmation_sha256=$first_confirmation_sha256" \
      -v "expected_second_confirmation_sha256=$expected_execution_confirmation_sha256" \
      -v "expected_effective_first_attestation_utc=$effective_first_attestation_utc" \
      -v "expected_minimum_cooldown_seconds=$minimum_cooldown_seconds" \
      -v "expected_destructive_actions=$destructive_actions" \
      -f "$marker_sql" 2>/dev/null
); then
  fail 'read-only disposable-clone marker query failed'
fi
clear_libpq_environment

expected_evidence=$'relation\t1\ttrue\nmarker\t1\t1'
[[ "$marker_evidence" == "$expected_evidence" ]] ||
  fail 'database is missing the exact approved disposable-clone marker'

printf 'PASS: independent policy and disposable-clone marker identify staging\n'
