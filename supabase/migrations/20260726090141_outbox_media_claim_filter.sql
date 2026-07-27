-- Allow the media worker to make progress before a general outbox delivery
-- receiver exists. Non-media lifecycle events remain pending and untouched.

create index if not exists outbox_events_media_available_claim_idx
  on content.outbox_events (event_type, available_at, created_at, id)
  where dead_lettered_at is null
    and delivered_at is null
    and event_type in ('media.publish_requested', 'media.cleanup_requested')
    and status in (
      'pending'::content.outbox_status,
      'failed'::content.outbox_status
    );

create index if not exists outbox_events_media_expired_lease_idx
  on content.outbox_events (event_type, locked_until, created_at, id)
  where dead_lettered_at is null
    and delivered_at is null
    and event_type in ('media.publish_requested', 'media.cleanup_requested')
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

  -- Only terminalize exhausted media events. General delivery events belong to
  -- the independent downstream-delivery worker and must remain untouched.
  for v_exhausted_event in
    with exhausted as (
      select event.id
      from content.outbox_events as event
      where event.event_type in (
          'media.publish_requested',
          'media.cleanup_requested'
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
        'media.cleanup_requested'
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

create or replace function public.outbox_claim_media_events(
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
  from content_private.outbox_claim_media_events_impl(
    p_lease_owner,
    p_batch_size,
    p_lease_seconds
  );
$$;

revoke all on function public.outbox_claim_media_events(text, integer, integer)
  from public, anon, authenticated, service_role;
grant execute on function public.outbox_claim_media_events(text, integer, integer)
  to service_role;
