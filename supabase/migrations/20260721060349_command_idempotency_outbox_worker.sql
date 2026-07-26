-- Durable command idempotency and lease-safe outbox processing.
--
-- This migration is additive over the CMS foundation and admin command API.
-- It deliberately does not expose the private content schema through PostgREST.
-- Public functions are narrow SECURITY INVOKER wrappers; privileged work stays
-- in fixed-search-path helpers with explicit EXECUTE grants.

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------------------
-- Durable lifecycle-command idempotency
-- ---------------------------------------------------------------------------

create table content.command_requests (
  actor_user_id uuid not null
    references auth.users(id) on delete restrict,
  request_id uuid not null,
  command_name text not null,
  request_fingerprint text not null,
  response jsonb,
  created_at timestamptz not null default now(),
  completed_at timestamptz,
  primary key (actor_user_id, request_id),
  constraint command_requests_name_not_blank check (
    length(btrim(command_name)) between 1 and 128
  ),
  constraint command_requests_fingerprint_format check (
    request_fingerprint ~ '^[0-9a-f]{64}$'
  ),
  constraint command_requests_response_object check (
    response is null or jsonb_typeof(response) = 'object'
  ),
  constraint command_requests_completion_pair check (
    (response is null) = (completed_at is null)
  )
);

comment on table content.command_requests is
  'Completed lifecycle command receipts. A request UUID is unique per authenticated actor and cannot be reused with different canonical parameters.';

create index command_requests_completed_cleanup_idx
  on content.command_requests (completed_at, actor_user_id, request_id)
  where completed_at is not null;

alter table content.command_requests enable row level security;

revoke all on content.command_requests
  from public, anon, authenticated, service_role;
grant select, delete on content.command_requests to service_role;

create or replace function content_private.command_request_fingerprint(
  p_parameters jsonb
)
returns text
language sql
immutable
security invoker
set search_path = ''
as $$
  select encode(
    extensions.digest(
      convert_to(coalesce(p_parameters, 'null'::jsonb)::text, 'UTF8'),
      'sha256'
    ),
    'hex'
  );
$$;

revoke all on function content_private.command_request_fingerprint(jsonb)
  from public, anon, authenticated, service_role;

create or replace function content_private.begin_command_request(
  p_actor_user_id uuid,
  p_request_id uuid,
  p_command_name text,
  p_request_fingerprint text
)
returns table (
  is_replay boolean,
  stored_response jsonb
)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_inserted integer := 0;
  v_request content.command_requests%rowtype;
begin
  if p_actor_user_id is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if p_request_id is null then
    raise exception using errcode = '22023', message = 'request_id_is_required';
  end if;
  if p_command_name is null or length(btrim(p_command_name)) = 0 then
    raise exception using errcode = '22023', message = 'command_name_is_required';
  end if;
  if p_request_fingerprint is null
     or p_request_fingerprint !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '22023', message = 'request_fingerprint_is_invalid';
  end if;

  insert into content.command_requests (
    actor_user_id,
    request_id,
    command_name,
    request_fingerprint
  )
  values (
    p_actor_user_id,
    p_request_id,
    p_command_name,
    p_request_fingerprint
  )
  on conflict (actor_user_id, request_id) do nothing;

  get diagnostics v_inserted = row_count;

  -- The unique-index conflict wait plus this row lock serializes concurrent
  -- retries. The second caller cannot observe a half-completed response.
  select request.*
  into v_request
  from content.command_requests as request
  where request.actor_user_id = p_actor_user_id
    and request.request_id = p_request_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'command_request_not_found';
  end if;

  if v_request.command_name <> p_command_name
     or v_request.request_fingerprint <> p_request_fingerprint then
    raise exception using
      errcode = '22023',
      message = 'idempotency_key_reused_with_different_request';
  end if;

  if v_request.response is not null then
    return query select true, v_request.response;
    return;
  end if;

  -- An incomplete committed row should never be produced by these atomic
  -- functions. Refuse to repeat side effects if manual corruption created one.
  if v_inserted = 0 then
    raise exception using errcode = '55000', message = 'command_request_incomplete';
  end if;

  return query select false, null::jsonb;
end;
$$;

revoke all on function content_private.begin_command_request(uuid, uuid, text, text)
  from public, anon, authenticated, service_role;

create or replace function content_private.complete_command_request(
  p_actor_user_id uuid,
  p_request_id uuid,
  p_command_name text,
  p_request_fingerprint text,
  p_response jsonb
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if p_response is null or jsonb_typeof(p_response) <> 'object' then
    raise exception using errcode = '22023', message = 'command_response_must_be_an_object';
  end if;

  update content.command_requests as request
  set
    response = p_response,
    completed_at = now()
  where request.actor_user_id = p_actor_user_id
    and request.request_id = p_request_id
    and request.command_name = p_command_name
    and request.request_fingerprint = p_request_fingerprint
    and request.response is null;

  if not found then
    raise exception using errcode = '55000', message = 'command_request_cannot_be_completed';
  end if;

  return p_response;
end;
$$;

revoke all on function content_private.complete_command_request(uuid, uuid, text, text, jsonb)
  from public, anon, authenticated, service_role;

-- Existing lifecycle implementations write their audit rows internally. A
-- transaction-local context lets this later migration stamp those rows without
-- rewriting historical migration SQL. The idempotent wrapper clears it again
-- before returning, so unrelated audit writes in the transaction are untouched.
create or replace function content_private.apply_command_request_context()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_request_id_text text := nullif(
    current_setting('app.command_request_id', true),
    ''
  );
  v_actor_id_text text := nullif(
    current_setting('app.command_actor_id', true),
    ''
  );
  v_actor_id uuid;
begin
  if v_request_id_text is null then
    return new;
  end if;

  if v_actor_id_text is null then
    raise exception using errcode = '55000', message = 'command_actor_context_is_missing';
  end if;

  v_actor_id := v_actor_id_text::uuid;
  if new.actor_user_id is distinct from v_actor_id then
    raise exception using errcode = '42501', message = 'command_audit_actor_mismatch';
  end if;

  new.request_id := v_request_id_text::uuid;
  return new;
end;
$$;

revoke all on function content_private.apply_command_request_context()
  from public, anon, authenticated, service_role;

drop trigger if exists audit_log_apply_command_request_context
  on content.audit_log;
create trigger audit_log_apply_command_request_context
  before insert on content.audit_log
  for each row
  execute function content_private.apply_command_request_context();

create or replace function content_private.admin_lifecycle_idempotent_impl(
  p_command_name text,
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor_user_id uuid;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored_response jsonb;
  v_response jsonb;
  v_parameters jsonb;
begin
  v_actor_user_id := content_private.admin_assert_staff(
    'publisher'::content.staff_role
  );

  if p_request_id is null then
    raise exception using errcode = '22023', message = 'request_id_is_required';
  end if;
  if p_command_name not in (
    'admin_publish_exhibition',
    'admin_archive_exhibition',
    'admin_restore_exhibition'
  ) then
    raise exception using errcode = '22023', message = 'unsupported_lifecycle_command';
  end if;

  v_parameters := jsonb_build_object(
    'exhibition_id', p_exhibition_id,
    'expected_version_id', p_expected_version_id,
    'expected_revision', p_expected_revision
  );
  v_fingerprint := content_private.command_request_fingerprint(v_parameters);

  select request.is_replay, request.stored_response
  into v_is_replay, v_stored_response
  from content_private.begin_command_request(
    v_actor_user_id,
    p_request_id,
    p_command_name,
    v_fingerprint
  ) as request;

  if v_is_replay then
    return v_stored_response;
  end if;

  perform set_config('app.command_request_id', p_request_id::text, true);
  perform set_config('app.command_actor_id', v_actor_user_id::text, true);

  case p_command_name
    when 'admin_publish_exhibition' then
      v_response := content_private.admin_publish_exhibition_impl(
        p_exhibition_id,
        p_expected_version_id,
        p_expected_revision
      );
    when 'admin_archive_exhibition' then
      v_response := content_private.admin_archive_exhibition_impl(
        p_exhibition_id,
        p_expected_version_id,
        p_expected_revision
      );
    when 'admin_restore_exhibition' then
      v_response := content_private.admin_restore_exhibition_impl(
        p_exhibition_id,
        p_expected_version_id,
        p_expected_revision
      );
  end case;

  perform set_config('app.command_request_id', '', true);
  perform set_config('app.command_actor_id', '', true);

  return content_private.complete_command_request(
    v_actor_user_id,
    p_request_id,
    p_command_name,
    v_fingerprint,
    v_response
  );
end;
$$;

revoke all on function content_private.admin_lifecycle_idempotent_impl(text, text, uuid, integer, uuid)
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_lifecycle_idempotent_impl(text, text, uuid, integer, uuid)
  to authenticated;

-- Remove every authenticated path to the historical non-idempotent lifecycle
-- overloads. The private implementations remain owner-callable only so the
-- idempotent dispatcher can reuse their already-tested transactions.
revoke all on function content_private.admin_publish_exhibition_impl(text, uuid, integer)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_archive_exhibition_impl(text, uuid, integer)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_restore_exhibition_impl(text, uuid, integer)
  from public, anon, authenticated, service_role;

drop function if exists public.admin_publish_exhibition(text, uuid, integer);
drop function if exists public.admin_archive_exhibition(text, uuid, integer);
drop function if exists public.admin_restore_exhibition(text, uuid, integer);

create or replace function public.admin_publish_exhibition(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_lifecycle_idempotent_impl(
    'admin_publish_exhibition',
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_request_id
  );
$$;

create or replace function public.admin_archive_exhibition(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_lifecycle_idempotent_impl(
    'admin_archive_exhibition',
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_request_id
  );
$$;

create or replace function public.admin_restore_exhibition(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_lifecycle_idempotent_impl(
    'admin_restore_exhibition',
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_request_id
  );
$$;

revoke all on function public.admin_publish_exhibition(text, uuid, integer, uuid)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_archive_exhibition(text, uuid, integer, uuid)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_restore_exhibition(text, uuid, integer, uuid)
  from public, anon, authenticated, service_role;
grant execute on function public.admin_publish_exhibition(text, uuid, integer, uuid)
  to authenticated;
grant execute on function public.admin_archive_exhibition(text, uuid, integer, uuid)
  to authenticated;
grant execute on function public.admin_restore_exhibition(text, uuid, integer, uuid)
  to authenticated;

-- ---------------------------------------------------------------------------
-- Lease-safe outbox
-- ---------------------------------------------------------------------------

alter table content.outbox_events
  add column if not exists lease_token uuid,
  add column if not exists lease_owner text,
  add column if not exists locked_until timestamptz,
  add column if not exists dead_lettered_at timestamptz,
  add column if not exists max_attempts integer not null default 5;

-- No worker existed before this migration. Normalize any manually created
-- processing rows before enforcing the stronger lease-state invariant.
update content.outbox_events
set
  status = 'failed'::content.outbox_status,
  lease_token = null,
  lease_owner = null,
  locked_at = null,
  locked_until = null,
  available_at = now(),
  last_error = coalesce(last_error, 'legacy_processing_row_recovered')
where status = 'processing'::content.outbox_status;

alter table content.outbox_events
  add constraint outbox_events_max_attempts_range check (
    max_attempts between 1 and 20
  ),
  add constraint outbox_events_lease_owner_length check (
    lease_owner is null or length(btrim(lease_owner)) between 1 and 128
  ),
  add constraint outbox_events_lease_state check (
    (
      status = 'processing'::content.outbox_status
      and lease_token is not null
      and lease_owner is not null
      and locked_at is not null
      and locked_until is not null
      and locked_until > locked_at
      and delivered_at is null
      and dead_lettered_at is null
    )
    or (
      status <> 'processing'::content.outbox_status
      and lease_token is null
      and lease_owner is null
      and locked_at is null
      and locked_until is null
    )
  ),
  add constraint outbox_events_dead_letter_state check (
    dead_lettered_at is null
    or (
      status = 'failed'::content.outbox_status
      and delivered_at is null
    )
  );

create unique index outbox_events_active_lease_token_idx
  on content.outbox_events (lease_token)
  where lease_token is not null;

create index outbox_events_available_claim_idx
  on content.outbox_events (available_at, created_at, id)
  where dead_lettered_at is null
    and delivered_at is null
    and status in (
      'pending'::content.outbox_status,
      'failed'::content.outbox_status
    );

create index outbox_events_expired_lease_idx
  on content.outbox_events (locked_until, created_at, id)
  where dead_lettered_at is null
    and delivered_at is null
    and status = 'processing'::content.outbox_status;

create or replace function content_private.reject_dead_lettered_media_publish(
  p_event_id uuid,
  p_event_type text,
  p_payload jsonb,
  p_error text,
  p_attempts integer
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_asset_id uuid;
begin
  if p_event_type <> 'media.publish_requested'
     or jsonb_typeof(p_payload) <> 'object' then
    return false;
  end if;

  update content.media_assets as asset
  set
    status = 'rejected'::content.media_asset_status,
    public_url = null,
    metadata = asset.metadata || jsonb_build_object(
      'rejection_reason', 'publish_delivery_exhausted',
      'rejection_diagnostic', left(coalesce(p_error, 'delivery attempts exhausted'), 1000),
      'rejected_at', clock_timestamp(),
      'editor_instruction', 'Remove this image and upload it again.'
    )
  where asset.id::text = p_payload ->> 'asset_id'
    and asset.status = 'ready'::content.media_asset_status
    and asset.bucket_id = p_payload ->> 'source_bucket_id'
    and asset.object_path = p_payload ->> 'source_object_path'
  returning asset.id into v_asset_id;

  if not found then
    return false;
  end if;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    null,
    'media.publish_dead_lettered',
    'media_asset',
    v_asset_id::text,
    jsonb_build_object(
      'event_id', p_event_id,
      'attempts', p_attempts,
      'diagnostic', left(coalesce(p_error, 'delivery attempts exhausted'), 1000),
      'next_action', 'remove_and_reupload'
    )
  );

  return true;
end;
$$;

revoke all on function content_private.reject_dead_lettered_media_publish(uuid, text, jsonb, text, integer)
  from public, anon, authenticated, service_role;

create or replace function content_private.outbox_claim_events_impl(
  p_lease_owner text,
  p_batch_size integer default 1,
  p_lease_seconds integer default 60
)
returns setof jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_now timestamptz := clock_timestamp();
  v_exhausted_event content.outbox_events%rowtype;
begin
  if p_lease_owner is null
     or length(btrim(p_lease_owner)) not between 1 and 128 then
    raise exception using errcode = '22023', message = 'lease_owner_is_invalid';
  end if;
  if p_batch_size is distinct from 1 then
    raise exception using errcode = '22023', message = 'outbox_claim_batch_size_must_equal_one';
  end if;
  if p_lease_seconds is null or p_lease_seconds not between 10 and 900 then
    raise exception using errcode = '22023', message = 'lease_seconds_must_be_between_10_and_900';
  end if;

  -- A worker that died on its final attempt cannot leave a permanently stuck
  -- processing row. Terminalize it before selecting fresh work.
  for v_exhausted_event in
    with exhausted as (
      select event.id
      from content.outbox_events as event
      where event.dead_lettered_at is null
        and event.delivered_at is null
        and event.attempts >= event.max_attempts
        and (
          (
            event.status in (
              'pending'::content.outbox_status,
              'failed'::content.outbox_status
            )
            and event.available_at <= v_now
          )
          or (
            event.status = 'processing'::content.outbox_status
            and event.locked_until <= v_now
          )
        )
      order by event.updated_at, event.id
      limit 1
      for update skip locked
    )
    update content.outbox_events as event
    set
      status = 'failed'::content.outbox_status,
      dead_lettered_at = v_now,
      lease_token = null,
      lease_owner = null,
      locked_at = null,
      locked_until = null,
      last_error = coalesce(event.last_error, 'maximum_attempts_exhausted')
    from exhausted
    where event.id = exhausted.id
    returning event.*
  loop
    perform content_private.reject_dead_lettered_media_publish(
      v_exhausted_event.id,
      v_exhausted_event.event_type,
      v_exhausted_event.payload,
      v_exhausted_event.last_error,
      v_exhausted_event.attempts
    );
  end loop;

  return query
  with candidates as (
    select event.id
    from content.outbox_events as event
    where event.dead_lettered_at is null
      and event.delivered_at is null
      and event.attempts < event.max_attempts
      and (
        (
          event.status in (
            'pending'::content.outbox_status,
            'failed'::content.outbox_status
          )
          and event.available_at <= v_now
        )
        or (
          event.status = 'processing'::content.outbox_status
          and event.locked_until <= v_now
        )
      )
    order by
      case
        when event.status = 'processing'::content.outbox_status then event.locked_until
        else event.available_at
      end,
      event.created_at,
      event.id
    limit p_batch_size
    for update skip locked
  ),
  claimed as (
    update content.outbox_events as event
    set
      status = 'processing'::content.outbox_status,
      attempts = event.attempts + 1,
      lease_token = gen_random_uuid(),
      lease_owner = btrim(p_lease_owner),
      locked_at = v_now,
      locked_until = v_now + make_interval(secs => p_lease_seconds)
    from candidates
    where event.id = candidates.id
    returning event.*
  )
  select jsonb_build_object(
    'id', claimed.id,
    'aggregate_type', claimed.aggregate_type,
    'aggregate_id', claimed.aggregate_id,
    'event_type', claimed.event_type,
    'payload', claimed.payload,
    'deduplication_key', claimed.deduplication_key,
    'attempts', claimed.attempts,
    'max_attempts', claimed.max_attempts,
    'lease_token', claimed.lease_token,
    'lease_owner', claimed.lease_owner,
    'locked_until', claimed.locked_until,
    'created_at', claimed.created_at
  )
  from claimed
  order by claimed.locked_at, claimed.created_at, claimed.id;
end;
$$;

create or replace function content_private.outbox_complete_event_impl(
  p_event_id uuid,
  p_lease_token uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_event content.outbox_events%rowtype;
begin
  if p_event_id is null or p_lease_token is null then
    raise exception using errcode = '22023', message = 'event_id_and_lease_token_are_required';
  end if;

  update content.outbox_events as event
  set
    status = 'delivered'::content.outbox_status,
    delivered_at = clock_timestamp(),
    lease_token = null,
    lease_owner = null,
    locked_at = null,
    locked_until = null,
    last_error = null
  where event.id = p_event_id
    and event.status = 'processing'::content.outbox_status
    and event.lease_token = p_lease_token
    and event.locked_until > clock_timestamp()
    and event.dead_lettered_at is null
  returning event.* into v_event;

  if not found then
    raise exception using errcode = 'P0002', message = 'outbox_lease_not_found_or_expired';
  end if;

  return jsonb_build_object(
    'id', v_event.id,
    'status', v_event.status,
    'attempts', v_event.attempts,
    'delivered_at', v_event.delivered_at
  );
end;
$$;

create or replace function content_private.outbox_fail_event_impl(
  p_event_id uuid,
  p_lease_token uuid,
  p_error text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_event content.outbox_events%rowtype;
  v_now timestamptz := clock_timestamp();
  v_backoff_seconds integer;
begin
  if p_event_id is null or p_lease_token is null then
    raise exception using errcode = '22023', message = 'event_id_and_lease_token_are_required';
  end if;
  if p_error is null or length(btrim(p_error)) = 0 then
    raise exception using errcode = '22023', message = 'outbox_error_is_required';
  end if;

  select event.*
  into v_event
  from content.outbox_events as event
  where event.id = p_event_id
  for update;

  if not found
     or v_event.status <> 'processing'::content.outbox_status
     or v_event.lease_token is distinct from p_lease_token
     or v_event.locked_until <= v_now
     or v_event.dead_lettered_at is not null then
    raise exception using errcode = 'P0002', message = 'outbox_lease_not_found_or_expired';
  end if;

  if v_event.attempts >= v_event.max_attempts then
    update content.outbox_events as event
    set
      status = 'failed'::content.outbox_status,
      dead_lettered_at = v_now,
      lease_token = null,
      lease_owner = null,
      locked_at = null,
      locked_until = null,
      last_error = left(btrim(p_error), 4000)
    where event.id = p_event_id
    returning event.* into v_event;

    perform content_private.reject_dead_lettered_media_publish(
      v_event.id,
      v_event.event_type,
      v_event.payload,
      v_event.last_error,
      v_event.attempts
    );
  else
    v_backoff_seconds := least(
      3600,
      (5 * power(2::numeric, greatest(v_event.attempts - 1, 0)))::integer
    );

    update content.outbox_events as event
    set
      status = 'failed'::content.outbox_status,
      available_at = v_now + make_interval(secs => v_backoff_seconds),
      lease_token = null,
      lease_owner = null,
      locked_at = null,
      locked_until = null,
      last_error = left(btrim(p_error), 4000)
    where event.id = p_event_id
    returning event.* into v_event;
  end if;

  return jsonb_build_object(
    'id', v_event.id,
    'status', v_event.status,
    'attempts', v_event.attempts,
    'max_attempts', v_event.max_attempts,
    'available_at', v_event.available_at,
    'dead_lettered_at', v_event.dead_lettered_at
  );
end;
$$;

revoke all on function content_private.outbox_claim_events_impl(text, integer, integer)
  from public, anon, authenticated, service_role;
revoke all on function content_private.outbox_complete_event_impl(uuid, uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.outbox_fail_event_impl(uuid, uuid, text)
  from public, anon, authenticated, service_role;
grant execute on function content_private.outbox_claim_events_impl(text, integer, integer)
  to service_role;
grant execute on function content_private.outbox_complete_event_impl(uuid, uuid)
  to service_role;
grant execute on function content_private.outbox_fail_event_impl(uuid, uuid, text)
  to service_role;

create or replace function public.outbox_claim_events(
  p_lease_owner text,
  p_batch_size integer default 1,
  p_lease_seconds integer default 60
)
returns setof jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select *
  from content_private.outbox_claim_events_impl(
    p_lease_owner,
    p_batch_size,
    p_lease_seconds
  );
$$;

create or replace function public.outbox_complete_event(
  p_event_id uuid,
  p_lease_token uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.outbox_complete_event_impl(
    p_event_id,
    p_lease_token
  );
$$;

create or replace function public.outbox_fail_event(
  p_event_id uuid,
  p_lease_token uuid,
  p_error text
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.outbox_fail_event_impl(
    p_event_id,
    p_lease_token,
    p_error
  );
$$;

revoke all on function public.outbox_claim_events(text, integer, integer)
  from public, anon, authenticated, service_role;
revoke all on function public.outbox_complete_event(uuid, uuid)
  from public, anon, authenticated, service_role;
revoke all on function public.outbox_fail_event(uuid, uuid, text)
  from public, anon, authenticated, service_role;
grant execute on function public.outbox_claim_events(text, integer, integer)
  to service_role;
grant execute on function public.outbox_complete_event(uuid, uuid)
  to service_role;
grant execute on function public.outbox_fail_event(uuid, uuid, text)
  to service_role;

-- ---------------------------------------------------------------------------
-- Service-only media delivery and cleanup finalization
-- ---------------------------------------------------------------------------

-- Published originals live in a dedicated public, immutable-delivery bucket.
-- Browser roles receive no object-write policy; only the service worker writes
-- or removes objects through the Storage API.
insert into storage.buckets (
  id,
  name,
  public,
  file_size_limit,
  allowed_mime_types
)
values (
  'exhibition-images',
  'exhibition-images',
  true,
  10485760,
  array['image/jpeg', 'image/png', 'image/webp']::text[]
)
on conflict (id) do update
set
  name = excluded.name,
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

alter table content.media_assets
  add column if not exists delivery_bucket_id text,
  add column if not exists delivery_object_path text,
  add column if not exists purged_at timestamptz,
  add column if not exists purge_started_at timestamptz,
  add column if not exists purge_token uuid;

create unique index if not exists media_assets_active_purge_token_idx
  on content.media_assets (purge_token)
  where purge_token is not null;

create index if not exists media_assets_stale_upload_sweep_idx
  on content.media_assets (updated_at, id)
  where status in (
    'pending_upload'::content.media_asset_status,
    'ready'::content.media_asset_status
  )
    and purged_at is null;

create or replace function content_private.outbox_mark_media_published_impl(
  p_asset_id uuid,
  p_source_bucket_id text,
  p_source_object_path text,
  p_delivery_bucket_id text,
  p_delivery_object_path text,
  p_public_url text,
  p_detected_mime_type text,
  p_detected_byte_size bigint,
  p_detected_width integer,
  p_detected_height integer,
  p_detected_checksum_sha256 text,
  p_delivery_checksum_sha256 text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_asset content.media_assets%rowtype;
  v_expected_extension text;
  v_expected_delivery_path text;
begin
  if p_asset_id is null then
    raise exception using errcode = '22023', message = 'asset_id_is_required';
  end if;

  select asset.*
  into v_asset
  from content.media_assets as asset
  where asset.id = p_asset_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'media_asset_not_found';
  end if;

  if v_asset.bucket_id <> p_source_bucket_id
     or v_asset.object_path <> p_source_object_path then
    raise exception using errcode = '22023', message = 'media_source_path_mismatch';
  end if;

  if p_delivery_bucket_id <> 'exhibition-images' then
    raise exception using errcode = '22023', message = 'media_delivery_bucket_is_invalid';
  end if;

  v_expected_extension := case p_detected_mime_type
    when 'image/jpeg' then 'jpg'
    when 'image/png' then 'png'
    when 'image/webp' then 'webp'
    else null
  end;
  if v_expected_extension is null then
    raise exception using errcode = '22023', message = 'detected_media_type_is_invalid';
  end if;

  v_expected_delivery_path := format(
    'cms/%s/original.%s',
    p_asset_id,
    v_expected_extension
  );
  if p_delivery_object_path <> v_expected_delivery_path then
    raise exception using errcode = '22023', message = 'media_delivery_path_is_invalid';
  end if;

  if p_detected_byte_size is null
     or p_detected_byte_size < 1
     or p_detected_byte_size > 10485760
     or p_detected_width is null
     or p_detected_width < 1
     or p_detected_height is null
     or p_detected_height < 1 then
    raise exception using errcode = '22023', message = 'detected_media_dimensions_or_size_are_invalid';
  end if;
  if p_detected_checksum_sha256 is null
     or p_detected_checksum_sha256 !~ '^[0-9a-f]{64}$'
     or p_delivery_checksum_sha256 is distinct from p_detected_checksum_sha256 then
    raise exception using errcode = '22023', message = 'media_delivery_checksum_mismatch';
  end if;
  if p_public_url is null
     or length(p_public_url) > 2048
     or p_public_url !~ '^https?://' then
    raise exception using errcode = '22023', message = 'media_public_url_is_invalid';
  end if;

  -- A successful retry is allowed only when every immutable source/delivery
  -- attribute is identical to the already-published record.
  if v_asset.status = 'published'::content.media_asset_status then
    if v_asset.delivery_bucket_id = p_delivery_bucket_id
       and v_asset.delivery_object_path = p_delivery_object_path
       and v_asset.public_url = p_public_url
       and v_asset.mime_type = p_detected_mime_type
       and v_asset.byte_size = p_detected_byte_size
       and v_asset.width = p_detected_width
       and v_asset.height = p_detected_height
       and v_asset.checksum_sha256 = p_detected_checksum_sha256 then
      return jsonb_build_object(
        'asset_id', v_asset.id,
        'status', v_asset.status,
        'public_url', v_asset.public_url,
        'published_at', v_asset.published_at,
        'replayed', true
      );
    end if;
    raise exception using errcode = '22023', message = 'published_media_is_immutable';
  end if;

  if v_asset.status <> 'ready'::content.media_asset_status
     or v_asset.purged_at is not null then
    raise exception using errcode = '22023', message = 'media_asset_is_not_ready_for_publication';
  end if;
  if not exists (
    select 1
    from content.exhibition_version_media as attachment
    where attachment.media_id = p_asset_id
  ) then
    raise exception using errcode = '22023', message = 'media_asset_is_unreferenced';
  end if;

  if (v_asset.mime_type is not null and v_asset.mime_type <> p_detected_mime_type)
     or (v_asset.byte_size is not null and v_asset.byte_size <> p_detected_byte_size)
     or (v_asset.width is not null and v_asset.width <> p_detected_width)
     or (v_asset.height is not null and v_asset.height <> p_detected_height)
     or (
       v_asset.checksum_sha256 is not null
       and v_asset.checksum_sha256 <> p_detected_checksum_sha256
     ) then
    raise exception using errcode = '23514', message = 'media_reservation_does_not_match_detected_bytes';
  end if;

  update content.media_assets as asset
  set
    status = 'published'::content.media_asset_status,
    delivery_bucket_id = p_delivery_bucket_id,
    delivery_object_path = p_delivery_object_path,
    public_url = p_public_url,
    mime_type = p_detected_mime_type,
    byte_size = p_detected_byte_size,
    width = p_detected_width,
    height = p_detected_height,
    checksum_sha256 = p_detected_checksum_sha256,
    published_at = coalesce(asset.published_at, clock_timestamp())
  where asset.id = p_asset_id
  returning asset.* into v_asset;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    null,
    'media.published',
    'media_asset',
    p_asset_id::text,
    jsonb_build_object(
      'source_bucket_id', p_source_bucket_id,
      'source_object_path', p_source_object_path,
      'delivery_bucket_id', p_delivery_bucket_id,
      'delivery_object_path', p_delivery_object_path,
      'checksum_sha256', p_detected_checksum_sha256
    )
  );

  return jsonb_build_object(
    'asset_id', v_asset.id,
    'status', v_asset.status,
    'public_url', v_asset.public_url,
    'published_at', v_asset.published_at,
    'replayed', false
  );
end;
$$;

create or replace function content_private.outbox_reject_media_impl(
  p_asset_id uuid,
  p_source_bucket_id text,
  p_source_object_path text,
  p_reason_code text,
  p_diagnostic text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_asset content.media_assets%rowtype;
begin
  select asset.*
  into v_asset
  from content.media_assets as asset
  where asset.id = p_asset_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'media_asset_not_found';
  end if;
  if v_asset.bucket_id <> p_source_bucket_id
     or v_asset.object_path <> p_source_object_path then
    raise exception using errcode = '22023', message = 'media_source_path_mismatch';
  end if;
  if p_reason_code is null
     or length(btrim(p_reason_code)) not between 1 and 128 then
    raise exception using errcode = '22023', message = 'media_rejection_reason_is_invalid';
  end if;

  if v_asset.status = 'rejected'::content.media_asset_status then
    return jsonb_build_object(
      'asset_id', v_asset.id,
      'status', v_asset.status,
      'replayed', true
    );
  end if;
  if v_asset.status <> 'ready'::content.media_asset_status then
    raise exception using errcode = '22023', message = 'media_asset_cannot_be_rejected_from_current_status';
  end if;

  update content.media_assets as asset
  set
    status = 'rejected'::content.media_asset_status,
    public_url = null,
    delivery_bucket_id = null,
    delivery_object_path = null,
    metadata = asset.metadata || jsonb_build_object(
      'rejection_reason', btrim(p_reason_code),
      'rejection_diagnostic', left(coalesce(p_diagnostic, ''), 1000),
      'rejected_at', clock_timestamp()
    )
  where asset.id = p_asset_id
  returning asset.* into v_asset;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    null,
    'media.rejected',
    'media_asset',
    p_asset_id::text,
    jsonb_build_object(
      'reason_code', btrim(p_reason_code),
      'diagnostic', left(coalesce(p_diagnostic, ''), 1000)
    )
  );

  return jsonb_build_object(
    'asset_id', v_asset.id,
    'status', v_asset.status,
    'replayed', false
  );
end;
$$;

create or replace function content_private.outbox_prepare_media_cleanup_impl(
  p_asset_id uuid,
  p_source_bucket_id text,
  p_source_object_path text,
  p_delivery_bucket_id text,
  p_delivery_object_path text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_asset content.media_assets%rowtype;
begin
  select asset.*
  into v_asset
  from content.media_assets as asset
  where asset.id = p_asset_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'media_asset_not_found';
  end if;
  if v_asset.bucket_id <> p_source_bucket_id
     or v_asset.object_path <> p_source_object_path
     or v_asset.delivery_bucket_id is distinct from p_delivery_bucket_id
     or v_asset.delivery_object_path is distinct from p_delivery_object_path then
    raise exception using errcode = '22023', message = 'media_cleanup_paths_do_not_match';
  end if;

  if v_asset.purged_at is not null then
    return jsonb_build_object(
      'asset_id', v_asset.id,
      'already_purged', true,
      'purged_at', v_asset.purged_at,
      'source_bucket_id', v_asset.bucket_id,
      'source_object_path', v_asset.object_path,
      'delivery_bucket_id', v_asset.delivery_bucket_id,
      'delivery_object_path', v_asset.delivery_object_path
    );
  end if;
  if v_asset.status <> 'orphaned'::content.media_asset_status then
    raise exception using errcode = '22023', message = 'media_asset_is_not_orphaned';
  end if;
  if exists (
    select 1 from content.exhibition_version_media where media_id = p_asset_id
  ) or exists (
    select 1 from content.submission_media where media_id = p_asset_id
  ) then
    raise exception using errcode = '55000', message = 'media_asset_is_still_referenced';
  end if;

  if v_asset.purge_token is null then
    update content.media_assets as asset
    set
      purge_token = gen_random_uuid(),
      purge_started_at = clock_timestamp()
    where asset.id = p_asset_id
    returning asset.* into v_asset;
  end if;

  return jsonb_build_object(
    'asset_id', v_asset.id,
    'already_purged', false,
    'purge_token', v_asset.purge_token,
    'source_bucket_id', v_asset.bucket_id,
    'source_object_path', v_asset.object_path,
    'delivery_bucket_id', v_asset.delivery_bucket_id,
    'delivery_object_path', v_asset.delivery_object_path
  );
end;
$$;

create or replace function content_private.outbox_finalize_media_cleanup_impl(
  p_asset_id uuid,
  p_purge_token uuid,
  p_source_bucket_id text,
  p_source_object_path text,
  p_delivery_bucket_id text,
  p_delivery_object_path text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_asset content.media_assets%rowtype;
begin
  select asset.*
  into v_asset
  from content.media_assets as asset
  where asset.id = p_asset_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'media_asset_not_found';
  end if;
  if v_asset.bucket_id <> p_source_bucket_id
     or v_asset.object_path <> p_source_object_path
     or v_asset.delivery_bucket_id is distinct from p_delivery_bucket_id
     or v_asset.delivery_object_path is distinct from p_delivery_object_path then
    raise exception using errcode = '22023', message = 'media_cleanup_paths_do_not_match';
  end if;

  if v_asset.purged_at is not null then
    return jsonb_build_object(
      'asset_id', v_asset.id,
      'status', v_asset.status,
      'purged_at', v_asset.purged_at,
      'replayed', true
    );
  end if;
  if p_purge_token is null
     or v_asset.purge_token is distinct from p_purge_token then
    raise exception using errcode = '42501', message = 'media_purge_token_is_stale';
  end if;
  if v_asset.status <> 'orphaned'::content.media_asset_status then
    raise exception using errcode = '22023', message = 'media_asset_is_not_orphaned';
  end if;
  if exists (
    select 1 from content.exhibition_version_media where media_id = p_asset_id
  ) or exists (
    select 1 from content.submission_media where media_id = p_asset_id
  ) then
    raise exception using errcode = '55000', message = 'media_asset_is_still_referenced';
  end if;

  update content.media_assets as asset
  set
    purged_at = clock_timestamp(),
    public_url = null,
    purge_token = null
  where asset.id = p_asset_id
  returning asset.* into v_asset;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    null,
    'media.purged',
    'media_asset',
    p_asset_id::text,
    jsonb_build_object(
      'source_bucket_id', p_source_bucket_id,
      'source_object_path', p_source_object_path,
      'delivery_bucket_id', p_delivery_bucket_id,
      'delivery_object_path', p_delivery_object_path
    )
  );

  return jsonb_build_object(
    'asset_id', v_asset.id,
    'status', v_asset.status,
    'purged_at', v_asset.purged_at,
    'replayed', false
  );
end;
$$;

create or replace function content_private.outbox_sweep_stale_media_impl(
  p_cutoff timestamptz default (now() - interval '24 hours'),
  p_batch_size integer default 100
)
returns setof jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if p_cutoff is null or p_cutoff > clock_timestamp() then
    raise exception using errcode = '22023', message = 'stale_media_cutoff_is_invalid';
  end if;
  if p_batch_size is null or p_batch_size not between 1 and 100 then
    raise exception using errcode = '22023', message = 'batch_size_must_be_between_1_and_100';
  end if;

  return query
  with candidates as (
    select asset.id, asset.status as prior_status
    from content.media_assets as asset
    where asset.status in (
        'pending_upload'::content.media_asset_status,
        'ready'::content.media_asset_status
      )
      and asset.purged_at is null
      and asset.updated_at < p_cutoff
      and not exists (
        select 1
        from content.exhibition_version_media as attachment
        where attachment.media_id = asset.id
      )
      and not exists (
        select 1
        from content.submission_media as attachment
        where attachment.media_id = asset.id
      )
    order by asset.updated_at, asset.id
    limit p_batch_size
    for update skip locked
  ),
  orphaned as (
    update content.media_assets as asset
    set status = 'orphaned'::content.media_asset_status
    from candidates
    where asset.id = candidates.id
    returning asset.*, candidates.prior_status
  ),
  audit_rows as (
    insert into content.audit_log (
      actor_user_id,
      action,
      entity_type,
      entity_id,
      metadata
    )
    select
      null,
      'media.stale_upload_orphaned',
      'media_asset',
      orphaned.id::text,
      jsonb_build_object(
        'prior_status', orphaned.prior_status,
        'cutoff', p_cutoff
      )
    from orphaned
    returning entity_id
  ),
  enqueued as (
    insert into content.outbox_events (
      aggregate_type,
      aggregate_id,
      event_type,
      payload,
      deduplication_key
    )
    select
      'media_asset',
      orphaned.id::text,
      'media.cleanup_requested',
      jsonb_build_object(
        'asset_id', orphaned.id,
        'source_bucket_id', orphaned.bucket_id,
        'source_object_path', orphaned.object_path,
        'delivery_bucket_id', orphaned.delivery_bucket_id,
        'delivery_object_path', orphaned.delivery_object_path
      ),
      format('media:%s:cleanup_requested', orphaned.id)
    from orphaned
    on conflict (deduplication_key) do nothing
    returning aggregate_id
  )
  select jsonb_build_object(
    'asset_id', orphaned.id,
    'prior_status', orphaned.prior_status,
    'status', orphaned.status,
    'cleanup_enqueued', exists (
      select 1 from enqueued where enqueued.aggregate_id = orphaned.id::text
    )
  )
  from orphaned;
end;
$$;

revoke all on function content_private.outbox_mark_media_published_impl(uuid, text, text, text, text, text, text, bigint, integer, integer, text, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.outbox_reject_media_impl(uuid, text, text, text, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.outbox_prepare_media_cleanup_impl(uuid, text, text, text, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.outbox_finalize_media_cleanup_impl(uuid, uuid, text, text, text, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.outbox_sweep_stale_media_impl(timestamptz, integer)
  from public, anon, authenticated, service_role;

grant execute on function content_private.outbox_mark_media_published_impl(uuid, text, text, text, text, text, text, bigint, integer, integer, text, text)
  to service_role;
grant execute on function content_private.outbox_reject_media_impl(uuid, text, text, text, text)
  to service_role;
grant execute on function content_private.outbox_prepare_media_cleanup_impl(uuid, text, text, text, text)
  to service_role;
grant execute on function content_private.outbox_finalize_media_cleanup_impl(uuid, uuid, text, text, text, text)
  to service_role;
grant execute on function content_private.outbox_sweep_stale_media_impl(timestamptz, integer)
  to service_role;

create or replace function public.outbox_mark_media_published(
  p_asset_id uuid,
  p_source_bucket_id text,
  p_source_object_path text,
  p_delivery_bucket_id text,
  p_delivery_object_path text,
  p_public_url text,
  p_detected_mime_type text,
  p_detected_byte_size bigint,
  p_detected_width integer,
  p_detected_height integer,
  p_detected_checksum_sha256 text,
  p_delivery_checksum_sha256 text
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.outbox_mark_media_published_impl(
    p_asset_id,
    p_source_bucket_id,
    p_source_object_path,
    p_delivery_bucket_id,
    p_delivery_object_path,
    p_public_url,
    p_detected_mime_type,
    p_detected_byte_size,
    p_detected_width,
    p_detected_height,
    p_detected_checksum_sha256,
    p_delivery_checksum_sha256
  );
$$;

create or replace function public.outbox_reject_media(
  p_asset_id uuid,
  p_source_bucket_id text,
  p_source_object_path text,
  p_reason_code text,
  p_diagnostic text
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.outbox_reject_media_impl(
    p_asset_id,
    p_source_bucket_id,
    p_source_object_path,
    p_reason_code,
    p_diagnostic
  );
$$;

create or replace function public.outbox_prepare_media_cleanup(
  p_asset_id uuid,
  p_source_bucket_id text,
  p_source_object_path text,
  p_delivery_bucket_id text,
  p_delivery_object_path text
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.outbox_prepare_media_cleanup_impl(
    p_asset_id,
    p_source_bucket_id,
    p_source_object_path,
    p_delivery_bucket_id,
    p_delivery_object_path
  );
$$;

create or replace function public.outbox_finalize_media_cleanup(
  p_asset_id uuid,
  p_purge_token uuid,
  p_source_bucket_id text,
  p_source_object_path text,
  p_delivery_bucket_id text,
  p_delivery_object_path text
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.outbox_finalize_media_cleanup_impl(
    p_asset_id,
    p_purge_token,
    p_source_bucket_id,
    p_source_object_path,
    p_delivery_bucket_id,
    p_delivery_object_path
  );
$$;

create or replace function public.outbox_sweep_stale_media(
  p_cutoff timestamptz default (now() - interval '24 hours'),
  p_batch_size integer default 100
)
returns setof jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select *
  from content_private.outbox_sweep_stale_media_impl(
    p_cutoff,
    p_batch_size
  );
$$;

revoke all on function public.outbox_mark_media_published(uuid, text, text, text, text, text, text, bigint, integer, integer, text, text)
  from public, anon, authenticated, service_role;
revoke all on function public.outbox_reject_media(uuid, text, text, text, text)
  from public, anon, authenticated, service_role;
revoke all on function public.outbox_prepare_media_cleanup(uuid, text, text, text, text)
  from public, anon, authenticated, service_role;
revoke all on function public.outbox_finalize_media_cleanup(uuid, uuid, text, text, text, text)
  from public, anon, authenticated, service_role;
revoke all on function public.outbox_sweep_stale_media(timestamptz, integer)
  from public, anon, authenticated, service_role;

grant execute on function public.outbox_mark_media_published(uuid, text, text, text, text, text, text, bigint, integer, integer, text, text)
  to service_role;
grant execute on function public.outbox_reject_media(uuid, text, text, text, text)
  to service_role;
grant execute on function public.outbox_prepare_media_cleanup(uuid, text, text, text, text)
  to service_role;
grant execute on function public.outbox_finalize_media_cleanup(uuid, uuid, text, text, text, text)
  to service_role;
grant execute on function public.outbox_sweep_stale_media(timestamptz, integer)
  to service_role;
