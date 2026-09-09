#!/usr/bin/env bash

# Local-only two-session regression for competing gallery-claim approval.
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
APP_CONTROL="gallr_claim_control_$RUN_TOKEN"
APP_A="gallr_claim_a_$RUN_TOKEN"
APP_B="gallr_claim_b_$RUN_TOKEN"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/gallr-claim-concurrency.XXXXXX")"
LOG_A="$TMP_DIR/session-a.log"
LOG_B="$TMP_DIR/session-b.log"
PID_A=""
PID_B=""
GALLERY_ID=""
CLAIMANT_A=""
CLAIMANT_B=""
PUBLISHER_ID=""
EXHIBITION_A=""
EXHIBITION_B=""

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
  if [ -n "$GALLERY_ID" ]; then
    run_psql "$APP_CONTROL" >/dev/null <<SQL || true
delete from content.outbox_events where aggregate_type = 'gallery' and aggregate_id = '$GALLERY_ID';
delete from content.audit_log where entity_type = 'gallery' and entity_id = '$GALLERY_ID';
delete from content.exhibition_versions
where exhibition_id in ('$EXHIBITION_A', '$EXHIBITION_B');
delete from content.exhibitions
where id in ('$EXHIBITION_A', '$EXHIBITION_B');
delete from content.gallery_memberships where gallery_id = '$GALLERY_ID'::uuid;
delete from content.galleries where id = '$GALLERY_ID'::uuid;
delete from content.staff_members where user_id = '$PUBLISHER_ID'::uuid;
delete from auth.users
where id in ('$CLAIMANT_A'::uuid, '$CLAIMANT_B'::uuid, '$PUBLISHER_ID'::uuid);
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

GALLERY_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
CLAIMANT_A="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
CLAIMANT_B="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
PUBLISHER_ID="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
REQUEST_A="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
REQUEST_B="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
REQUEST_C="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
CREATE_RACE_REQUEST="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
VERSION_A="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
VERSION_B="$(run_psql "$APP_CONTROL" -Atc 'select gen_random_uuid();')"
EXHIBITION_A="claim-a-$RUN_TOKEN"
EXHIBITION_B="claim-b-$RUN_TOKEN"

run_psql "$APP_CONTROL" >/dev/null <<SQL
insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
  ('$CLAIMANT_A'::uuid, 'claim-a-$RUN_TOKEN@example.invalid', now(), '{}'::jsonb),
  ('$CLAIMANT_B'::uuid, 'claim-b-$RUN_TOKEN@example.invalid', now(), '{}'::jsonb),
  ('$PUBLISHER_ID'::uuid, 'claim-publisher-$RUN_TOKEN@example.invalid', now(), '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values ('$PUBLISHER_ID'::uuid, 'publisher', true);

insert into content.galleries (id, name_ko, name_en, status, created_by, updated_by)
values (
  '$GALLERY_ID'::uuid,
  '동시 승인 $RUN_TOKEN',
  'Concurrent approval $RUN_TOKEN',
  'active',
  '$PUBLISHER_ID'::uuid,
  '$PUBLISHER_ID'::uuid
);

insert into content.gallery_memberships (
  gallery_id, user_id, status, claim_note, created_by, updated_by
)
values
  ('$GALLERY_ID'::uuid, '$CLAIMANT_A'::uuid, 'pending', 'claim A', '$CLAIMANT_A'::uuid, '$CLAIMANT_A'::uuid),
  ('$GALLERY_ID'::uuid, '$CLAIMANT_B'::uuid, 'pending', 'claim B', '$CLAIMANT_B'::uuid, '$CLAIMANT_B'::uuid);

insert into content.exhibitions (
  id, gallery_id, owner_status, owner_status_changed_at, created_by, updated_by
)
values
  ('$EXHIBITION_A', '$GALLERY_ID'::uuid, 'draft', now(), '$CLAIMANT_A'::uuid, '$CLAIMANT_A'::uuid),
  ('$EXHIBITION_B', '$GALLERY_ID'::uuid, 'draft', now(), '$CLAIMANT_B'::uuid, '$CLAIMANT_B'::uuid);

insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, venue_name_ko, city_ko, region_ko, address_ko,
  opening_date, closing_date, hours, created_by, updated_by
)
values
  ('$VERSION_A'::uuid, '$EXHIBITION_A', 1, 1, 'draft', '청구 A', '장소', '서울', '종로구', '주소', current_date, current_date + 7, 'Daily', '$CLAIMANT_A'::uuid, '$CLAIMANT_A'::uuid),
  ('$VERSION_B'::uuid, '$EXHIBITION_B', 1, 1, 'draft', '청구 B', '장소', '서울', '종로구', '주소', current_date, current_date + 7, 'Daily', '$CLAIMANT_B'::uuid, '$CLAIMANT_B'::uuid);
SQL

run_psql "$APP_A" >"$LOG_A" 2>&1 <<SQL &
begin;
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"$PUBLISHER_ID","role":"authenticated"}',
  true
);
select public.admin_approve_gallery_claim(
  '$GALLERY_ID'::uuid,
  '$CLAIMANT_A'::uuid,
  '$REQUEST_A'::uuid
);
select pg_sleep(1.5);
commit;
SQL
PID_A=$!
wait_for_sleep_gate "$APP_A"

run_psql "$APP_B" >"$LOG_B" 2>&1 <<SQL &
begin;
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"$PUBLISHER_ID","role":"authenticated"}',
  true
);
select public.admin_approve_gallery_claim(
  '$GALLERY_ID'::uuid,
  '$CLAIMANT_B'::uuid,
  '$REQUEST_B'::uuid
);
commit;
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
  echo "Competing approval unexpectedly succeeded." >&2
  exit 1
fi
grep -q 'gallery_claim_not_pending' "$LOG_B"
if grep -qi 'deadlock detected\|duplicate key value' "$LOG_B"; then
  echo "Competing approval failed through a lock or uniqueness accident." >&2
  exit 1
fi

run_psql "$APP_CONTROL" -Atc "
  select case
    when count(*) filter (where status = 'active') = 1
     and count(*) filter (where status = 'rejected') = 1
     and count(*) filter (where status = 'pending') = 0
      then 'ok'
    else 'bad'
  end
  from content.gallery_memberships
  where gallery_id = '$GALLERY_ID'::uuid;
" | grep -qx 'ok'

run_psql "$APP_CONTROL" -Atc "
  select case
    when bool_and(owner_hidden_at is null) filter (where created_by = '$CLAIMANT_A'::uuid)
     and bool_and(owner_hidden_at is not null) filter (where created_by = '$CLAIMANT_B'::uuid)
      then 'ok'
    else 'bad'
  end
  from content.exhibitions
  where gallery_id = '$GALLERY_ID'::uuid;
" | grep -qx 'ok'

run_psql "$APP_CONTROL" -Atc "
  select case
    when count(*) filter (
      where action = 'gallery.claim_rejected'
        and metadata ->> 'reason' = 'competing_claim_approved'
    ) = 1
     and (
       select count(*)
       from content.outbox_events
       where aggregate_type = 'gallery'
         and aggregate_id = '$GALLERY_ID'
         and event_type = 'gallery.claim_rejected'
         and payload ->> 'reason' = 'competing_claim_approved'
     ) = 1
      then 'ok'
    else 'bad'
  end
  from content.audit_log
  where entity_type = 'gallery'
    and entity_id = '$GALLERY_ID';
" | grep -qx 'ok'

# Restore a second pending-vs-pending state and race approval against a losing
# claimant's draft creation. The create path must re-authorize after waiting on
# the same gallery row lock, otherwise its FK insert can resume post-rejection.
run_psql "$APP_CONTROL" >/dev/null <<SQL
update content.gallery_memberships
set
  status = 'pending'::content.gallery_membership_status,
  reviewed_at = null,
  reviewed_by = null,
  review_notes = null,
  updated_by = user_id
where gallery_id = '$GALLERY_ID'::uuid;

update content.exhibitions
set owner_hidden_at = null, owner_hidden_by = null, updated_by = created_by
where gallery_id = '$GALLERY_ID'::uuid;
SQL

run_psql "$APP_A" >"$LOG_A" 2>&1 <<SQL &
begin;
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"$PUBLISHER_ID","role":"authenticated"}',
  true
);
select public.admin_approve_gallery_claim(
  '$GALLERY_ID'::uuid,
  '$CLAIMANT_A'::uuid,
  '$REQUEST_C'::uuid
);
select pg_sleep(1.5);
commit;
SQL
PID_A=$!
wait_for_sleep_gate "$APP_A"

run_psql "$APP_B" >"$LOG_B" 2>&1 <<SQL &
begin;
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"$CLAIMANT_B","role":"authenticated"}',
  true
);
select public.owner_create_exhibition_draft('$CREATE_RACE_REQUEST'::uuid);
commit;
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
  echo "Losing claimant created a draft after approval." >&2
  exit 1
fi
grep -q 'gallery_membership_required' "$LOG_B"
if grep -qi 'deadlock detected\|duplicate key value' "$LOG_B"; then
  echo "Approval-versus-create failed through a lock or uniqueness accident." >&2
  exit 1
fi

run_psql "$APP_CONTROL" -Atc "
  select case
    when (
      select status = 'rejected'::content.gallery_membership_status
      from content.gallery_memberships
      where gallery_id = '$GALLERY_ID'::uuid
        and user_id = '$CLAIMANT_B'::uuid
    )
     and count(*) = 1
     and bool_and(owner_hidden_at is not null)
      then 'ok'
    else 'bad'
  end
  from content.exhibitions
  where gallery_id = '$GALLERY_ID'::uuid
    and created_by = '$CLAIMANT_B'::uuid;
" | grep -qx 'ok'

echo "PASS: competing gallery claim approval concurrency"
