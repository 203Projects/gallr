#!/usr/bin/env bash

# Local-only two-session regression for spec 089 personal routes: simultaneous saves of one route run one
# after another (last writer wins, never a mix or a failure), and a reader during a save sees one version.

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
APP_CONTROL="gallr_routes_control_$RUN_TOKEN"
APP_A="gallr_routes_a_$RUN_TOKEN"
APP_B="gallr_routes_b_$RUN_TOKEN"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/gallr-routes-concurrency.XXXXXX")"
LOG_A="$TMP_DIR/session-a.log"
LOG_B="$TMP_DIR/session-b.log"
PID_A=""
PID_B=""
USER_ID=""
EX1="route-concurrency-1-$RUN_TOKEN"
EX2="route-concurrency-2-$RUN_TOKEN"
EX3="route-concurrency-3-$RUN_TOKEN"

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
  run_psql "$APP_CONTROL" -c "delete from public.exhibition_catalog_v2 where id in ('$EX1', '$EX2', '$EX3');" \
    >/dev/null || true
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
ROUTE_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
CLAIMS="{\"sub\":\"$USER_ID\",\"role\":\"authenticated\"}"
run_psql "$APP_CONTROL" >/dev/null <<SQL
insert into auth.users (id, email, raw_user_meta_data)
values ('$USER_ID'::uuid, 'routes-concurrency-$RUN_TOKEN@example.invalid', '{}'::jsonb);
insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
)
select id, '전시', 'Show', '갤러리', 'Gallery', '서울', 'Seoul', '종로구', 'Jongno-gu',
  current_date - 1, current_date + 30, false, 37.58, 126.98, '', '', '', '', false, now(), false, repeat('0', 64)
from unnest(array['$EX1', '$EX2', '$EX3']) as id;
SQL

save_sql() {
  printf "select public.save_personal_route('%s'::uuid, '%s', array['%s','%s']);" "$ROUTE_ID" "$1" "$2" "$3"
}

stops_sql="select string_agg(exhibition_id, ',' order by position) from public.personal_route_stops where route_id = '$ROUTE_ID'::uuid;"

run_psql "$APP_CONTROL" >/dev/null <<SQL
set role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', false);
$(save_sql '시작' "$EX1" "$EX2")
SQL

# Session A holds the route lock while it sleeps; session B saves the same route and must wait, then win.
run_psql "$APP_A" >"$LOG_A" 2>&1 <<SQL &
begin;
set local role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', true);
$(save_sql 'A' "$EX2" "$EX3")
select pg_sleep(1.5);
commit;
SQL
PID_A=$!
wait_for_sleep_gate "$APP_A"

# A reader during the uncommitted save sees the previous version, whole.
during="$(run_psql "$APP_CONTROL" -Atc "$stops_sql")"
if [ "$during" != "$EX1,$EX2" ]; then
  echo "Reader saw '$during' during a save; expected the previous version." >&2
  exit 1
fi

run_psql "$APP_B" >"$LOG_B" 2>&1 <<SQL &
set role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', false);
$(save_sql 'B' "$EX3" "$EX1")
SQL
PID_B=$!
STATUS_A=0
STATUS_B=0
wait "$PID_A" || STATUS_A=$?
PID_A=""
wait "$PID_B" || STATUS_B=$?
PID_B=""

if [ "$STATUS_A" -ne 0 ] || [ "$STATUS_B" -ne 0 ] || grep -q ERROR "$LOG_A" "$LOG_B"; then
  echo "A concurrent save failed (session A exit $STATUS_A, session B exit $STATUS_B):" >&2
  cat "$LOG_A" "$LOG_B" >&2
  exit 1
fi

after="$(run_psql "$APP_CONTROL" -Atc "$stops_sql")"
name="$(run_psql "$APP_CONTROL" -Atc "select name from public.personal_routes where id = '$ROUTE_ID'::uuid;")"
if [ "$after" != "$EX3,$EX1" ] || [ "$name" != "B" ]; then
  echo "Expected the later save to win whole; got stops '$after' and name '$name'." >&2
  exit 1
fi

echo "PASS: personal routes concurrency"
