#!/usr/bin/env bash

# Local-only regression test for concurrent exhibition_catalog_v2 refreshes.
#
# This script covers two transaction-boundary regressions for one uniquely
# named exhibition:
#
# 1. concurrent canonical source updates must converge on the exact V2 payload;
# 2. a legacy service-role writer already queued behind bridge activation must
#    be rejected after activation commits and releases its table lock.
#
# It must only be pointed at a disposable local Supabase Docker database.

set -eu
set -o pipefail

# zsh otherwise tries to lower the priority of background jobs. Some locked-
# down local environments deny that harmless nice(2) call, so disable it while
# retaining the same scheduling behavior bash uses here.
if [ -n "${ZSH_VERSION:-}" ]; then
  setopt NO_BG_NICE
fi

DB_CONTAINER="${SUPABASE_DB_CONTAINER:-supabase_db_gallr}"
DB_USER="${SUPABASE_DB_USER:-postgres}"
DB_NAME="${SUPABASE_DB_NAME:-postgres}"
WAIT_TIMEOUT_SECONDS="${GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS:-20}"

usage() {
  echo "Usage: bash supabase/tests/exhibition_catalog_v2_concurrency.sh"
  echo
  echo "Optional environment variables:"
  echo "  SUPABASE_DB_CONTAINER  Local DB container (default: supabase_db_gallr)"
  echo "  SUPABASE_DB_USER       Database role (default: postgres)"
  echo "  SUPABASE_DB_NAME       Database name (default: postgres)"
  echo "  GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS  Coordination timeout (default: 20)"
}

if [ "$#" -gt 0 ]; then
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

case "$WAIT_TIMEOUT_SECONDS" in
  ''|*[!0-9]*)
    echo "GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS must be a positive integer." >&2
    exit 2
    ;;
  0)
    echo "GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS must be greater than zero." >&2
    exit 2
    ;;
esac

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required to run this local concurrency test." >&2
  exit 2
fi

if [ "$(docker inspect --format '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null || true)" != "true" ]; then
  echo "Local Supabase database container '$DB_CONTAINER' is not running." >&2
  echo "Start the local stack first, or set SUPABASE_DB_CONTAINER." >&2
  exit 2
fi

RUN_TOKEN="$(date -u '+%Y%m%d%H%M%S')-$$"
FIXTURE_ID="catalog-v2-concurrency-$RUN_TOKEN"
APP_CONTROL="gallr_v2_control_$RUN_TOKEN"
APP_SESSION_A="gallr_v2_a_$RUN_TOKEN"
APP_SESSION_B="gallr_v2_b_$RUN_TOKEN"
APP_BRIDGE_ACTIVATION="gallr_v2_bridge_$RUN_TOKEN"
APP_LEGACY_WRITER="gallr_v2_legacy_writer_$RUN_TOKEN"
BASELINE_NAME="Concurrency baseline $RUN_TOKEN"
SESSION_A_NAME="Concurrency version A $RUN_TOKEN"
LEGACY_WRITER_NAME="Queued legacy writer must fail $RUN_TOKEN"
BRIDGE_REASON="queued legacy writer regression $RUN_TOKEN"

TMP_ROOT="${TMPDIR:-/tmp}"
TMP_DIR="$(mktemp -d "$TMP_ROOT/gallr-catalog-v2-concurrency.XXXXXX")"
SESSION_A_LOG="$TMP_DIR/session-a.log"
SESSION_B_LOG="$TMP_DIR/session-b.log"
BRIDGE_ACTIVATION_LOG="$TMP_DIR/bridge-activation.log"
LEGACY_WRITER_LOG="$TMP_DIR/legacy-writer.log"

CLEANUP_ENABLED=0
SESSION_A_PID=""
SESSION_B_PID=""
BRIDGE_ACTIVATION_PID=""
LEGACY_WRITER_PID=""

run_psql() {
  local app_name="$1"
  shift
  docker exec -i \
    --env "PGAPPNAME=$app_name" \
    "$DB_CONTAINER" \
    psql -X -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" "$@"
}

wait_for_session_a_gate() {
  local started_at
  local now
  local ready
  started_at="$(date '+%s')"

  while :; do
    ready="$(run_psql "$APP_CONTROL" -Atc "
      select exists (
        select 1
        from pg_catalog.pg_stat_activity as activity
        where activity.application_name = '$APP_SESSION_A'
          and activity.state = 'active'
          and activity.backend_xid is not null
          and activity.wait_event_type = 'Timeout'
          and activity.wait_event = 'PgSleep'
      );
    ")"
    if [ "$ready" = "t" ]; then
      return 0
    fi

    now="$(date '+%s')"
    if [ $((now - started_at)) -ge "$WAIT_TIMEOUT_SECONDS" ]; then
      echo "Timed out waiting for session A to hold its committed-source gate." >&2
      return 1
    fi
    sleep 0.05
  done
}

wait_for_bridge_activation_gate() {
  local started_at
  local now
  local ready
  started_at="$(date '+%s')"

  while :; do
    ready="$(run_psql "$APP_CONTROL" -Atc "
      select exists (
        select 1
        from pg_catalog.pg_stat_activity as activity
        where activity.application_name = '$APP_BRIDGE_ACTIVATION'
          and activity.state = 'active'
          and activity.backend_xid is not null
          and activity.wait_event_type = 'Timeout'
          and activity.wait_event = 'PgSleep'
      );
    ")"
    if [ "$ready" = "t" ]; then
      return 0
    fi

    now="$(date '+%s')"
    if [ $((now - started_at)) -ge "$WAIT_TIMEOUT_SECONDS" ]; then
      echo "Timed out waiting for bridge activation to hold its lock." >&2
      return 1
    fi
    sleep 0.05
  done
}

stop_child() {
  local child_pid="$1"
  if [ -n "$child_pid" ] && kill -0 "$child_pid" 2>/dev/null; then
    kill "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  fi
}

cleanup() {
  local exit_status=$?
  trap - EXIT HUP INT TERM

  stop_child "$SESSION_A_PID"
  stop_child "$SESSION_B_PID"
  stop_child "$BRIDGE_ACTIVATION_PID"
  stop_child "$LEGACY_WRITER_PID"

  if [ "$CLEANUP_ENABLED" -eq 1 ]; then
    if ! run_psql "$APP_CONTROL" >/dev/null <<SQL
set statement_timeout = '15s';
set lock_timeout = '5s';

select pg_terminate_backend(activity.pid)
from pg_catalog.pg_stat_activity as activity
where activity.application_name in (
    '$APP_SESSION_A',
    '$APP_SESSION_B',
    '$APP_BRIDGE_ACTIVATION',
    '$APP_LEGACY_WRITER'
  )
  and activity.pid <> pg_catalog.pg_backend_pid();

-- Restore the exact default Sheet-writer state if this test committed bridge
-- activation before a later assertion failed. The unique reason prevents this
-- cleanup from changing an unrelated local operator state.
do \$restore_runtime\$
declare
  v_reason text;
  v_enabled boolean;
  v_blocked boolean;
begin
  perform pg_catalog.pg_advisory_xact_lock(73241, 1);

  select
    runtime.reason,
    runtime.legacy_mirror_enabled,
    runtime.legacy_writes_blocked
  into strict v_reason, v_enabled, v_blocked
  from content_private.exhibition_catalog_runtime as runtime
  where runtime.singleton;

  if v_reason = '$BRIDGE_REASON' then
    update content_private.exhibition_catalog_runtime
    set
      legacy_mirror_enabled = false,
      legacy_writes_blocked = false,
      legacy_mirror_enabled_at = null,
      baseline_row_count = null,
      baseline_id_checksum_sha256 = null,
      baseline_catalog_checksum_sha256 = null,
      reason = 'installed disabled'
    where singleton;

    execute
      'grant insert, update, delete, truncate on public.exhibitions to service_role';
  elsif v_enabled or v_blocked then
    raise exception using
      message = 'cleanup_refused_unrelated_runtime_state',
      detail = format(
        'reason=%s enabled=%s blocked=%s',
        v_reason,
        v_enabled,
        v_blocked
      );
  end if;
end
\$restore_runtime\$;

begin;

do \$cleanup\$
declare
  v_identity_count integer;
  v_version_count integer;
  v_curation_count integer;
  v_projection_count integer;
  v_legacy_count integer;
  v_bridge_audit_count integer;
begin
  select count(*)::integer into v_identity_count
  from content.exhibitions
  where id = '$FIXTURE_ID';

  select count(*)::integer into v_version_count
  from content.exhibition_versions
  where exhibition_id = '$FIXTURE_ID';

  select count(*)::integer into v_curation_count
  from content.curation_placements
  where exhibition_id = '$FIXTURE_ID';

  select count(*)::integer into v_projection_count
  from public.exhibition_catalog_v2
  where id = '$FIXTURE_ID';

  select count(*)::integer into v_legacy_count
  from public.exhibitions
  where id = '$FIXTURE_ID';

  select count(*)::integer into v_bridge_audit_count
  from content.audit_log
  where action = 'legacy_exhibition_mirror.enabled'
    and entity_type = 'system_setting'
    and entity_id = 'legacy_exhibition_mirror'
    and metadata ->> 'reason' = '$BRIDGE_REASON';

  if v_identity_count > 1
    or v_version_count > 1
    or v_curation_count > 1
    or v_projection_count > 1
    or v_legacy_count > 1
    or v_bridge_audit_count > 1
  then
    raise exception using
      message = format(
        'cleanup_count_guard_failed identity=%s version=%s curation=%s projection=%s legacy=%s bridge_audit=%s',
        v_identity_count,
        v_version_count,
        v_curation_count,
        v_projection_count,
        v_legacy_count,
        v_bridge_audit_count
      );
  end if;

  if v_version_count = 1 and not exists (
    select 1
    from content.exhibition_versions
    where exhibition_id = '$FIXTURE_ID'
      and version_number = 1
      and status = 'published'::content.exhibition_version_status
      and name_ko in ('$BASELINE_NAME', '$SESSION_A_NAME')
  ) then
    raise exception 'cleanup_provenance_guard_failed_for_version';
  end if;

  if v_curation_count = 1 and not exists (
    select 1
    from content.curation_placements
    where exhibition_id = '$FIXTURE_ID'
      and surface = 'app_featured'::content.curation_surface
  ) then
    raise exception 'cleanup_provenance_guard_failed_for_curation';
  end if;

  if v_legacy_count = 1 and not exists (
    select 1
    from public.exhibitions
    where id = '$FIXTURE_ID'
      and name_ko in (
        '$BASELINE_NAME',
        '$SESSION_A_NAME',
        '$LEGACY_WRITER_NAME'
      )
  ) then
    raise exception 'cleanup_provenance_guard_failed_for_legacy_row';
  end if;
end
\$cleanup\$;

delete from public.exhibitions
where id = '$FIXTURE_ID';

delete from content.audit_log
where action = 'legacy_exhibition_mirror.enabled'
  and entity_type = 'system_setting'
  and entity_id = 'legacy_exhibition_mirror'
  and metadata ->> 'reason' = '$BRIDGE_REASON';

delete from content.curation_placements
where exhibition_id = '$FIXTURE_ID';

update content.exhibitions
set published_version_id = null
where id = '$FIXTURE_ID';

delete from content.exhibition_versions
where exhibition_id = '$FIXTURE_ID';

delete from content.exhibitions
where id = '$FIXTURE_ID';

do \$cleanup_verify\$
begin
  if exists (
    select 1 from content.exhibitions where id = '$FIXTURE_ID'
  ) or exists (
    select 1
    from content.exhibition_versions
    where exhibition_id = '$FIXTURE_ID'
  ) or exists (
    select 1
    from content.curation_placements
    where exhibition_id = '$FIXTURE_ID'
  ) or exists (
    select 1 from public.exhibition_catalog_v2 where id = '$FIXTURE_ID'
  ) or exists (
    select 1 from public.exhibitions where id = '$FIXTURE_ID'
  ) or exists (
    select 1
    from content.audit_log
    where action = 'legacy_exhibition_mirror.enabled'
      and entity_type = 'system_setting'
      and entity_id = 'legacy_exhibition_mirror'
      and metadata ->> 'reason' = '$BRIDGE_REASON'
  ) then
    raise exception 'cleanup_verification_failed';
  end if;
end
\$cleanup_verify\$;

commit;
SQL
    then
      echo "WARNING: guarded cleanup failed for fixture '$FIXTURE_ID'." >&2
      echo "Inspect the local database before removing it manually." >&2
      exit_status=1
    fi
  fi

  rm -f \
    "$SESSION_A_LOG" \
    "$SESSION_B_LOG" \
    "$BRIDGE_ACTIVATION_LOG" \
    "$LEGACY_WRITER_LOG"
  rmdir "$TMP_DIR" 2>/dev/null || true
  exit "$exit_status"
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

echo "Checking local exhibition_catalog_v2 prerequisites..."
run_psql "$APP_CONTROL" >/dev/null <<'SQL'
do $prerequisites$
declare
  v_report jsonb;
begin
  if to_regclass('public.exhibition_catalog_v2') is null then
    raise exception 'public.exhibition_catalog_v2 is missing; apply local migrations first';
  end if;

  if to_regprocedure(
    'content_private.exhibition_catalog_v2_source(text)'
  ) is null then
    raise exception 'the exhibition_catalog_v2 source function is missing';
  end if;

  if to_regprocedure(
    'public.admin_reconcile_exhibition_catalog_v2()'
  ) is null then
    raise exception 'the exhibition_catalog_v2 reconciliation function is missing';
  end if;

  if to_regprocedure(
    'public.admin_enable_legacy_exhibition_mirror(bigint,text,text,text)'
  ) is null then
    raise exception 'the legacy exhibition bridge activation function is missing';
  end if;

  if not exists (
    select 1
    from information_schema.columns
    where table_schema = 'content_private'
      and table_name = 'exhibition_catalog_runtime'
      and column_name = 'legacy_writes_blocked'
  ) then
    raise exception
      'legacy_writes_blocked is missing; apply the bridge freeze migration first';
  end if;

  v_report := public.admin_reconcile_exhibition_catalog_v2();
  if not coalesce((v_report ->> 'in_sync')::boolean, false) then
    raise exception using
      message = 'catalog_v2_is_already_out_of_sync',
      detail = v_report::text;
  end if;

  if not exists (
    select 1
    from content_private.exhibition_catalog_runtime as runtime
    where runtime.singleton
      and not runtime.legacy_mirror_enabled
      and not runtime.legacy_writes_blocked
      and runtime.legacy_mirror_enabled_at is null
      and runtime.baseline_row_count is null
      and runtime.baseline_id_checksum_sha256 is null
      and runtime.baseline_catalog_checksum_sha256 is null
      and runtime.reason = 'installed disabled'
  ) then
    raise exception
      'queued-writer regression requires the clean default Sheet-writer state';
  end if;

  if exists (
    select 1
    from content_private.exhibition_catalog_legacy_write_context
  ) then
    raise exception 'legacy write context must be empty before the regression';
  end if;

  if not pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'INSERT'
    )
    or not pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'UPDATE'
    )
    or not pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'DELETE'
    )
    or not pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'TRUNCATE'
    ) then
    raise exception
      'service_role needs legacy DML before testing an already-authorized writer';
  end if;

  if exists (
    select 1
    from public.exhibition_catalog_v2 as catalog
    full join public.exhibitions as legacy using (id)
    where catalog.id is null
      or legacy.id is null
      or content_private.exhibition_catalog_v2_payload(catalog)
        is distinct from
          content_private.legacy_exhibition_catalog_v2_payload(legacy)
  ) then
    raise exception
      'queued-writer regression requires exact legacy-to-V2 parity; reset the local database first';
  end if;
end
$prerequisites$;
SQL

# The fixture ID includes UTC wall-clock time and this shell's PID. Refuse any
# pre-existing collision before enabling the cleanup trap for this identity.
run_psql "$APP_CONTROL" >/dev/null <<SQL
do \$collision_guard\$
begin
  if exists (
    select 1 from content.exhibitions where id = '$FIXTURE_ID'
  ) or exists (
    select 1
    from content.exhibition_versions
    where exhibition_id = '$FIXTURE_ID'
  ) or exists (
    select 1
    from content.curation_placements
    where exhibition_id = '$FIXTURE_ID'
  ) or exists (
    select 1 from public.exhibition_catalog_v2 where id = '$FIXTURE_ID'
  ) or exists (
    select 1 from public.exhibitions where id = '$FIXTURE_ID'
  ) or exists (
    select 1
    from content.audit_log
    where action = 'legacy_exhibition_mirror.enabled'
      and entity_type = 'system_setting'
      and entity_id = 'legacy_exhibition_mirror'
      and metadata ->> 'reason' = '$BRIDGE_REASON'
  ) then
    raise exception 'fixture_id_collision';
  end if;
end
\$collision_guard\$;
SQL
CLEANUP_ENABLED=1

echo "Creating committed canonical fixture '$FIXTURE_ID'..."
run_psql "$APP_CONTROL" >/dev/null <<SQL
begin;

insert into content.exhibitions (id)
values ('$FIXTURE_ID');

insert into content.exhibition_versions (
  exhibition_id,
  version_number,
  revision,
  status,
  name_ko,
  name_en,
  venue_name_ko,
  venue_name_en,
  city_ko,
  city_en,
  region_ko,
  region_en,
  address_ko,
  address_en,
  opening_date,
  closing_date,
  description_ko,
  description_en,
  is_featured,
  is_homepage_featured,
  published_at
) values (
  '$FIXTURE_ID',
  1,
  1,
  'published',
  '$BASELINE_NAME',
  'Concurrency baseline',
  '동시성 테스트 전시장',
  'Concurrency test venue',
  '서울',
  'Seoul',
  '서울',
  'Seoul',
  '테스트 주소',
  'Test address',
  date '2026-01-01',
  date '2026-12-31',
  '동시성 회귀 테스트',
  'Concurrency regression test',
  false,
  false,
  clock_timestamp()
);

update content.exhibitions as exhibition
set published_version_id = version.id
from content.exhibition_versions as version
where exhibition.id = '$FIXTURE_ID'
  and version.exhibition_id = exhibition.id
  and version.version_number = 1;

insert into content.curation_placements (
  surface,
  exhibition_id,
  position,
  enabled
) values (
  'app_featured',
  '$FIXTURE_ID',
  0,
  false
);

-- Seed the installed legacy contract from the exact projected payload. This
-- is only possible in the clean default state checked above; activation will
-- independently validate the full global payload before moving ownership.
insert into public.exhibitions (
  id,
  name_ko,
  venue_name_ko,
  city_ko,
  region_ko,
  opening_date,
  closing_date,
  is_featured,
  latitude,
  longitude,
  description_ko,
  cover_image_url,
  updated_at,
  name_en,
  venue_name_en,
  city_en,
  region_en,
  description_en,
  address_ko,
  address_en,
  hours,
  contact,
  reception_date,
  opening_time,
  event_id,
  is_homepage_featured,
  editor_id,
  ticket_url
)
select
  catalog.id,
  catalog.name_ko,
  catalog.venue_name_ko,
  catalog.city_ko,
  catalog.region_ko,
  catalog.opening_date,
  catalog.closing_date,
  catalog.is_featured,
  catalog.latitude,
  catalog.longitude,
  catalog.description_ko,
  catalog.cover_image_url,
  catalog.updated_at,
  catalog.name_en,
  catalog.venue_name_en,
  catalog.city_en,
  catalog.region_en,
  catalog.description_en,
  catalog.address_ko,
  catalog.address_en,
  catalog.hours,
  catalog.contact,
  catalog.reception_date,
  catalog.opening_time,
  catalog.event_id,
  catalog.is_homepage_featured,
  catalog.editor_id,
  catalog.ticket_url
from public.exhibition_catalog_v2 as catalog
where catalog.id = '$FIXTURE_ID';

do \$fixture_assertion\$
begin
  if not exists (
    select 1
    from public.exhibition_catalog_v2
    where id = '$FIXTURE_ID'
      and name_ko = '$BASELINE_NAME'
      and not is_featured
  ) then
    raise exception 'baseline_projection_was_not_created';
  end if;

  if not exists (
    select 1
    from public.exhibition_catalog_v2 as catalog
    join public.exhibitions as legacy using (id)
    where catalog.id = '$FIXTURE_ID'
      and content_private.exhibition_catalog_v2_payload(catalog)
        = content_private.legacy_exhibition_catalog_v2_payload(legacy)
  ) then
    raise exception 'exact_legacy_fixture_was_not_created';
  end if;
end
\$fixture_assertion\$;

commit;
SQL

echo "Launching queued legacy-writer bridge regression..."
run_psql "$APP_BRIDGE_ACTIVATION" >"$BRIDGE_ACTIVATION_LOG" 2>&1 <<SQL &
\set VERBOSITY verbose
set statement_timeout = '$((WAIT_TIMEOUT_SECONDS + 10))s';
set lock_timeout = '$((WAIT_TIMEOUT_SECONDS + 5))s';

begin;

set local role service_role;

select public.admin_enable_legacy_exhibition_mirror(
  integrity.row_count,
  integrity.id_checksum_sha256,
  integrity.catalog_checksum_sha256,
  '$BRIDGE_REASON'
)
from public.exhibition_catalog_v2_integrity(null, false) as integrity;

reset role;

do \$bridge_wait\$
declare
  v_deadline timestamptz :=
    clock_timestamp() + make_interval(secs => $WAIT_TIMEOUT_SECONDS);
begin
  loop
    -- The writer must be actively waiting on the activation transaction's
    -- table lock before activation is allowed to commit.
    perform pg_catalog.pg_stat_clear_snapshot();

    if exists (
      select 1
      from pg_catalog.pg_stat_activity as activity
      where activity.application_name = '$APP_LEGACY_WRITER'
        and activity.state = 'active'
        and activity.wait_event_type = 'Lock'
        and activity.query like '%update public.exhibitions as legacy%'
    ) then
      raise notice 'queued legacy writer observed behind bridge activation';
      return;
    end if;

    if clock_timestamp() >= v_deadline then
      raise exception
        'bridge_activation_timed_out_waiting_for_queued_legacy_writer';
    end if;

    perform pg_catalog.pg_sleep(0.05);
  end loop;
end
\$bridge_wait\$;

commit;
SQL
BRIDGE_ACTIVATION_PID=$!

wait_for_bridge_activation_gate

run_psql "$APP_LEGACY_WRITER" >"$LEGACY_WRITER_LOG" 2>&1 <<SQL &
\set VERBOSITY verbose
set statement_timeout = '$((WAIT_TIMEOUT_SECONDS + 10))s';
set lock_timeout = '$((WAIT_TIMEOUT_SECONDS + 5))s';

begin;
set local role service_role;

do \$writer_authorization\$
begin
  if not pg_catalog.has_table_privilege(
    current_user,
    'public.exhibitions',
    'UPDATE'
  ) then
    raise exception 'queued_writer_did_not_observe_update_authorization';
  end if;

  raise notice 'queued writer observed service-role UPDATE authorization';
end
\$writer_authorization\$;

update public.exhibitions as legacy
set name_ko = '$LEGACY_WRITER_NAME'
where legacy.id = '$FIXTURE_ID';

commit;
SQL
LEGACY_WRITER_PID=$!

if wait "$BRIDGE_ACTIVATION_PID"; then
  BRIDGE_ACTIVATION_STATUS=0
else
  BRIDGE_ACTIVATION_STATUS=$?
fi
BRIDGE_ACTIVATION_PID=""

if wait "$LEGACY_WRITER_PID"; then
  LEGACY_WRITER_STATUS=0
else
  LEGACY_WRITER_STATUS=$?
fi
LEGACY_WRITER_PID=""

if [ "$BRIDGE_ACTIVATION_STATUS" -ne 0 ]; then
  echo "Bridge activation session failed ($BRIDGE_ACTIVATION_STATUS)." >&2
  echo "--- bridge activation ---" >&2
  sed -n '1,240p' "$BRIDGE_ACTIVATION_LOG" >&2
  echo "--- queued legacy writer ---" >&2
  sed -n '1,240p' "$LEGACY_WRITER_LOG" >&2
  exit 1
fi

if [ "$LEGACY_WRITER_STATUS" -eq 0 ]; then
  echo "Queued legacy writer unexpectedly committed after bridge activation." >&2
  echo "--- bridge activation ---" >&2
  sed -n '1,240p' "$BRIDGE_ACTIVATION_LOG" >&2
  echo "--- queued legacy writer ---" >&2
  sed -n '1,240p' "$LEGACY_WRITER_LOG" >&2
  exit 1
fi

if ! grep -Fq \
  'queued writer observed service-role UPDATE authorization' \
  "$LEGACY_WRITER_LOG"
then
  echo "Queued writer did not prove pre-lock UPDATE authorization." >&2
  echo "--- queued legacy writer ---" >&2
  sed -n '1,240p' "$LEGACY_WRITER_LOG" >&2
  exit 1
fi

if ! grep -Eq \
  '42501: permission denied for table exhibitions|55000: legacy_exhibitions_managed_by_canonical' \
  "$LEGACY_WRITER_LOG"
then
  echo "Queued writer failed for an unexpected reason." >&2
  echo "--- queued legacy writer ---" >&2
  sed -n '1,240p' "$LEGACY_WRITER_LOG" >&2
  exit 1
fi

echo "Asserting queued writer rejection and restoring the default local state..."
run_psql "$APP_CONTROL" >/dev/null <<SQL
do \$bridge_assertion\$
begin
  if not exists (
    select 1
    from content_private.exhibition_catalog_runtime as runtime
    where runtime.singleton
      and runtime.legacy_mirror_enabled
      and runtime.legacy_writes_blocked
      and runtime.reason = '$BRIDGE_REASON'
  ) then
    raise exception 'bridge_runtime_was_not_enabled_and_blocked';
  end if;

  if pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'INSERT'
    )
    or pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'UPDATE'
    )
    or pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'DELETE'
    )
    or pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'TRUNCATE'
    ) then
    raise exception 'bridge_activation_did_not_revoke_legacy_dml';
  end if;

  if not exists (
    select 1
    from public.exhibitions
    where id = '$FIXTURE_ID'
      and name_ko = '$BASELINE_NAME'
  ) then
    raise exception 'queued_legacy_writer_changed_the_legacy_payload';
  end if;

  if not exists (
    select 1
    from content.audit_log
    where action = 'legacy_exhibition_mirror.enabled'
      and entity_type = 'system_setting'
      and entity_id = 'legacy_exhibition_mirror'
      and metadata ->> 'reason' = '$BRIDGE_REASON'
  ) then
    raise exception 'bridge_activation_audit_record_is_missing';
  end if;
end
\$bridge_assertion\$;

begin;

select pg_catalog.pg_advisory_xact_lock(73241, 1);
lock table public.exhibitions in share row exclusive mode;
lock table public.exhibition_catalog_v2 in share mode;

update content_private.exhibition_catalog_runtime
set
  legacy_mirror_enabled = false,
  legacy_writes_blocked = false,
  legacy_mirror_enabled_at = null,
  baseline_row_count = null,
  baseline_id_checksum_sha256 = null,
  baseline_catalog_checksum_sha256 = null,
  reason = 'installed disabled'
where singleton;

grant insert, update, delete, truncate
  on public.exhibitions to service_role;

delete from public.exhibitions
where id = '$FIXTURE_ID';

delete from content.audit_log
where action = 'legacy_exhibition_mirror.enabled'
  and entity_type = 'system_setting'
  and entity_id = 'legacy_exhibition_mirror'
  and metadata ->> 'reason' = '$BRIDGE_REASON';

do \$restore_assertion\$
begin
  if not exists (
    select 1
    from content_private.exhibition_catalog_runtime as runtime
    where runtime.singleton
      and not runtime.legacy_mirror_enabled
      and not runtime.legacy_writes_blocked
      and runtime.legacy_mirror_enabled_at is null
      and runtime.baseline_row_count is null
      and runtime.baseline_id_checksum_sha256 is null
      and runtime.baseline_catalog_checksum_sha256 is null
      and runtime.reason = 'installed disabled'
  ) then
    raise exception 'default_runtime_state_was_not_restored';
  end if;

  if not pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'INSERT'
    )
    or not pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'UPDATE'
    )
    or not pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'DELETE'
    )
    or not pg_catalog.has_table_privilege(
      'service_role', 'public.exhibitions', 'TRUNCATE'
    ) then
    raise exception 'legacy_service_role_dml_was_not_restored';
  end if;

  if exists (
    select 1 from public.exhibitions where id = '$FIXTURE_ID'
  ) or exists (
    select 1
    from content.audit_log
    where action = 'legacy_exhibition_mirror.enabled'
      and entity_type = 'system_setting'
      and entity_id = 'legacy_exhibition_mirror'
      and metadata ->> 'reason' = '$BRIDGE_REASON'
  ) then
    raise exception 'bridge_fixture_state_was_not_removed';
  end if;
end
\$restore_assertion\$;

commit;
SQL

echo "Launching controlled concurrent source updates..."
run_psql "$APP_SESSION_A" >"$SESSION_A_LOG" 2>&1 <<SQL &
set statement_timeout = '$((WAIT_TIMEOUT_SECONDS + 10))s';
set lock_timeout = '$((WAIT_TIMEOUT_SECONDS + 5))s';

begin;

update content.exhibition_versions
set name_ko = '$SESSION_A_NAME'
where exhibition_id = '$FIXTURE_ID'
  and version_number = 1;

do \$session_a_wait\$
declare
  v_deadline timestamptz :=
    clock_timestamp() + make_interval(secs => $WAIT_TIMEOUT_SECONDS);
begin
  loop
    -- Statistics snapshots are cached for the duration of a transaction. Clear
    -- the cache so this long-running DO block can observe session B arriving.
    perform pg_catalog.pg_stat_clear_snapshot();

    if exists (
      select 1
      from pg_catalog.pg_stat_activity as activity
      where activity.application_name = '$APP_SESSION_B'
        and activity.state = 'active'
        and activity.wait_event_type = 'Lock'
        and activity.query like '%update content.curation_placements%'
    ) then
      return;
    end if;

    if clock_timestamp() >= v_deadline then
      raise exception 'session_a_timed_out_waiting_for_session_b_lock';
    end if;

    perform pg_catalog.pg_sleep(0.05);
  end loop;
end
\$session_a_wait\$;

commit;
SQL
SESSION_A_PID=$!

wait_for_session_a_gate

run_psql "$APP_SESSION_B" >"$SESSION_B_LOG" 2>&1 <<SQL &
set statement_timeout = '$((WAIT_TIMEOUT_SECONDS + 10))s';
set lock_timeout = '$((WAIT_TIMEOUT_SECONDS + 5))s';

begin;

update content.curation_placements
set enabled = true
where exhibition_id = '$FIXTURE_ID'
  and surface = 'app_featured'::content.curation_surface;

commit;
SQL
SESSION_B_PID=$!

if wait "$SESSION_A_PID"; then
  SESSION_A_STATUS=0
else
  SESSION_A_STATUS=$?
fi
SESSION_A_PID=""

if wait "$SESSION_B_PID"; then
  SESSION_B_STATUS=0
else
  SESSION_B_STATUS=$?
fi
SESSION_B_PID=""

if [ "$SESSION_A_STATUS" -ne 0 ] || [ "$SESSION_B_STATUS" -ne 0 ]; then
  echo "Concurrent session failed (A=$SESSION_A_STATUS, B=$SESSION_B_STATUS)." >&2
  echo "--- session A ---" >&2
  sed -n '1,200p' "$SESSION_A_LOG" >&2
  echo "--- session B ---" >&2
  sed -n '1,200p' "$SESSION_B_LOG" >&2
  exit 1
fi

echo "Asserting projection equality and global reconciliation..."
run_psql "$APP_CONTROL" <<SQL
set timezone = 'UTC';

select jsonb_build_object(
  'fixture_id', source.id,
  'canonical_name_ko', source.name_ko,
  'projected_name_ko', catalog.name_ko,
  'canonical_is_featured', source.is_featured,
  'projected_is_featured', catalog.is_featured,
  'payloads_equal',
    canonical.payload || jsonb_build_object(
      'gallery_id', gallery_source.gallery_id
    ) =
      content_private.exhibition_catalog_v2_payload(catalog)
) as fixture_result
from content_private.exhibition_catalog_v2_source('$FIXTURE_ID') as source
join content_private.exhibition_catalog_v2_source_payload('$FIXTURE_ID')
  as canonical using (id)
join content.gallery_catalog_sources as gallery_source
  on gallery_source.source = 'public.exhibition_catalog_v2'
  and gallery_source.source_key =
    content_private.normalize_gallery_catalog_name(
      canonical.payload ->> 'venue_name_ko'
    )
join public.exhibition_catalog_v2 as catalog using (id);

do \$final_assertion\$
declare
  v_report jsonb;
begin
  if not exists (
    select 1
    from content_private.exhibition_catalog_v2_source('$FIXTURE_ID') as source
    join content_private.exhibition_catalog_v2_source_payload('$FIXTURE_ID')
      as canonical using (id)
    join content.gallery_catalog_sources as gallery_source
      on gallery_source.source = 'public.exhibition_catalog_v2'
      and gallery_source.source_key =
        content_private.normalize_gallery_catalog_name(
          canonical.payload ->> 'venue_name_ko'
        )
    join public.exhibition_catalog_v2 as catalog using (id)
    where source.name_ko = '$SESSION_A_NAME'
      and source.is_featured
      and canonical.payload || jsonb_build_object(
        'gallery_id', gallery_source.gallery_id
      ) =
        content_private.exhibition_catalog_v2_payload(catalog)
      and catalog.content_checksum_sha256 =
        content_private.sha256_canonical_jsonb(
          canonical.payload || jsonb_build_object(
            'gallery_id', gallery_source.gallery_id
          )
        )
  ) then
    raise exception
      'concurrency_regression: projection does not equal canonical source';
  end if;

  v_report := public.admin_reconcile_exhibition_catalog_v2();
  if not coalesce((v_report ->> 'in_sync')::boolean, false) then
    raise exception using
      message = 'concurrency_regression: reconciliation is out of sync',
      detail = v_report::text;
  end if;

  raise notice 'reconciliation: %', v_report;
end
\$final_assertion\$;
SQL

echo \
  "PASS: bridge activation rejected the queued legacy writer, and concurrent" \
  "source writes produced a canonical, reconciled catalog row."
