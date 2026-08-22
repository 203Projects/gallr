#!/usr/bin/env bash

# Local-only two-session regression for one-Kit-per-exhibition free activation.
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
APP_CONTROL="gallr_launch_control_$RUN_TOKEN"
APP_A="gallr_launch_a_$RUN_TOKEN"
APP_B="gallr_launch_b_$RUN_TOKEN"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/gallr-launch-concurrency.XXXXXX")"
LOG_A="$TMP_DIR/session-a.log"
LOG_B="$TMP_DIR/session-b.log"
PID_A=""
PID_B=""
USER_ID=""
GALLERY_ID=""
VERSION_ID=""
REQUEST_A=""
REQUEST_B=""
EXHIBITION_ID="gallery-launch-concurrency-$RUN_TOKEN"

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
  if [ -n "$USER_ID" ] && [ -n "$GALLERY_ID" ] && [ -n "$VERSION_ID" ]; then
    run_psql "$APP_CONTROL" >/dev/null <<SQL || true
delete from content.command_requests
where actor_user_id = '$USER_ID'::uuid
  and command_name = 'owner_activate_launch_kit';
delete from content.audit_log
where actor_user_id = '$USER_ID'::uuid
  and action = 'launch_kit.activated'
  and entity_type = 'launch_kit';
delete from content.launch_kits
where exhibition_id = '$EXHIBITION_ID';
delete from content.outbox_events
where aggregate_id = '$EXHIBITION_ID'
   or payload ->> 'exhibition_id' = '$EXHIBITION_ID';
update content.exhibitions
set published_version_id = null
where id = '$EXHIBITION_ID';
delete from content.exhibition_versions
where id = '$VERSION_ID'::uuid
  and exhibition_id = '$EXHIBITION_ID';
delete from content.exhibitions
where id = '$EXHIBITION_ID';
delete from content.gallery_memberships
where gallery_id = '$GALLERY_ID'::uuid
  and user_id = '$USER_ID'::uuid;
delete from content.galleries
where id = '$GALLERY_ID'::uuid
  and name_en = 'Gallery launch concurrency $RUN_TOKEN';
delete from auth.users
where id = '$USER_ID'::uuid;
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

USER_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
GALLERY_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
VERSION_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
REQUEST_A="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
REQUEST_B="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
CLAIMS="{\"sub\":\"$USER_ID\",\"role\":\"authenticated\"}"

run_psql "$APP_CONTROL" >/dev/null <<SQL
insert into auth.users (
  id, email, email_confirmed_at, raw_user_meta_data
) values (
  '$USER_ID'::uuid,
  'launch-concurrency-$RUN_TOKEN@example.invalid',
  now(),
  '{}'::jsonb
);
insert into content.galleries (id, name_ko, name_en, status)
values (
  '$GALLERY_ID'::uuid,
  '런치 동시성 $RUN_TOKEN',
  'Gallery launch concurrency $RUN_TOKEN',
  'active'
);
insert into content.gallery_memberships (
  gallery_id, user_id, status, claim_note
) values (
  '$GALLERY_ID'::uuid,
  '$USER_ID'::uuid,
  'active',
  'local concurrency regression'
);
insert into content.exhibitions (id, gallery_id, owner_status)
values ('$EXHIBITION_ID', '$GALLERY_ID'::uuid, 'published');
insert into content.exhibition_versions (
  id, exhibition_id, version_number, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko,
  opening_date, closing_date, hours, reception_date, opening_time,
  latitude, longitude, published_at
) values (
  '$VERSION_ID'::uuid, '$EXHIBITION_ID', 1, 'published',
  '런치 동시성 전시', 'Gallery launch concurrency exhibition',
  '런치 동시성 갤러리', 'Gallery launch concurrency venue',
  '서울', 'Seoul', '종로구', 'Jongno-gu', '서울 종로구',
  current_date, current_date + 30, '11:00-18:00',
  now() + interval '7 days', '19:00', 37.573, 126.979, now()
);
update content.exhibitions
set published_version_id = '$VERSION_ID'::uuid
where id = '$EXHIBITION_ID';
SQL

# Session A holds the unique exhibition entitlement open after activation.
# Session B reaches the same unique key with a distinct request ID and must
# converge on Session A's committed Kit rather than audit or insert another.
run_psql "$APP_A" >"$LOG_A" 2>&1 <<SQL &
begin;
set local role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', true);
select public.owner_activate_launch_kit(
  '$EXHIBITION_ID', '$REQUEST_A'::uuid
);
select pg_sleep(1.5);
commit;
SQL
PID_A=$!
wait_for_sleep_gate "$APP_A"
run_psql "$APP_B" >"$LOG_B" 2>&1 <<SQL &
set role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', false);
select public.owner_activate_launch_kit(
  '$EXHIBITION_ID', '$REQUEST_B'::uuid
);
SQL
PID_B=$!
wait "$PID_A"
PID_A=""
wait "$PID_B"
PID_B=""

run_psql "$APP_CONTROL" -Atc "
  select case
    when (
      select count(*) = 1
        and bool_and(status = 'active'::content.launch_kit_status)
        and bool_and(
          entitlement_source = 'free_beta'::content.launch_kit_entitlement_source
        )
        and bool_and(activated_at is not null)
        and bool_and(revision = 1)
        and bool_and(stripe_price_id is null)
        and bool_and(stripe_checkout_session_id is null)
        and bool_and(stripe_payment_intent_id is null)
        and bool_and(stripe_event_id is null)
        and bool_and(amount_total is null)
        and bool_and(currency is null)
        and bool_and(checkout_attempt = 0)
      from content.launch_kits
      where exhibition_id = '$EXHIBITION_ID'
    )
    and (
      select count(*) = 1
      from content.audit_log
      where action = 'launch_kit.activated'
        and entity_type = 'launch_kit'
        and metadata ->> 'exhibition_id' = '$EXHIBITION_ID'
    )
    and (
      select count(*) = 2
        and bool_and(completed_at is not null)
        and bool_and(response ->> 'id' is not null)
        and count(distinct response ->> 'id') = 1
        and bool_and(
          (response ->> 'entitlement_source') is not distinct from 'free_beta'
        )
        and bool_and(
          (response ->> 'id')::uuid = (
            select id
            from content.launch_kits
            where exhibition_id = '$EXHIBITION_ID'
          )
        )
      from content.command_requests
      where actor_user_id = '$USER_ID'::uuid
        and request_id in ('$REQUEST_A'::uuid, '$REQUEST_B'::uuid)
        and command_name = 'owner_activate_launch_kit'
    )
    then 'ok'
    else 'bad'
  end;
" | grep -qx 'ok'

echo "PASS: gallery launch activation concurrency"
