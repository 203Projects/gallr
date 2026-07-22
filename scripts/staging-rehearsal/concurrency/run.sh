#!/usr/bin/env bash

# Staging-only proof that a legacy service-role writer already queued behind
# canonical bridge activation cannot commit after ownership changes.

set -euo pipefail
umask 077

# Never let an inherited `bash -x` setting print the credential-bearing child
# environment assignments below.
if [[ $- == *x* ]]; then
  set +x
fi
if [[ $- == *a* ]]; then
  set +a
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

usage() {
  cat <<'EOF'
Usage: bash scripts/staging-rehearsal/concurrency/run.sh

Required environment:
  GALLR_EXPECTED_STAGING_PROJECT_REF      Exact 20-character staging ref
  GALLR_PRODUCTION_PROJECT_REF            Exact 20-character production ref
  GALLR_STAGING_DATABASE_URL              Direct staging PostgreSQL URI
  GALLR_STAGING_REHEARSAL_CONFIRM         Must equal the staging ref
  GALLR_STAGING_IDENTITY_POLICY_PATH      External sealed two-approver policy
  GALLR_CONCURRENCY_EVIDENCE_DIR          Preflight's external 0700 directory
  GALLR_CONCURRENCY_RUN_ID                Unique 8-32 character run ID
  GALLR_CONCURRENCY_APPROVAL_REASON       Strict reason containing the run ID
  GALLR_CONCURRENCY_TARGET_EXHIBITION_ID  Existing published parity target

Optional environment:
  GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS     5-120 (default: 30)
  GALLR_CONCURRENCY_CONNECT_TIMEOUT_SECONDS  5-60 (default: 15)

This operation intentionally changes the staging clone to canonical-owned
mode. It never restores runtime flags, grants, target rows, or audit history.
Continue only with approved canonical-owned gates, then restore the disposable
clone when the rehearsal is complete.
EOF
}

if [[ $# -gt 0 ]]; then
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
fi

concurrency_validate_environment

unset DATABASE_URL
DATABASE_URL=$GALLR_STAGING_DATABASE_URL
export -n DATABASE_URL
SSL_MODE=verify-full
# Remove inherited routing/session overrides. Every child receives the reviewed
# URI plus explicit safe connection settings below.
unset GALLR_STAGING_DATABASE_URL
unset PGAPPNAME PGCHANNELBINDING PGCLIENTENCODING PGCONNECT_TIMEOUT
unset PGDATABASE PGDATESTYLE PGGSSENCMODE PGGSSLIB PGHOST PGHOSTADDR
unset PGKRBSRVNAME PGLOADBALANCEHOSTS PGOPTIONS PGPASSFILE PGPASSWORD PGPORT
unset PGREQUIREAUTH PGREQUIREPEER PGSERVICE PGSERVICEFILE PGSSLCERT PGSSLCRL
unset PGREQUIRESSL PGSSLCERTMODE PGSSLCRLDIR PGSSLKEY
unset PGSSLMAXPROTOCOLVERSION PGSSLMINPROTOCOLVERSION
unset PGSSLMODE PGSSLNEGOTIATION PGSSLROOTCERT PGTARGETSESSIONATTRS
unset PGTCP_USER_TIMEOUT PGTZ PGUSER
STATEMENT_TIMEOUT="$((GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS + 20))s"
LOCK_TIMEOUT="$((GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS + 10))s"

MANIFEST_PATH="$CONCURRENCY_RUN_DIR/manifest.tsv"
IDENTITY_PATH="$CONCURRENCY_RUN_DIR/identity.tsv"
PREFLIGHT_PATH="$CONCURRENCY_RUN_DIR/preflight.tsv"
PREFLIGHT_LOG="$CONCURRENCY_RUN_DIR/preflight.log"
LINKED_GUARD_LOG="$CONCURRENCY_RUN_DIR/linked-target-guard.log"
TARGET_IDENTITY_GUARD_LOG="$CONCURRENCY_RUN_DIR/target-identity-guard.log"
CONTROL_LOG="$CONCURRENCY_RUN_DIR/control.log"
ACTIVATION_LOG="$CONCURRENCY_RUN_DIR/activation.log"
WRITER_LOG="$CONCURRENCY_RUN_DIR/writer.log"
POSTFLIGHT_PATH="$CONCURRENCY_RUN_DIR/postflight.tsv"
POSTFLIGHT_LOG="$CONCURRENCY_RUN_DIR/postflight.log"

CONCURRENCY_EVIDENCE_PATHS=(
  "$IDENTITY_PATH"
  "$PREFLIGHT_PATH"
  "$PREFLIGHT_LOG"
  "$LINKED_GUARD_LOG"
  "$TARGET_IDENTITY_GUARD_LOG"
  "$CONTROL_LOG"
  "$ACTIVATION_LOG"
  "$WRITER_LOG"
  "$POSTFLIGHT_PATH"
  "$POSTFLIGHT_LOG"
  "$MANIFEST_PATH"
)

ACTIVATION_PID=""
WRITER_PID=""
MUTATION_PHASE=0
RUN_COMPLETED=0
MANIFEST_INITIALIZED=0

evidence_path_is_allowed() {
  local candidate="$1"
  local allowed_path

  for allowed_path in "${CONCURRENCY_EVIDENCE_PATHS[@]}"; do
    [[ "$candidate" == "$allowed_path" ]] && return 0
  done
  return 1
}

create_evidence_file() {
  local evidence_path="$1"

  evidence_path_is_allowed "$evidence_path" ||
    concurrency_die "refusing to create an unrecognized evidence path"
  [[ ! -e "$evidence_path" && ! -L "$evidence_path" ]] ||
    concurrency_die "refusing to overwrite evidence: $evidence_path"
  (set -o noclobber; : > "$evidence_path") ||
    concurrency_die "could not exclusively create evidence: $evidence_path"
  [[ -f "$evidence_path" && ! -L "$evidence_path" && -O "$evidence_path" ]] ||
    concurrency_die "evidence path is not an owned regular file: $evidence_path"
  chmod 600 "$evidence_path" ||
    concurrency_die "could not protect evidence: $evidence_path"
}

manifest_append() {
  local event="$1"
  local detail="$2"

  [[ "$MANIFEST_INITIALIZED" -eq 1 ]] || return 1
  [[ -f "$MANIFEST_PATH" && ! -L "$MANIFEST_PATH" && -O "$MANIFEST_PATH" ]] ||
    return 1
  printf '%s\t%s\t%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$event" "$detail" >> "$MANIFEST_PATH"
}

stop_own_client() {
  local pid="$1"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
}

validate_run_evidence_inventory() {
  local evidence_files=()
  local evidence_path
  local expected_path
  local invalid=0
  local restore_dotglob=0
  local restore_nullglob=0

  shopt -q dotglob || restore_dotglob=1
  shopt -q nullglob || restore_nullglob=1
  shopt -s dotglob nullglob
  evidence_files=("$CONCURRENCY_RUN_DIR"/*)
  (( restore_dotglob == 0 )) || shopt -u dotglob
  (( restore_nullglob == 0 )) || shopt -u nullglob

  if (( ${#evidence_files[@]} > 0 )); then
    for evidence_path in "${evidence_files[@]}"; do
      if ! evidence_path_is_allowed "$evidence_path"; then
        printf 'ERROR: unexpected evidence entry: %s\n' "$evidence_path" >&2
        invalid=1
        continue
      fi
      if [[ -L "$evidence_path" || ! -f "$evidence_path" || ! -O "$evidence_path" ]]; then
        printf 'ERROR: evidence entry is not an owned regular file: %s\n' "$evidence_path" >&2
        invalid=1
      fi
    done
  fi

  if [[ "$RUN_COMPLETED" -eq 1 ]]; then
    for expected_path in "${CONCURRENCY_EVIDENCE_PATHS[@]}"; do
      if [[ ! -f "$expected_path" || -L "$expected_path" || ! -O "$expected_path" ]]; then
        printf 'ERROR: completed run is missing regular evidence: %s\n' "$expected_path" >&2
        invalid=1
      fi
    done
  fi

  [[ "$invalid" -eq 0 ]]
}

seal_evidence_payloads() {
  local evidence_path
  local invalid=0

  for evidence_path in "${CONCURRENCY_EVIDENCE_PATHS[@]}"; do
    [[ "$evidence_path" != "$MANIFEST_PATH" ]] || continue
    if [[ ! -e "$evidence_path" && ! -L "$evidence_path" ]]; then
      continue
    fi
    if [[ -f "$evidence_path" && ! -L "$evidence_path" && -O "$evidence_path" ]]; then
      chmod 400 "$evidence_path" || invalid=1
    else
      invalid=1
    fi
  done

  [[ "$invalid" -eq 0 ]]
}

seal_evidence_manifest() {
  if [[ ! -e "$MANIFEST_PATH" && ! -L "$MANIFEST_PATH" ]]; then
    return 0
  fi
  [[ -f "$MANIFEST_PATH" && ! -L "$MANIFEST_PATH" && -O "$MANIFEST_PATH" ]] ||
    return 1
  chmod 400 "$MANIFEST_PATH"
}

on_exit() {
  local status=$?
  local failure_detail
  trap - EXIT HUP INT TERM

  # Only stop clients launched by this process. No database session is
  # terminated by PID and no SQL cleanup, grant restoration, or audit mutation
  # is attempted.
  stop_own_client "$ACTIVATION_PID"
  stop_own_client "$WRITER_PID"

  if [[ "$RUN_COMPLETED" -eq 0 || "$status" -ne 0 ]]; then
    [[ "$status" -ne 0 ]] || status=1
    manifest_append failed "exit_status=$status;mutation_phase=$MUTATION_PHASE" 2>/dev/null || true
  fi

  if ! validate_run_evidence_inventory; then
    status=1
    failure_detail="exit_status=1;mutation_phase=$MUTATION_PHASE;evidence_inventory_invalid=true"
    manifest_append failed "$failure_detail" 2>/dev/null || true
  fi

  if ! seal_evidence_payloads; then
    status=1
    failure_detail="exit_status=1;mutation_phase=$MUTATION_PHASE;evidence_payload_sealing_failed=true"
    manifest_append failed "$failure_detail" 2>/dev/null || true
    printf 'ERROR: could not seal all regular evidence payloads as mode 0400.\n' >&2
  fi

  if ! seal_evidence_manifest; then
    status=1
    printf 'ERROR: could not seal the run manifest as mode 0400.\n' >&2
  fi

  if [[ "$status" -ne 0 && "$MUTATION_PHASE" -eq 1 ]]; then
    printf '%s\n' \
      "CRITICAL: activation was started. Leave the clone and evidence intact." \
      "Restore the disposable staging clone before any retry; this script never cleans up database state." \
      "Evidence: $CONCURRENCY_RUN_DIR" >&2
  fi
  exit "$status"
}

# Resolve the exact evidence contract before creating its private directory so
# the EXIT trap can be installed immediately after mkdir and before any write.
[[ ! -e "$CONCURRENCY_RUN_DIR" && ! -L "$CONCURRENCY_RUN_DIR" ]] ||
  concurrency_die "evidence run already exists; choose a unique run ID"
mkdir -m 700 "$CONCURRENCY_RUN_DIR"
trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

[[ -O "$CONCURRENCY_RUN_DIR" ]] ||
  concurrency_die "run evidence directory must be owned by the current user"
[[ "$(concurrency_mode "$CONCURRENCY_RUN_DIR")" == "700" ]] ||
  concurrency_die "run evidence directory must have mode 0700"

for evidence_path in "${CONCURRENCY_EVIDENCE_PATHS[@]}"; do
  [[ ! -e "$evidence_path" && ! -L "$evidence_path" ]] ||
    concurrency_die "refusing to overwrite evidence: $evidence_path"
done
validate_run_evidence_inventory ||
  concurrency_die "new evidence run directory contains an unexpected entry"

create_evidence_file "$MANIFEST_PATH"
printf 'timestamp_utc\tevent\tdetail\n' >> "$MANIFEST_PATH"
MANIFEST_INITIALIZED=1
create_evidence_file "$IDENTITY_PATH"
printf 'schema_version\trun_id\ttarget_exhibition_id\tapproval_reason\tstaging_ref_sha256\tproduction_ref_sha256\toperator_manifest_sha256\trepository_commit\tbridge_migration_sha256\n' >> "$IDENTITY_PATH"
printf '1\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$GALLR_CONCURRENCY_RUN_ID" \
  "$GALLR_CONCURRENCY_TARGET_EXHIBITION_ID" \
  "$GALLR_CONCURRENCY_APPROVAL_REASON" \
  "$CONCURRENCY_STAGING_REF_SHA256" \
  "$CONCURRENCY_PRODUCTION_REF_SHA256" \
  "$CONCURRENCY_OPERATOR_MANIFEST_SHA256" \
  "$CONCURRENCY_REPOSITORY_COMMIT" \
  "$CONCURRENCY_BRIDGE_MIGRATION_SHA256" >> "$IDENTITY_PATH"

create_evidence_file "$LINKED_GUARD_LOG"
if ! BASH_ENV=/dev/null ENV=/dev/null \
  GALLR_EXPECTED_STAGING_PROJECT_REF="$GALLR_EXPECTED_STAGING_PROJECT_REF" \
  GALLR_PRODUCTION_PROJECT_REF="$GALLR_PRODUCTION_PROJECT_REF" \
  GALLR_STAGING_REHEARSAL_CONFIRM="$GALLR_STAGING_REHEARSAL_CONFIRM" \
  GALLR_STAGING_EVIDENCE_DIR="$CONCURRENCY_EVIDENCE_ROOT" \
  bash "$CONCURRENCY_LINKED_STAGING_GUARD" \
  > "$LINKED_GUARD_LOG" 2>&1; then
  concurrency_die "linked staging target did not match the reviewed manifest; inspect $LINKED_GUARD_LOG"
fi
if [[ "$(grep -Fxc "$CONCURRENCY_BRIDGE_MIGRATION_SHA256  $CONCURRENCY_BRIDGE_MIGRATION_RELATIVE" "$CONCURRENCY_OPERATOR_MANIFEST_PATH")" != "1" ]]; then
  concurrency_die "bridge migration does not match its operator-manifest SHA-256"
fi
manifest_append linked_target_verified \
  "operator_manifest_sha256=$CONCURRENCY_OPERATOR_MANIFEST_SHA256;repository_commit=$CONCURRENCY_REPOSITORY_COMMIT"

create_evidence_file "$TARGET_IDENTITY_GUARD_LOG"
if ! BASH_ENV=/dev/null ENV=/dev/null \
  GALLR_EXPECTED_STAGING_PROJECT_REF="$GALLR_EXPECTED_STAGING_PROJECT_REF" \
  GALLR_PRODUCTION_PROJECT_REF="$GALLR_PRODUCTION_PROJECT_REF" \
  GALLR_STAGING_DATABASE_URL="$DATABASE_URL" \
  GALLR_STAGING_REHEARSAL_CONFIRM="$GALLR_STAGING_REHEARSAL_CONFIRM" \
  GALLR_STAGING_EVIDENCE_DIR="$CONCURRENCY_EVIDENCE_ROOT" \
  GALLR_STAGING_IDENTITY_POLICY_PATH="$GALLR_STAGING_IDENTITY_POLICY_PATH" \
    bash "$CONCURRENCY_TARGET_IDENTITY_GUARD" \
      > "$TARGET_IDENTITY_GUARD_LOG" 2>&1; then
  concurrency_die "disposable-clone target identity failed; inspect $TARGET_IDENTITY_GUARD_LOG"
fi
[[ "$(grep -Fxc \
  'PASS: independent policy and disposable-clone marker identify staging' \
  "$TARGET_IDENTITY_GUARD_LOG")" == "1" ]] ||
  concurrency_die "target-identity evidence is missing its exact pass record"
chmod 400 "$TARGET_IDENTITY_GUARD_LOG"
unset GALLR_STAGING_IDENTITY_POLICY_PATH
manifest_append target_identity_verified \
  "independent_policy_and_database_marker=true"
manifest_append prepared "fail_closed_guards_passed"

psql_control() {
  PGDATABASE="$DATABASE_URL" \
  PGCONNECT_TIMEOUT="$GALLR_CONCURRENCY_CONNECT_TIMEOUT_SECONDS" \
  PGSSLMODE="$SSL_MODE" \
  PGPASSFILE=/dev/null \
  PGOPTIONS="-c statement_timeout=$STATEMENT_TIMEOUT -c lock_timeout=$LOCK_TIMEOUT" \
  PGAPPNAME="$CONCURRENCY_APP_CONTROL" \
    psql -X --no-password --set=ON_ERROR_STOP=1 "$@"
}

validate_two_line_tsv() {
  local path="$1"
  local line_count
  line_count=$(wc -l < "$path" | tr -d ' ')
  [[ "$line_count" == "2" ]] ||
    concurrency_die "expected one database evidence row in $path"
}

create_evidence_file "$PREFLIGHT_PATH"
printf 'row_count\tid_checksum_sha256\tcatalog_checksum_sha256\ttarget_checksum_sha256\tcaptured_at\n' \
  >> "$PREFLIGHT_PATH"
create_evidence_file "$PREFLIGHT_LOG"

printf 'Validating Sheet-owned staging parity and the representative target...\n'
if ! psql_control -Atq -F $'\t' \
  -v "target_id=$GALLR_CONCURRENCY_TARGET_EXHIBITION_ID" \
  -v "approval_reason=$GALLR_CONCURRENCY_APPROVAL_REASON" \
  -f "$SCRIPT_DIR/preflight.sql" \
  >> "$PREFLIGHT_PATH" 2> "$PREFLIGHT_LOG"; then
  concurrency_die "staging preflight failed; inspect $PREFLIGHT_LOG"
fi
validate_two_line_tsv "$PREFLIGHT_PATH"

IFS=$'\t' read -r \
  EXPECTED_ROW_COUNT \
  EXPECTED_ID_CHECKSUM_SHA256 \
  EXPECTED_CATALOG_CHECKSUM_SHA256 \
  EXPECTED_TARGET_CHECKSUM_SHA256 \
  PREFLIGHT_CAPTURED_AT \
  PREFLIGHT_EXTRA < <(sed -n '2p' "$PREFLIGHT_PATH")

[[ -z "${PREFLIGHT_EXTRA:-}" ]] ||
  concurrency_die "preflight evidence has unexpected fields"
[[ "$EXPECTED_ROW_COUNT" =~ ^[0-9]+$ ]] ||
  concurrency_die "preflight row count is invalid"
for digest in \
  "$EXPECTED_ID_CHECKSUM_SHA256" \
  "$EXPECTED_CATALOG_CHECKSUM_SHA256" \
  "$EXPECTED_TARGET_CHECKSUM_SHA256"; do
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] ||
    concurrency_die "preflight returned an invalid SHA-256 digest"
done
[[ -n "$PREFLIGHT_CAPTURED_AT" ]] ||
  concurrency_die "preflight timestamp is missing"

create_evidence_file "$CONTROL_LOG"
create_evidence_file "$ACTIVATION_LOG"
create_evidence_file "$WRITER_LOG"

# Refuse a run ID that is already active from another operator even if this
# machine's evidence directory is new.
ACTIVE_APP_COUNT=$(psql_control -Atq \
  -v "activation_app=$CONCURRENCY_APP_ACTIVATION" \
  -v "writer_app=$CONCURRENCY_APP_WRITER" \
  -c "select count(*) from pg_catalog.pg_stat_activity where application_name in (:'activation_app', :'writer_app');" \
  2>> "$CONTROL_LOG")
[[ "$ACTIVE_APP_COUNT" == "0" ]] ||
  concurrency_die "the run ID already has active database sessions"

MUTATION_PHASE=1
manifest_append activation_started \
  "row_count=$EXPECTED_ROW_COUNT;preflight_at=$PREFLIGHT_CAPTURED_AT"

printf 'Starting canonical bridge activation and holding deployed locks...\n'
PGDATABASE="$DATABASE_URL" \
PGCONNECT_TIMEOUT="$GALLR_CONCURRENCY_CONNECT_TIMEOUT_SECONDS" \
PGSSLMODE="$SSL_MODE" \
PGPASSFILE=/dev/null \
PGOPTIONS="-c statement_timeout=$STATEMENT_TIMEOUT -c lock_timeout=$LOCK_TIMEOUT" \
PGAPPNAME="$CONCURRENCY_APP_ACTIVATION" \
  psql -X --no-password -Atq \
    -v "statement_timeout=$STATEMENT_TIMEOUT" \
    -v "lock_timeout=$LOCK_TIMEOUT" \
    -v "wait_timeout_seconds=$GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS" \
    -v "writer_app=$CONCURRENCY_APP_WRITER" \
    -v "expected_row_count=$EXPECTED_ROW_COUNT" \
    -v "expected_id_checksum_sha256=$EXPECTED_ID_CHECKSUM_SHA256" \
    -v "expected_catalog_checksum_sha256=$EXPECTED_CATALOG_CHECKSUM_SHA256" \
    -v "approval_reason=$GALLR_CONCURRENCY_APPROVAL_REASON" \
    -f "$SCRIPT_DIR/activation.sql" \
    >> "$ACTIVATION_LOG" 2>&1 &
ACTIVATION_PID=$!

wait_for_activation_gate() {
  local started_at
  local now
  local ready
  started_at=$(date '+%s')

  while :; do
    if ! ready=$(psql_control -Atq \
      -v "activation_app=$CONCURRENCY_APP_ACTIVATION" \
      -c "select count(*) from pg_catalog.pg_stat_activity where application_name = :'activation_app' and state = 'active' and backend_xid is not null and wait_event_type = 'Timeout' and wait_event = 'PgSleep';" \
      2>> "$CONTROL_LOG"); then
      return 1
    fi
    if [[ "$ready" == "1" ]]; then
      return 0
    fi

    if ! kill -0 "$ACTIVATION_PID" 2>/dev/null; then
      return 1
    fi
    now=$(date '+%s')
    if (( now - started_at >= GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS )); then
      return 1
    fi
    sleep 0.05
  done
}

if ! wait_for_activation_gate; then
  concurrency_die "activation did not reach its lock-holding gate; inspect $ACTIVATION_LOG"
fi
manifest_append activation_lock_gate "deployed_locks_held=true"

printf 'Starting the already-authorized service_role writer...\n'
PGDATABASE="$DATABASE_URL" \
PGCONNECT_TIMEOUT="$GALLR_CONCURRENCY_CONNECT_TIMEOUT_SECONDS" \
PGSSLMODE="$SSL_MODE" \
PGPASSFILE=/dev/null \
PGOPTIONS="-c statement_timeout=$STATEMENT_TIMEOUT -c lock_timeout=$LOCK_TIMEOUT" \
PGAPPNAME="$CONCURRENCY_APP_WRITER" \
  psql -X --no-password -Atq \
    -v "statement_timeout=$STATEMENT_TIMEOUT" \
    -v "lock_timeout=$LOCK_TIMEOUT" \
    -v "run_id=$GALLR_CONCURRENCY_RUN_ID" \
    -v "target_id=$GALLR_CONCURRENCY_TARGET_EXHIBITION_ID" \
    -f "$SCRIPT_DIR/writer.sql" \
    >> "$WRITER_LOG" 2>&1 &
WRITER_PID=$!

if wait "$ACTIVATION_PID"; then
  ACTIVATION_STATUS=0
else
  ACTIVATION_STATUS=$?
fi
ACTIVATION_PID=""

if wait "$WRITER_PID"; then
  WRITER_STATUS=0
else
  WRITER_STATUS=$?
fi
WRITER_PID=""

manifest_append client_status \
  "activation=$ACTIVATION_STATUS;writer=$WRITER_STATUS"

[[ "$ACTIVATION_STATUS" -eq 0 ]] ||
  concurrency_die "activation failed; inspect $ACTIVATION_LOG"
[[ "$WRITER_STATUS" -ne 0 ]] ||
  concurrency_die "queued writer unexpectedly committed"

grep -Fq \
  'queued writer observed service_role UPDATE authorization' \
  "$WRITER_LOG" ||
  concurrency_die "writer did not prove pre-lock UPDATE authorization"
grep -Eq \
  '42501:|55000: legacy_exhibitions_managed_by_canonical' \
  "$WRITER_LOG" ||
  concurrency_die "writer failed without the expected 42501 or ownership-guard 55000"
grep -Fq \
  'queued legacy writer observed behind activation locks' \
  "$ACTIVATION_LOG" ||
  concurrency_die "activation did not record the queued writer"
grep -Fq \
  'activation holds deployed advisory and relation locks' \
  "$ACTIVATION_LOG" ||
  concurrency_die "activation did not record exact deployed locks"

create_evidence_file "$POSTFLIGHT_PATH"
printf 'legacy_mirror_enabled\tlegacy_writes_blocked\ttarget_checksum_sha256\taudit_id\taudit_occurred_at\treconciliation_in_sync\tcaptured_at\n' \
  >> "$POSTFLIGHT_PATH"
create_evidence_file "$POSTFLIGHT_LOG"

printf 'Verifying canonical ownership, unchanged payload, and append-only audit evidence...\n'
if ! psql_control -Atq -F $'\t' \
  -v "target_id=$GALLR_CONCURRENCY_TARGET_EXHIBITION_ID" \
  -v "approval_reason=$GALLR_CONCURRENCY_APPROVAL_REASON" \
  -v "expected_row_count=$EXPECTED_ROW_COUNT" \
  -v "expected_id_checksum_sha256=$EXPECTED_ID_CHECKSUM_SHA256" \
  -v "expected_catalog_checksum_sha256=$EXPECTED_CATALOG_CHECKSUM_SHA256" \
  -v "expected_target_checksum_sha256=$EXPECTED_TARGET_CHECKSUM_SHA256" \
  -f "$SCRIPT_DIR/postflight.sql" \
  >> "$POSTFLIGHT_PATH" 2> "$POSTFLIGHT_LOG"; then
  concurrency_die "post-activation verification failed; inspect $POSTFLIGHT_LOG"
fi
validate_two_line_tsv "$POSTFLIGHT_PATH"

POSTFLIGHT_ROW=$(sed -n '2p' "$POSTFLIGHT_PATH")
case "$POSTFLIGHT_ROW" in
  $'t\tt\t'*) ;;
  *) concurrency_die "postflight evidence does not show canonical-owned runtime" ;;
esac

manifest_append passed \
  "writer_rejected=true;runtime=canonical_owned;final_clone_restore_required=true"
RUN_COMPLETED=1

printf '%s\n' \
  'PASS: the queued service_role writer was authorized before waiting and rejected after activation.' \
  "Evidence: $CONCURRENCY_RUN_DIR" \
  'The clone is canonical-owned. Run only the approved next gates (the 1,205 fixture gate is next), then restore it.'
