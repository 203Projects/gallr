#!/usr/bin/env bash

# Local-only two-session regression for the public-site rebuild coalescing lock.
# It must only target a disposable local Supabase Docker database.

set -eu
set -o pipefail

if [ -n "${ZSH_VERSION:-}" ]; then
  setopt NO_BG_NICE
fi

DB_CONTAINER="${SUPABASE_DB_CONTAINER:-supabase_db_gallr}"
DB_USER="${SUPABASE_DB_USER:-postgres}"
DB_NAME="${SUPABASE_DB_NAME:-postgres}"
WAIT_TIMEOUT_SECONDS="${GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS:-20}"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required to run this local concurrency test." >&2
  exit 2
fi
if [ "$(docker inspect --format '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null || true)" != "true" ]; then
  echo "Local Supabase database container '$DB_CONTAINER' is not running." >&2
  exit 2
fi
case "$WAIT_TIMEOUT_SECONDS" in
  ''|*[!0-9]*|0)
    echo "GALLR_CONCURRENCY_WAIT_TIMEOUT_SECONDS must be a positive integer." >&2
    exit 2
    ;;
esac

RUN_TOKEN="$(date -u '+%Y%m%d%H%M%S')-$$"
APP_CONTROL="gallr_rebuild_control_$RUN_TOKEN"
APP_A="gallr_rebuild_a_$RUN_TOKEN"
APP_B="gallr_rebuild_b_$RUN_TOKEN"
EVENT_A="$(printf '%08x-0000-4000-8000-%012x' $$ $((RANDOM + 1)))"
EVENT_B="$(printf '%08x-0000-4000-8000-%012x' $$ $((RANDOM + 2)))"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/gallr-rebuild-concurrency.XXXXXX")"
LOG_A="$TMP_DIR/session-a.log"
LOG_B="$TMP_DIR/session-b.log"
PID_A=""
PID_B=""

run_psql() {
  local app_name="$1"
  shift
  docker exec -i \
    --env "PGAPPNAME=$app_name" \
    "$DB_CONTAINER" \
    psql -X -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" "$@"
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
  stop_child "$PID_A"
  stop_child "$PID_B"
  run_psql "$APP_CONTROL" >/dev/null <<SQL || true
delete from content.outbox_events
where id in ('$EVENT_A'::uuid, '$EVENT_B'::uuid)
   or (
     event_type = 'public_site.rebuild_requested'
     and (
       payload ->> 'first_event_id' in ('$EVENT_A', '$EVENT_B')
       or payload ->> 'latest_event_id' in ('$EVENT_A', '$EVENT_B')
     )
   );
SQL
  rm -rf "$TMP_DIR"
  exit "$exit_status"
}
trap cleanup EXIT HUP INT TERM

wait_for_advisory_gate() {
  local app_name="$1"
  local started_at
  local now
  local ready
  started_at="$(date '+%s')"
  while :; do
    ready="$(run_psql "$APP_CONTROL" -Atc "
      select exists (
        select 1
        from pg_catalog.pg_stat_activity
        where application_name = '$app_name'
          and state = 'active'
          and wait_event_type = 'Lock'
          and wait_event = 'advisory'
      );
    ")"
    if [ "$ready" = "t" ]; then
      return 0
    fi
    now="$(date '+%s')"
    if [ $((now - started_at)) -ge "$WAIT_TIMEOUT_SECONDS" ]; then
      echo "Timed out waiting for $app_name advisory lock." >&2
      return 1
    fi
    sleep 0.05
  done
}

run_psql "$APP_CONTROL" >/dev/null <<SQL
delete from content.outbox_events
where event_type = 'public_site.rebuild_requested'
  and status in ('pending'::content.outbox_status, 'failed'::content.outbox_status);
SQL

run_psql "$APP_A" >"$LOG_A" 2>&1 <<SQL &
begin;
insert into content.outbox_events (
  id, aggregate_type, aggregate_id, event_type, payload, deduplication_key
) values (
  '$EVENT_A'::uuid,
  'exhibition',
  'rebuild-concurrency-a',
  'exhibition.published',
  '{}'::jsonb,
  'rebuild-concurrency-a-$RUN_TOKEN'
);
select pg_sleep(1.5);
commit;
SQL
PID_A=$!

sleep 0.1
run_psql "$APP_B" >"$LOG_B" 2>&1 <<SQL &
insert into content.outbox_events (
  id, aggregate_type, aggregate_id, event_type, payload, deduplication_key
) values (
  '$EVENT_B'::uuid,
  'exhibition',
  'rebuild-concurrency-b',
  'exhibition.archived',
  '{}'::jsonb,
  'rebuild-concurrency-b-$RUN_TOKEN'
);
SQL
PID_B=$!

wait_for_advisory_gate "$APP_B"
wait "$PID_A"
PID_A=""
wait "$PID_B"
PID_B=""

run_psql "$APP_CONTROL" -Atc "
  select case
    when count(*) = 1
      and min((payload ->> 'source_event_count')::integer) = 2
      and min(payload ->> 'first_event_id') = '$EVENT_A'
      and min(payload ->> 'latest_event_id') = '$EVENT_B'
      and min(available_at) >= max(created_at) + interval '29 seconds'
    then 'ok'
    else 'bad'
  end
  from content.outbox_events
  where event_type = 'public_site.rebuild_requested'
    and status = 'pending'::content.outbox_status;
" | grep -qx 'ok'

echo "public-site rebuild coalescing concurrency test passed"
