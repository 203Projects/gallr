-- Coalesce exhibition lifecycle bursts into one delayed public-site rebuild
-- while preserving a follow-up request for edits committed during delivery.

create index if not exists outbox_events_rebuild_coalesce_idx
  on content.outbox_events (created_at desc, id)
  where event_type = 'public_site.rebuild_requested'
    and delivered_at is null
    and dead_lettered_at is null
    and status in (
      'pending'::content.outbox_status,
      'failed'::content.outbox_status
    );

create or replace function content_private.enqueue_public_site_rebuild_request()
returns trigger
language plpgsql
volatile
security invoker
set search_path = ''
as $$
declare
  v_now timestamptz := clock_timestamp();
  v_request_id uuid;
  v_source_event_count integer;
begin
  if new.event_type not in (
    'exhibition.published',
    'exhibition.archived',
    'exhibition.restored'
  ) then
    return new;
  end if;

  new.payload := new.payload || jsonb_build_object(
    'public_site_rebuild_queued', true
  );

  -- Serialize only this single coalescing lane. A processing request is
  -- deliberately excluded below so a later edit always creates a follow-up.
  perform pg_catalog.pg_advisory_xact_lock(314159265358979::bigint);

  select event.id
  into v_request_id
  from content.outbox_events as event
  where event.event_type = 'public_site.rebuild_requested'
    and event.status in (
      'pending'::content.outbox_status,
      'failed'::content.outbox_status
    )
    and event.delivered_at is null
    and event.dead_lettered_at is null
    and event.attempts < event.max_attempts
  order by event.created_at desc, event.id desc
  limit 1
  for update;

  if found then
    select case
      when jsonb_typeof(event.payload -> 'source_event_count') = 'number'
        and event.payload ->> 'source_event_count' ~ '^[0-9]{1,6}$'
      then least(
        (event.payload ->> 'source_event_count')::integer + 1,
        1000000
      )
      else 1
    end
    into v_source_event_count
    from content.outbox_events as event
    where event.id = v_request_id;

    update content.outbox_events as event
    set
      available_at = v_now + interval '30 seconds',
      payload = jsonb_build_object(
        'source_event_count', v_source_event_count,
        'first_event_id', coalesce(
          event.payload ->> 'first_event_id',
          new.id::text
        ),
        'latest_event_id', new.id::text,
        'latest_event_type', new.event_type
      ),
      updated_at = v_now
    where event.id = v_request_id;
  else
    insert into content.outbox_events (
      aggregate_type,
      aggregate_id,
      event_type,
      payload,
      deduplication_key,
      available_at
    )
    values (
      'public_site',
      'catalogue',
      'public_site.rebuild_requested',
      jsonb_build_object(
        'source_event_count', 1,
        'first_event_id', new.id::text,
        'latest_event_id', new.id::text,
        'latest_event_type', new.event_type
      ),
      'public-site-rebuild:' || new.id::text,
      v_now + interval '30 seconds'
    );
  end if;

  return new;
end;
$$;

revoke all on function content_private.enqueue_public_site_rebuild_request()
  from public, anon, authenticated, service_role;

drop trigger if exists outbox_events_enqueue_public_site_rebuild
  on content.outbox_events;
create trigger outbox_events_enqueue_public_site_rebuild
  before insert on content.outbox_events
  for each row
  execute function content_private.enqueue_public_site_rebuild_request();

comment on function content_private.enqueue_public_site_rebuild_request() is
  'Coalesces lifecycle outbox inserts into a delayed durable public-site rebuild request.';
