-- Authenticated consumer-account deletion with protected operator identities and
-- durable avatar cleanup. The Edge Function deletes the Auth identity; this
-- migration owns the transactional authorization and retryable cleanup record.

alter table content.command_requests
  drop constraint command_requests_actor_user_id_fkey,
  add constraint command_requests_actor_user_id_fkey
    foreign key (actor_user_id) references auth.users(id) on delete cascade;

create table content_private.account_deletion_rate_limits (
  user_id uuid primary key references auth.users(id) on delete cascade,
  window_started_at timestamptz not null,
  attempt_count integer not null,
  updated_at timestamptz not null default now(),
  constraint account_deletion_rate_attempts_positive check (attempt_count > 0)
);

alter table content_private.account_deletion_rate_limits enable row level security;

revoke all on table content_private.account_deletion_rate_limits
  from public, anon, authenticated, service_role;

create or replace function content_private.account_deletion_is_protected(
  p_user_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select
    exists (
      select 1
      from content.staff_members as staff
      where staff.user_id = p_user_id
        and staff.active
    )
    or exists (
      select 1
      from content.gallery_memberships as membership
      where membership.user_id = p_user_id
        and membership.status in (
          'pending'::content.gallery_membership_status,
          'active'::content.gallery_membership_status,
          'suspended'::content.gallery_membership_status
        )
    );
$$;

revoke all on function content_private.account_deletion_is_protected(uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.protect_operator_account_deletion()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if content_private.account_deletion_is_protected(old.id) then
    raise exception using
      errcode = 'P0001',
      message = 'account_deletion_requires_support';
  end if;
  return old;
end;
$$;

revoke all on function content_private.protect_operator_account_deletion()
  from public, anon, authenticated, service_role;

drop trigger if exists protect_operator_account_deletion on auth.users;
create trigger protect_operator_account_deletion
  before delete on auth.users
  for each row execute function content_private.protect_operator_account_deletion();

create or replace function content_private.account_deletion_prepare_impl()
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_last_sign_in_at timestamptz;
  v_window_started_at timestamptz;
  v_attempt_count integer;
  v_event_id uuid;
  v_now timestamptz := clock_timestamp();
begin
  if v_user_id is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;

  insert into content_private.account_deletion_rate_limits (
    user_id,
    window_started_at,
    attempt_count,
    updated_at
  )
  values (v_user_id, v_now, 1, v_now)
  on conflict (user_id) do update
  set
    window_started_at = case
      when content_private.account_deletion_rate_limits.window_started_at
        <= v_now - interval '15 minutes' then v_now
      else content_private.account_deletion_rate_limits.window_started_at
    end,
    attempt_count = case
      when content_private.account_deletion_rate_limits.window_started_at
        <= v_now - interval '15 minutes' then 1
      else content_private.account_deletion_rate_limits.attempt_count + 1
    end,
    updated_at = v_now
  returning window_started_at, attempt_count
    into v_window_started_at, v_attempt_count;

  if v_attempt_count > 3 then
    return jsonb_build_object(
      'allowed', false,
      'code', 'account_deletion_rate_limited',
      'retry_after_seconds', greatest(
        1,
        ceil(extract(epoch from (
          v_window_started_at + interval '15 minutes' - v_now
        )))::integer
      )
    );
  end if;

  select user_record.last_sign_in_at
  into v_last_sign_in_at
  from auth.users as user_record
  where user_record.id = v_user_id;

  if v_last_sign_in_at is null
     or v_last_sign_in_at < v_now - interval '15 minutes' then
    return jsonb_build_object(
      'allowed', false,
      'code', 'account_deletion_reauthentication_required'
    );
  end if;

  if content_private.account_deletion_is_protected(v_user_id) then
    return jsonb_build_object(
      'allowed', false,
      'code', 'account_deletion_requires_support'
    );
  end if;

  insert into content.outbox_events (
    aggregate_type,
    aggregate_id,
    event_type,
    payload,
    deduplication_key,
    max_attempts
  )
  values (
    'account',
    v_user_id::text,
    'account.avatar_cleanup_requested',
    jsonb_build_object('bucket_id', 'avatars'),
    'account-avatar-cleanup:' || v_user_id::text,
    20
  )
  on conflict (deduplication_key) do nothing
  returning id into v_event_id;

  if v_event_id is null then
    select event.id into v_event_id
    from content.outbox_events as event
    where event.deduplication_key =
      'account-avatar-cleanup:' || v_user_id::text;
  end if;

  if v_event_id is null then
    raise exception using
      errcode = 'P0001',
      message = 'account_deletion_cleanup_schedule_failed';
  end if;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    request_id,
    metadata
  )
  values (
    v_user_id,
    'account.deletion_requested',
    'account_deletion',
    v_event_id::text,
    v_event_id,
    jsonb_build_object('cleanup_scheduled', true)
  );

  return jsonb_build_object(
    'allowed', true,
    'request_id', v_event_id
  );
end;
$$;

revoke all on function content_private.account_deletion_prepare_impl()
  from public, anon, authenticated, service_role;
grant execute on function content_private.account_deletion_prepare_impl()
  to authenticated;

create or replace function public.account_deletion_prepare()
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.account_deletion_prepare_impl();
$$;

revoke all on function public.account_deletion_prepare()
  from public, anon, authenticated, service_role;
grant execute on function public.account_deletion_prepare()
  to authenticated;

create or replace function content_private.account_deletion_cancel_impl(
  p_request_id uuid
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  delete from content.outbox_events as event
  where event.id = p_request_id
    and event.event_type = 'account.avatar_cleanup_requested'
    and event.delivered_at is null;
  return found;
end;
$$;

revoke all on function content_private.account_deletion_cancel_impl(uuid)
  from public, anon, authenticated, service_role;
grant execute on function content_private.account_deletion_cancel_impl(uuid)
  to service_role;

create or replace function public.account_deletion_cancel(
  p_request_id uuid
)
returns boolean
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.account_deletion_cancel_impl(p_request_id);
$$;

revoke all on function public.account_deletion_cancel(uuid)
  from public, anon, authenticated, service_role;
grant execute on function public.account_deletion_cancel(uuid)
  to service_role;

create or replace function content_private.account_deletion_finalize_cleanup_impl(
  p_request_id uuid,
  p_lease_token uuid
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  update content.outbox_events as event
  set
    aggregate_id = event.id::text,
    payload = jsonb_build_object('bucket_id', 'avatars', 'identity_scrubbed', true),
    deduplication_key = 'account-avatar-cleanup-completed:' || event.id::text,
    updated_at = clock_timestamp()
  where event.id = p_request_id
    and event.event_type = 'account.avatar_cleanup_requested'
    and event.status = 'processing'::content.outbox_status
    and event.lease_token = p_lease_token
    and event.locked_until > clock_timestamp()
    and event.dead_lettered_at is null;
  return found;
end;
$$;

revoke all on function content_private.account_deletion_finalize_cleanup_impl(uuid, uuid)
  from public, anon, authenticated, service_role;
grant execute on function content_private.account_deletion_finalize_cleanup_impl(uuid, uuid)
  to service_role;

create or replace function public.account_deletion_finalize_cleanup(
  p_request_id uuid,
  p_lease_token uuid
)
returns boolean
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.account_deletion_finalize_cleanup_impl(
    p_request_id,
    p_lease_token
  );
$$;

revoke all on function public.account_deletion_finalize_cleanup(uuid, uuid)
  from public, anon, authenticated, service_role;
grant execute on function public.account_deletion_finalize_cleanup(uuid, uuid)
  to service_role;

-- Keep the existing RPC name for compatibility, but extend its internal-work
-- filter so installations without a downstream delivery URL still process
-- account cleanup alongside media work.
drop index if exists content.outbox_events_media_available_claim_idx;
drop index if exists content.outbox_events_media_expired_lease_idx;

create index outbox_events_internal_available_claim_idx
  on content.outbox_events (event_type, available_at, created_at, id)
  where dead_lettered_at is null
    and delivered_at is null
    and event_type in (
      'media.publish_requested',
      'media.cleanup_requested',
      'account.avatar_cleanup_requested'
    )
    and status in (
      'pending'::content.outbox_status,
      'failed'::content.outbox_status
    );

create index outbox_events_internal_expired_lease_idx
  on content.outbox_events (event_type, locked_until, created_at, id)
  where dead_lettered_at is null
    and delivered_at is null
    and event_type in (
      'media.publish_requested',
      'media.cleanup_requested',
      'account.avatar_cleanup_requested'
    )
    and status = 'processing'::content.outbox_status;

create or replace function content_private.outbox_claim_media_events_impl(
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

  for v_exhausted_event in
    with exhausted as (
      select event.id
      from content.outbox_events as event
      where event.event_type in (
          'media.publish_requested',
          'media.cleanup_requested',
          'account.avatar_cleanup_requested'
        )
        and event.dead_lettered_at is null
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
    where event.event_type in (
        'media.publish_requested',
        'media.cleanup_requested',
        'account.avatar_cleanup_requested'
      )
      and event.dead_lettered_at is null
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

revoke all on function content_private.outbox_claim_media_events_impl(text, integer, integer)
  from public, anon, authenticated, service_role;
grant execute on function content_private.outbox_claim_media_events_impl(text, integer, integer)
  to service_role;

comment on function public.account_deletion_prepare() is
  'Authorizes recent non-operator callers and durably schedules avatar cleanup before Auth deletion.';
