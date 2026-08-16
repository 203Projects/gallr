#!/usr/bin/env bash

# Local-only two-session regression for serialized My Gallr account mutations.

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
APP_CONTROL="gallr_archive_control_$RUN_TOKEN"
APP_A="gallr_archive_a_$RUN_TOKEN"
APP_B="gallr_archive_b_$RUN_TOKEN"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/gallr-archive-concurrency.XXXXXX")"
LOG_A="$TMP_DIR/session-a.log"
LOG_B="$TMP_DIR/session-b.log"
PID_A=""
PID_B=""
USER_ID=""

run_psql() {
  local app_name="$1"
  shift
  docker exec -i --env "PGAPPNAME=$app_name" "$DB_CONTAINER" \
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
  if [ -n "$USER_ID" ]; then
    run_psql "$APP_CONTROL" -c "delete from auth.users where id = '$USER_ID'::uuid;" >/dev/null || true
  fi
  rm -rf "$TMP_DIR"
  exit "$exit_status"
}
trap cleanup EXIT HUP INT TERM

wait_for_sleep_gate() {
  local app_name="$1"
  local started_at
  local now
  local ready
  started_at="$(date '+%s')"
  while :; do
    ready="$(run_psql "$APP_CONTROL" -Atc "
      select exists (
        select 1 from pg_catalog.pg_stat_activity
        where application_name = '$app_name'
          and state = 'active'
          and wait_event_type = 'Timeout'
          and wait_event = 'PgSleep'
      );
    ")"
    if [ "$ready" = "t" ]; then
      return 0
    fi
    now="$(date '+%s')"
    if [ $((now - started_at)) -ge "$WAIT_TIMEOUT_SECONDS" ]; then
      echo "Timed out waiting for $app_name transaction gate." >&2
      return 1
    fi
    sleep 0.05
  done
}

USER_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
CLAIMS="{\"sub\":\"$USER_ID\",\"role\":\"authenticated\"}"
run_psql "$APP_CONTROL" -c "
  insert into auth.users (id, email, raw_user_meta_data)
  values ('$USER_ID'::uuid, 'archive-concurrency-$RUN_TOKEN@example.invalid', '{}'::jsonb);
" >/dev/null

MUTATION_ADD='[{"mutation_id":"d1000000-0000-4000-8000-000000000001","kind":"add_visit","record":{"client_record_id":"concurrent-record","exhibition_id":"concurrent-exhibition","snapshot":{"name_ko":"동시성 전시","name_en":"Concurrency Exhibition","venue_name_ko":"동시성 갤러리","venue_name_en":"Concurrency Gallery","opening_date":"2026-08-01","closing_date":"2026-08-31","cover_image_url":null},"created_at":"2026-08-14T00:00:00Z"}}]'

# Identical simultaneous retries converge on one receipt and one revision.
run_psql "$APP_A" >"$LOG_A" 2>&1 <<SQL &
begin;
set local role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', true);
select public.sync_my_gallr_archive('$MUTATION_ADD'::jsonb);
select pg_sleep(1.5);
commit;
SQL
PID_A=$!
wait_for_sleep_gate "$APP_A"
run_psql "$APP_B" >"$LOG_B" 2>&1 <<SQL &
set role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', false);
select public.sync_my_gallr_archive('$MUTATION_ADD'::jsonb);
SQL
PID_B=$!
wait "$PID_A"
PID_A=""
wait "$PID_B"
PID_B=""

run_psql "$APP_CONTROL" -Atc "
  select case when archive.revision = 1
    and (select count(*) from content_private.my_gallr_visits where user_id = '$USER_ID') = 1
    and (select count(*) from content_private.my_gallr_mutation_receipts where user_id = '$USER_ID') = 1
  then 'ok' else 'bad' end
  from content_private.my_gallr_archives as archive
  where archive.user_id = '$USER_ID';
" | grep -qx 'ok'

# A later queued removal is applied after the winning add transaction.
MUTATION_REMOVE='[{"mutation_id":"d1000000-0000-4000-8000-000000000002","kind":"remove_visit","record":{"exhibition_id":"concurrent-exhibition"}}]'
run_psql "$APP_A" >"$LOG_A" 2>&1 <<SQL &
begin;
set local role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', true);
select public.sync_my_gallr_archive('[]'::jsonb);
select pg_sleep(1.5);
commit;
SQL
PID_A=$!
wait_for_sleep_gate "$APP_A"
run_psql "$APP_B" >"$LOG_B" 2>&1 <<SQL &
set role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', false);
select public.sync_my_gallr_archive('$MUTATION_REMOVE'::jsonb);
SQL
PID_B=$!
wait "$PID_A"
PID_A=""
wait "$PID_B"
PID_B=""

run_psql "$APP_CONTROL" -Atc "
  select case when archive.revision = 2
    and not exists (
      select 1 from content_private.my_gallr_visits where user_id = '$USER_ID'
    ) then 'ok' else 'bad' end
  from content_private.my_gallr_archives as archive
  where archive.user_id = '$USER_ID';
" | grep -qx 'ok'

echo "PASS: My Gallr account archive concurrency"
