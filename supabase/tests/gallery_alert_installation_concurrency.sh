#!/usr/bin/env bash

# Local-only two-session regression for idempotent gallery-alert preferences.
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
APP_CONTROL="gallr_alert_control_$RUN_TOKEN"
APP_A="gallr_alert_a_$RUN_TOKEN"
APP_B="gallr_alert_b_$RUN_TOKEN"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/gallr-alert-concurrency.XXXXXX")"
LOG_A="$TMP_DIR/session-a.log"
LOG_B="$TMP_DIR/session-b.log"
PID_A=""
PID_B=""
INSTALLATION_ID=""
GALLERY_ID=""
OUTBOX_EVENT_ID=""
INSTALLATION_SECRET="gallery-alert-concurrency-secret-$RUN_TOKEN-000000000000"

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
  if [ -n "$INSTALLATION_ID" ] && [ -n "$GALLERY_ID" ]; then
    run_psql "$APP_CONTROL" >/dev/null <<SQL || true
delete from content_private.gallery_alert_installations
where id = '$INSTALLATION_ID'::uuid;
delete from content.galleries
where id = '$GALLERY_ID'::uuid
  and name_en = 'Gallery alert concurrency $RUN_TOKEN';
SQL
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
        select 1
        from pg_catalog.pg_stat_activity
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

INSTALLATION_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
GALLERY_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
OUTBOX_EVENT_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"

run_psql "$APP_CONTROL" >/dev/null <<SQL
insert into content.galleries (id, name_ko, name_en, status)
values (
  '$GALLERY_ID'::uuid,
  '알림 동시성 $RUN_TOKEN',
  'Gallery alert concurrency $RUN_TOKEN',
  'active'
);
select public.register_gallery_alert_installation(
  '$INSTALLATION_ID'::uuid,
  '$INSTALLATION_SECRET',
  'ios',
  'ko-KR',
  0
);
SQL

# Two concurrent first-time enables must converge on one revision-1 row.
run_psql "$APP_A" >"$LOG_A" 2>&1 <<SQL &
begin;
set local role anon;
select public.set_gallery_alert_subscription(
  '$INSTALLATION_ID'::uuid,
  '$INSTALLATION_SECRET',
  '$GALLERY_ID'::uuid,
  true,
  0
);
select pg_sleep(1.5);
commit;
SQL
PID_A=$!
wait_for_sleep_gate "$APP_A"
run_psql "$APP_B" >"$LOG_B" 2>&1 <<SQL &
set role anon;
select public.set_gallery_alert_subscription(
  '$INSTALLATION_ID'::uuid,
  '$INSTALLATION_SECRET',
  '$GALLERY_ID'::uuid,
  true,
  0
);
SQL
PID_B=$!
wait "$PID_A"
PID_A=""
wait "$PID_B"
PID_B=""

run_psql "$APP_CONTROL" -Atc "
  select case
    when count(*) = 1 and bool_and(enabled) and min(revision) = 1
      then 'ok'
    else 'bad'
  end
  from content_private.gallery_alert_subscriptions
  where installation_id = '$INSTALLATION_ID'::uuid
    and gallery_id = '$GALLERY_ID'::uuid;
" | grep -qx 'ok'

# A queued contradictory stale update must fail after the winning revision
# commits; it must not overwrite the winner.
run_psql "$APP_A" >"$LOG_A" 2>&1 <<SQL &
begin;
set local role anon;
select public.set_gallery_alert_subscription(
  '$INSTALLATION_ID'::uuid,
  '$INSTALLATION_SECRET',
  '$GALLERY_ID'::uuid,
  false,
  1
);
select pg_sleep(1.5);
commit;
SQL
PID_A=$!
wait_for_sleep_gate "$APP_A"
run_psql "$APP_B" >"$LOG_B" 2>&1 <<SQL &
set role anon;
select public.set_gallery_alert_subscription(
  '$INSTALLATION_ID'::uuid,
  '$INSTALLATION_SECRET',
  '$GALLERY_ID'::uuid,
  true,
  1
);
SQL
PID_B=$!
wait "$PID_A"
PID_A=""
set +e
wait "$PID_B"
SESSION_B_STATUS=$?
set -e
PID_B=""
if [ "$SESSION_B_STATUS" -eq 0 ]; then
  echo "Contradictory stale update unexpectedly succeeded." >&2
  exit 1
fi
grep -q 'revision_conflict' "$LOG_B"

run_psql "$APP_CONTROL" -Atc "
  select case
    when count(*) = 1 and not bool_or(enabled) and min(revision) = 2
      then 'ok'
    else 'bad'
  end
  from content_private.gallery_alert_subscriptions
  where installation_id = '$INSTALLATION_ID'::uuid
    and gallery_id = '$GALLERY_ID'::uuid;
" | grep -qx 'ok'

# Fan-out materialization must wait behind the per-publication advisory lock.
# The synthetic event does not exist, so the function fails validation only
# after proving that it waited for the exact lock first.
run_psql "$APP_A" >"$LOG_A" 2>&1 <<SQL &
begin;
select pg_catalog.pg_advisory_xact_lock(
  pg_catalog.hashtextextended(
    'gallery-alert-fanout:$OUTBOX_EVENT_ID',
    0
  )
);
select pg_sleep(1.5);
commit;
SQL
PID_A=$!
wait_for_sleep_gate "$APP_A"
run_psql "$APP_B" >"$LOG_B" 2>&1 <<SQL &
select public.claim_gallery_alert_delivery_jobs(
  '$OUTBOX_EVENT_ID'::uuid,
  'gallery-alert-concurrency',
  30,
  1
);
SQL
PID_B=$!
wait_for_advisory_gate "$APP_B"
wait "$PID_A"
PID_A=""
set +e
wait "$PID_B"
SESSION_B_STATUS=$?
set -e
PID_B=""
if [ "$SESSION_B_STATUS" -eq 0 ]; then
  echo "Invalid fan-out event unexpectedly succeeded." >&2
  exit 1
fi
grep -q 'gallery_alert_event_invalid' "$LOG_B"

echo "PASS: gallery alert installation concurrency"
