#!/usr/bin/env bash

# Local-only two-session regression for presence-sensitive art metadata saves.
# Both callers use the same optimistic revision; exactly one may commit.

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
APP_CONTROL="gallr_art_control_$RUN_TOKEN"
APP_A="gallr_art_a_$RUN_TOKEN"
APP_B="gallr_art_b_$RUN_TOKEN"
APP_TERM_GATE="gallr_art_term_gate_$RUN_TOKEN"
APP_TERM_SAVE="gallr_art_term_save_$RUN_TOKEN"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/gallr-art-concurrency.XXXXXX")"
LOG_A="$TMP_DIR/session-a.log"
LOG_B="$TMP_DIR/session-b.log"
LOG_TERM_GATE="$TMP_DIR/term-gate.log"
LOG_TERM_SAVE="$TMP_DIR/term-save.log"
PID_A=""
PID_B=""
PID_TERM_GATE=""
PID_TERM_SAVE=""
USER_ID=""
VERSION_ID=""
TERM_VERSION_ID=""
ARTIST_A=""
ARTIST_B=""
EXHIBITION_ID="art-metadata-concurrency-$RUN_TOKEN"
TERM_EXHIBITION_ID="art-term-retirement-concurrency-$RUN_TOKEN"
TERM_ID="style:experimental"

run_psql() {
  local app_name="$1"
  shift
  docker exec -i \
    --env "PGAPPNAME=$app_name" \
    "$DB_CONTAINER" \
    psql -X -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" "$@"
}

wait_for_activity() {
  local app_name="$1"
  local wait_type="$2"
  local wait_event="$3"
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
          and wait_event_type = '$wait_type'
          and ('$wait_event' = '' or wait_event = '$wait_event')
      );
    ")"
    if [ "$ready" = "t" ]; then
      return 0
    fi
    now="$(date '+%s')"
    if [ $((now - started_at)) -ge "$WAIT_TIMEOUT_SECONDS" ]; then
      echo "Timed out waiting for $app_name ($wait_type/$wait_event)." >&2
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
  stop_child "$PID_A"
  stop_child "$PID_B"
  stop_child "$PID_TERM_GATE"
  stop_child "$PID_TERM_SAVE"
  if [ -n "$USER_ID" ] && [ -n "$VERSION_ID" ]; then
    run_psql "$APP_CONTROL" >/dev/null <<SQL || true
update content.art_taxonomy_terms
set active = true
where id = '$TERM_ID';
delete from content.audit_log
where actor_user_id = '$USER_ID'::uuid
  and entity_id in ('$EXHIBITION_ID', '$TERM_EXHIBITION_ID');
delete from content.exhibition_versions
where id = '$TERM_VERSION_ID'::uuid;
delete from content.exhibitions
where id = '$TERM_EXHIBITION_ID';
delete from content.exhibition_versions
where id = '$VERSION_ID'::uuid;
delete from content.exhibitions
where id = '$EXHIBITION_ID';
delete from content.artists
where id in ('$ARTIST_A'::uuid, '$ARTIST_B'::uuid);
delete from content.staff_members
where user_id = '$USER_ID'::uuid;
delete from auth.users
where id = '$USER_ID'::uuid;
SQL
  fi
  rm -rf "$TMP_DIR"
  exit "$exit_status"
}
trap cleanup EXIT HUP INT TERM

USER_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
VERSION_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
TERM_VERSION_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
ARTIST_A="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
ARTIST_B="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
CLAIMS="{\"sub\":\"$USER_ID\",\"role\":\"authenticated\"}"

run_psql "$APP_CONTROL" >/dev/null <<SQL
insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values (
  '$USER_ID'::uuid,
  'art-concurrency-$RUN_TOKEN@example.invalid',
  now(),
  '{}'::jsonb
);
insert into content.staff_members (user_id, role, active)
values ('$USER_ID'::uuid, 'contributor', true);
insert into content.artists (id, name_ko, name_en, created_by, updated_by)
values
  ('$ARTIST_A'::uuid, '동시성 작가 A', 'Concurrency Artist A', '$USER_ID'::uuid, '$USER_ID'::uuid),
  ('$ARTIST_B'::uuid, '동시성 작가 B', 'Concurrency Artist B', '$USER_ID'::uuid, '$USER_ID'::uuid);
insert into content.exhibitions (id, created_by, updated_by)
values
  ('$EXHIBITION_ID', '$USER_ID'::uuid, '$USER_ID'::uuid),
  ('$TERM_EXHIBITION_ID', '$USER_ID'::uuid, '$USER_ID'::uuid);
insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  opening_date, closing_date, latitude, longitude, country_code,
  created_by, updated_by
)
values (
  '$VERSION_ID'::uuid, '$EXHIBITION_ID', 1, 1, 'draft',
  '동시성 전시', 'Concurrency Exhibition', '동시성 갤러리', 'Concurrency Gallery',
  '서울', 'Seoul', '종로구', 'Jongno-gu',
  '서울특별시 종로구 삼청로 10', '10 Samcheong-ro, Jongno-gu, Seoul',
  '2026-09-01', '2026-12-31', 37.582, 126.981, 'KR',
  '$USER_ID'::uuid, '$USER_ID'::uuid
), (
  '$TERM_VERSION_ID'::uuid, '$TERM_EXHIBITION_ID', 1, 1, 'draft',
  '용어 은퇴 동시성 전시', 'Term Retirement Concurrency',
  '동시성 갤러리', 'Concurrency Gallery',
  '서울', 'Seoul', '종로구', 'Jongno-gu',
  '서울특별시 종로구 삼청로 10', '10 Samcheong-ro, Jongno-gu, Seoul',
  '2026-09-01', '2026-12-31', 37.582, 126.981, 'KR',
  '$USER_ID'::uuid, '$USER_ID'::uuid
);
SQL

(
  run_psql "$APP_A" >"$LOG_A" 2>&1 <<SQL
begin;
set local role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', true);
select pg_sleep(0.15);
select public.admin_save_exhibition_draft(
  '$EXHIBITION_ID',
  '$VERSION_ID'::uuid,
  1,
  '{"artists":[{"id":"$ARTIST_A"}]}'::jsonb
);
commit;
SQL
) &
PID_A=$!

(
  run_psql "$APP_B" >"$LOG_B" 2>&1 <<SQL
begin;
set local role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', true);
select pg_sleep(0.15);
select public.admin_save_exhibition_draft(
  '$EXHIBITION_ID',
  '$VERSION_ID'::uuid,
  1,
  '{"artists":[{"id":"$ARTIST_B"}]}'::jsonb
);
commit;
SQL
) &
PID_B=$!

set +e
wait "$PID_A"
STATUS_A=$?
wait "$PID_B"
STATUS_B=$?
set -e
PID_A=""
PID_B=""

if [ "$STATUS_A" -eq 0 ] && [ "$STATUS_B" -eq 0 ]; then
  echo "Both same-revision metadata saves committed." >&2
  exit 1
fi
if [ "$STATUS_A" -ne 0 ] && [ "$STATUS_B" -ne 0 ]; then
  echo "Both same-revision metadata saves failed." >&2
  sed -n '1,80p' "$LOG_A" >&2
  sed -n '1,80p' "$LOG_B" >&2
  exit 1
fi
if ! grep -q 'revision_conflict' "$LOG_A" && ! grep -q 'revision_conflict' "$LOG_B"; then
  echo "The losing metadata save did not report revision_conflict." >&2
  exit 1
fi

FINAL="$(run_psql "$APP_CONTROL" -Atc "
  select version.revision::text || ':' || count(credit.artist_id)::text || ':' ||
    min(credit.artist_id::text)
  from content.exhibition_versions as version
  left join content.exhibition_version_artists as credit
    on credit.version_id = version.id
  where version.id = '$VERSION_ID'::uuid
  group by version.revision;
")"
case "$FINAL" in
  "2:1:$ARTIST_A"|"2:1:$ARTIST_B") ;;
  *)
    echo "Committed revision and artist link diverged: $FINAL" >&2
    exit 1
    ;;
esac

# Hold the exhibition identity after the save validator has observed an active
# term. Retiring that term before the revision trigger applies the patch must
# fail the whole save; silently inserting zero links is never acceptable.
(
  run_psql "$APP_TERM_GATE" >"$LOG_TERM_GATE" 2>&1 <<SQL
begin;
select 1
from content.exhibitions
where id = '$TERM_EXHIBITION_ID'
for update;
select pg_sleep(3);
commit;
SQL
) &
PID_TERM_GATE=$!
wait_for_activity "$APP_TERM_GATE" "Timeout" "PgSleep"

(
  run_psql "$APP_TERM_SAVE" >"$LOG_TERM_SAVE" 2>&1 <<SQL
begin;
set local role authenticated;
select set_config('request.jwt.claims', '$CLAIMS', true);
select public.admin_save_exhibition_draft(
  '$TERM_EXHIBITION_ID',
  '$TERM_VERSION_ID'::uuid,
  1,
  '{"art_term_ids":["$TERM_ID"]}'::jsonb
);
commit;
SQL
) &
PID_TERM_SAVE=$!
wait_for_activity "$APP_TERM_SAVE" "Lock" ""

run_psql "$APP_CONTROL" >/dev/null -c "
  update content.art_taxonomy_terms
  set active = false
  where id = '$TERM_ID';
"

set +e
wait "$PID_TERM_SAVE"
TERM_SAVE_STATUS=$?
wait "$PID_TERM_GATE"
TERM_GATE_STATUS=$?
set -e
PID_TERM_SAVE=""
PID_TERM_GATE=""

if [ "$TERM_GATE_STATUS" -ne 0 ]; then
  echo "Term-retirement lock gate failed." >&2
  sed -n '1,80p' "$LOG_TERM_GATE" >&2
  exit 1
fi
if [ "$TERM_SAVE_STATUS" -eq 0 ]; then
  echo "A term retired after validation was silently dropped from a successful save." >&2
  exit 1
fi
if ! grep -q 'art_metadata_term_unknown' "$LOG_TERM_SAVE"; then
  echo "Retirement-race save failed without the stable term validation error." >&2
  sed -n '1,80p' "$LOG_TERM_SAVE" >&2
  exit 1
fi

TERM_FINAL="$(run_psql "$APP_CONTROL" -Atc "
  select version.revision::text || ':' || count(link.term_id)::text
  from content.exhibition_versions as version
  left join content.exhibition_version_terms as link
    on link.version_id = version.id
  where version.id = '$TERM_VERSION_ID'::uuid
  group by version.revision;
")"
if [ "$TERM_FINAL" != "1:0" ]; then
  echo "Failed retirement-race save changed revision or term links: $TERM_FINAL" >&2
  exit 1
fi
run_psql "$APP_CONTROL" >/dev/null -c "
  update content.art_taxonomy_terms
  set active = true
  where id = '$TERM_ID';
"

echo "PASS: explainable art metadata optimistic concurrency"
