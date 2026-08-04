-- Temporary cross-project compatibility bridge for installed mobile clients.
-- Seoul remains authoritative. The RPC is inert until a database owner enables
-- this exact legacy target and records the expected Seoul project ref.

create table content_private.legacy_mobile_catalog_mirror_config (
  singleton boolean primary key default true check (singleton),
  enabled boolean not null default false,
  source_outbox_enabled boolean not null default false,
  expected_source_project_ref text,
  max_delete_fraction numeric not null default 0.25,
  reason text not null default 'installed disabled',
  last_snapshot_sha256 text,
  last_applied_at timestamptz,
  constraint legacy_mobile_catalog_source_ref check (
    expected_source_project_ref is null
    or expected_source_project_ref ~ '^[a-z0-9]{20}$'
  ),
  constraint legacy_mobile_catalog_delete_fraction check (
    max_delete_fraction >= 0 and max_delete_fraction <= 1
  ),
  constraint legacy_mobile_catalog_reason_not_blank check (
    length(btrim(reason)) > 0
  ),
  constraint legacy_mobile_catalog_snapshot_hash check (
    last_snapshot_sha256 is null
    or last_snapshot_sha256 ~ '^[0-9a-f]{64}$'
  ),
  constraint legacy_mobile_catalog_enabled_source check (
    not enabled or expected_source_project_ref is not null
  )
);

insert into content_private.legacy_mobile_catalog_mirror_config (singleton)
values (true);

alter table content_private.legacy_mobile_catalog_mirror_config
  enable row level security;

revoke all on content_private.legacy_mobile_catalog_mirror_config
  from public, anon, authenticated, service_role;

create or replace function content_private.enqueue_legacy_mobile_catalog_sync()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_enabled boolean;
begin
  select config.source_outbox_enabled
  into strict v_enabled
  from content_private.legacy_mobile_catalog_mirror_config as config
  where config.singleton;

  if not v_enabled then
    return null;
  end if;

  insert into content.outbox_events (
    aggregate_type,
    aggregate_id,
    event_type,
    payload,
    deduplication_key
  ) values (
    'public_catalogue',
    'public-catalogue',
    'legacy_catalog.sync_requested',
    jsonb_build_object('source', 'database_catalog_trigger'),
    format('legacy_catalog:sync_requested:%s', pg_catalog.txid_current())
  )
  on conflict (deduplication_key) do nothing;

  return null;
end;
$function$;

revoke all on function content_private.enqueue_legacy_mobile_catalog_sync()
  from public, anon, authenticated, service_role;

create trigger exhibitions_enqueue_legacy_mobile_catalog_sync
  after insert or update or delete on public.exhibitions
  for each row execute function content_private.enqueue_legacy_mobile_catalog_sync();

create trigger events_enqueue_legacy_mobile_catalog_sync
  after insert or update or delete on public.events
  for each row execute function content_private.enqueue_legacy_mobile_catalog_sync();

create trigger editors_enqueue_legacy_mobile_catalog_sync
  after insert or update or delete on public.editors
  for each row execute function content_private.enqueue_legacy_mobile_catalog_sync();

do $extension$
begin
  if not exists (
    select 1 from pg_catalog.pg_extension where extname = 'pg_net'
  ) then
    create extension pg_net with schema extensions;
  end if;
  if not exists (
    select 1 from pg_catalog.pg_extension where extname = 'supabase_vault'
  ) then
    create extension supabase_vault with schema vault;
  end if;
  if not exists (
    select 1 from pg_catalog.pg_extension where extname = 'pg_cron'
  ) then
    create extension pg_cron with schema pg_catalog;
  end if;
end;
$extension$;

create or replace function content_private.invoke_legacy_catalog_mirror()
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_source_enabled boolean;
  v_mirror_url text;
  v_mirror_token text;
  v_request_id bigint;
begin
  select config.source_outbox_enabled
  into strict v_source_enabled
  from content_private.legacy_mobile_catalog_mirror_config as config
  where config.singleton;

  if not v_source_enabled then
    raise exception using
      errcode = '55000',
      message = 'legacy_mobile_catalog_source_disabled';
  end if;

  select secret.decrypted_secret
  into v_mirror_url
  from vault.decrypted_secrets as secret
  where secret.name = 'gallr_legacy_catalog_mirror_url';

  select secret.decrypted_secret
  into v_mirror_token
  from vault.decrypted_secrets as secret
  where secret.name = 'gallr_legacy_catalog_mirror_token';

  v_mirror_url := btrim(v_mirror_url);
  v_mirror_token := btrim(v_mirror_token);

  if v_mirror_url is null or v_mirror_token is null then
    raise exception using
      errcode = '55000',
      message = 'legacy_mobile_catalog_schedule_not_configured';
  end if;
  if v_mirror_url <> 'https://oqrvbstopuppznxqoonp.supabase.co/functions/v1/legacy-catalog-mirror' then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_mirror_url_is_invalid';
  end if;
  if length(v_mirror_token) < 32
      or v_mirror_token ~ '[[:space:][:cntrl:]]' then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_mirror_token_is_invalid';
  end if;

  select net.http_post(
    url := v_mirror_url,
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || v_mirror_token
    ),
    body := jsonb_build_object(
      'source', 'five-minute-reconciliation',
      'requested_at', clock_timestamp()
    ),
    timeout_milliseconds := 30000
  )
  into v_request_id;

  return v_request_id;
end;
$function$;

revoke all on function content_private.invoke_legacy_catalog_mirror()
  from public, anon, authenticated, service_role;

comment on function content_private.invoke_legacy_catalog_mirror() is
  'Database-owner-only pg_net entry point for the five-minute Seoul catalogue reconciliation job. Applying the migration does not schedule it.';

create or replace function public.service_replace_legacy_mobile_catalog(
  p_snapshot jsonb,
  p_source_project_ref text,
  p_reason text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
set timezone = 'UTC'
as $function$
declare
  v_config content_private.legacy_mobile_catalog_mirror_config%rowtype;
  v_snapshot_sha256 text;
  v_exhibition_count bigint;
  v_event_count bigint;
  v_editor_count bigint;
  v_current_count bigint;
  v_stale_count bigint;
  v_has_duplicate boolean;
  v_audit_id uuid;
  v_temp_schema name;
  v_temp_exhibitions name := 'legacy_mobile_exhibitions';
  v_temp_events name := 'legacy_mobile_events';
  v_temp_editors name := 'legacy_mobile_editors';
begin
  if p_snapshot is null or jsonb_typeof(p_snapshot) <> 'object' then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_snapshot_must_be_an_object';
  end if;
  if pg_column_size(p_snapshot) > 16777216 then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_snapshot_is_too_large';
  end if;
  if exists (
    select 1
    from jsonb_object_keys(p_snapshot) as key(value)
    where key.value not in ('exhibitions', 'events', 'editors')
  ) or not (p_snapshot ?& array['exhibitions', 'events', 'editors']) then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_snapshot_keys_are_invalid';
  end if;
  if jsonb_typeof(p_snapshot -> 'exhibitions') <> 'array'
      or jsonb_typeof(p_snapshot -> 'events') <> 'array'
      or jsonb_typeof(p_snapshot -> 'editors') <> 'array' then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_snapshot_resources_must_be_arrays';
  end if;
  if p_source_project_ref is null
      or p_source_project_ref !~ '^[a-z0-9]{20}$' then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_source_ref_is_invalid';
  end if;
  if p_reason is null or length(btrim(p_reason)) = 0
      or length(p_reason) > 500 then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_reason_is_invalid';
  end if;

  select *
  into strict v_config
  from content_private.legacy_mobile_catalog_mirror_config as config
  where config.singleton
  for update;

  if not v_config.enabled then
    raise exception using
      errcode = '42501',
      message = 'legacy_mobile_catalog_mirror_disabled';
  end if;
  if v_config.expected_source_project_ref is distinct from p_source_project_ref then
    raise exception using
      errcode = '42501',
      message = 'legacy_mobile_catalog_source_mismatch';
  end if;
  if not coalesce(
    (
      select runtime.legacy_writes_blocked
      from content_private.exhibition_catalog_runtime as runtime
      where runtime.singleton
    ),
    false
  ) then
    raise exception using
      errcode = '55000',
      message = 'legacy_mobile_catalog_target_is_not_frozen';
  end if;

  v_snapshot_sha256 := encode(
    extensions.digest(convert_to(p_snapshot::text, 'UTF8'), 'sha256'),
    'hex'
  );
  if v_config.last_snapshot_sha256 = v_snapshot_sha256 then
    return jsonb_build_object(
      'status', 'unchanged',
      'snapshot_sha256', v_snapshot_sha256,
      'last_applied_at', v_config.last_applied_at
    );
  end if;

  drop table if exists pg_temp.legacy_mobile_events;
  drop table if exists pg_temp.legacy_mobile_editors;
  drop table if exists pg_temp.legacy_mobile_exhibitions;

  create temporary table legacy_mobile_events on commit drop as
  select *
  from jsonb_to_recordset(p_snapshot -> 'events') as event_row(
    id text,
    name_ko text,
    name_en text,
    description_ko text,
    description_en text,
    location_label_ko text,
    location_label_en text,
    start_date date,
    end_date date,
    brand_color text,
    accent_color text,
    ticket_url text,
    is_active boolean,
    updated_at timestamptz,
    cover_image_url text,
    short_label text
  );

  create temporary table legacy_mobile_editors on commit drop as
  select *
  from jsonb_to_recordset(p_snapshot -> 'editors') as editor_row(
    id text,
    name_ko text,
    name_en text,
    title_ko text,
    title_en text,
    bio_ko text,
    bio_en text,
    is_active boolean,
    active_from date,
    active_to date,
    created_at timestamptz,
    updated_at timestamptz
  );

  create temporary table legacy_mobile_exhibitions on commit drop as
  select *
  from jsonb_to_recordset(p_snapshot -> 'exhibitions') as exhibition_row(
    id text,
    name_ko text,
    venue_name_ko text,
    city_ko text,
    region_ko text,
    opening_date date,
    closing_date date,
    is_featured boolean,
    latitude double precision,
    longitude double precision,
    description_ko text,
    cover_image_url text,
    updated_at timestamptz,
    name_en text,
    venue_name_en text,
    city_en text,
    region_en text,
    description_en text,
    address_ko text,
    address_en text,
    hours text,
    contact text,
    reception_date timestamptz,
    opening_time text,
    ticket_url text,
    is_homepage_featured boolean,
    event_id text,
    editor_id text,
    credits_ko text,
    credits_en text
  );

  select namespace.nspname
  into strict v_temp_schema
  from pg_catalog.pg_namespace as namespace
  where namespace.oid = pg_catalog.pg_my_temp_schema();

  execute format(
    'select count(*) from %I.%I',
    v_temp_schema,
    v_temp_exhibitions
  )
    into v_exhibition_count;
  execute format(
    'select count(*) from %I.%I',
    v_temp_schema,
    v_temp_events
  )
    into v_event_count;
  execute format(
    'select count(*) from %I.%I',
    v_temp_schema,
    v_temp_editors
  )
    into v_editor_count;
  if v_exhibition_count = 0 then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_exhibitions_must_not_be_empty';
  end if;
  execute format($sql$
    select
      exists (
        select id
        from %I.%I
        group by id
        having count(*) > 1
      )
      or exists (
        select id
        from %I.%I
        group by id
        having count(*) > 1
      )
      or exists (
        select id
        from %I.%I
        group by id
        having count(*) > 1
      )
  $sql$,
    v_temp_schema, v_temp_exhibitions,
    v_temp_schema, v_temp_events,
    v_temp_schema, v_temp_editors
  ) into v_has_duplicate;
  if v_has_duplicate then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_contains_duplicate_ids';
  end if;

  select count(*) into v_current_count from public.exhibitions;
  execute format($sql$
    select count(*)
    from public.exhibitions as target
    where not exists (
      select 1
      from %I.%I as source
      where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_exhibitions) into v_stale_count;
  if v_current_count > 0
      and v_stale_count::numeric / v_current_count > v_config.max_delete_fraction then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_delete_limit_exceeded';
  end if;

  select count(*) into v_current_count from public.events;
  execute format($sql$
    select count(*)
    from public.events as target
    where not exists (
      select 1
      from %I.%I as source
      where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_events) into v_stale_count;
  if v_current_count > 0
      and v_stale_count::numeric / v_current_count > v_config.max_delete_fraction then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_delete_limit_exceeded';
  end if;

  select count(*) into v_current_count from public.editors;
  execute format($sql$
    select count(*)
    from public.editors as target
    where not exists (
      select 1
      from %I.%I as source
      where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_editors) into v_stale_count;
  if v_current_count > 0
      and v_stale_count::numeric / v_current_count > v_config.max_delete_fraction then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_delete_limit_exceeded';
  end if;

  -- Serialize with the existing canonical-to-legacy projector and open only
  -- its private, backend-scoped write context around compatibility DML.
  perform pg_catalog.pg_advisory_xact_lock(73241, 1);
  insert into content_private.exhibition_catalog_legacy_write_context (backend_pid)
  values (pg_catalog.pg_backend_pid())
  on conflict (backend_pid) do nothing;

  execute format($sql$
  insert into public.events as target (
    id, name_ko, name_en, description_ko, description_en, location_label_ko,
    location_label_en, start_date, end_date, brand_color, accent_color,
    ticket_url, is_active, updated_at, cover_image_url, short_label
  )
  select
    id, name_ko, name_en, description_ko, description_en, location_label_ko,
    location_label_en, start_date, end_date, brand_color, accent_color,
    ticket_url, is_active, updated_at, cover_image_url, short_label
  from %I.%I
  on conflict (id) do update set
    name_ko = excluded.name_ko,
    name_en = excluded.name_en,
    description_ko = excluded.description_ko,
    description_en = excluded.description_en,
    location_label_ko = excluded.location_label_ko,
    location_label_en = excluded.location_label_en,
    start_date = excluded.start_date,
    end_date = excluded.end_date,
    brand_color = excluded.brand_color,
    accent_color = excluded.accent_color,
    ticket_url = excluded.ticket_url,
    is_active = excluded.is_active,
    updated_at = excluded.updated_at,
    cover_image_url = excluded.cover_image_url,
    short_label = excluded.short_label
  $sql$, v_temp_schema, v_temp_events);

  execute format($sql$
  insert into public.editors as target (
    id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en, is_active,
    active_from, active_to, created_at, updated_at
  )
  select
    id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en, is_active,
    active_from, active_to, created_at, updated_at
  from %I.%I
  on conflict (id) do update set
    name_ko = excluded.name_ko,
    name_en = excluded.name_en,
    title_ko = excluded.title_ko,
    title_en = excluded.title_en,
    bio_ko = excluded.bio_ko,
    bio_en = excluded.bio_en,
    is_active = excluded.is_active,
    active_from = excluded.active_from,
    active_to = excluded.active_to,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at
  $sql$, v_temp_schema, v_temp_editors);

  execute format($sql$
  insert into public.exhibitions as target (
    id, name_ko, venue_name_ko, city_ko, region_ko, opening_date,
    closing_date, is_featured, latitude, longitude, description_ko,
    cover_image_url, updated_at, name_en, venue_name_en, city_en, region_en,
    description_en, address_ko, address_en, hours, contact, reception_date,
    opening_time, ticket_url, is_homepage_featured, event_id, editor_id,
    credits_ko, credits_en
  )
  select
    id, name_ko, venue_name_ko, city_ko, region_ko, opening_date,
    closing_date, is_featured, latitude, longitude, description_ko,
    cover_image_url, updated_at, name_en, venue_name_en, city_en, region_en,
    description_en, address_ko, address_en, hours, contact, reception_date,
    opening_time, ticket_url, is_homepage_featured, event_id, editor_id,
    credits_ko, credits_en
  from %I.%I
  on conflict (id) do update set
    name_ko = excluded.name_ko,
    venue_name_ko = excluded.venue_name_ko,
    city_ko = excluded.city_ko,
    region_ko = excluded.region_ko,
    opening_date = excluded.opening_date,
    closing_date = excluded.closing_date,
    is_featured = excluded.is_featured,
    latitude = excluded.latitude,
    longitude = excluded.longitude,
    description_ko = excluded.description_ko,
    cover_image_url = excluded.cover_image_url,
    updated_at = excluded.updated_at,
    name_en = excluded.name_en,
    venue_name_en = excluded.venue_name_en,
    city_en = excluded.city_en,
    region_en = excluded.region_en,
    description_en = excluded.description_en,
    address_ko = excluded.address_ko,
    address_en = excluded.address_en,
    hours = excluded.hours,
    contact = excluded.contact,
    reception_date = excluded.reception_date,
    opening_time = excluded.opening_time,
    ticket_url = excluded.ticket_url,
    is_homepage_featured = excluded.is_homepage_featured,
    event_id = excluded.event_id,
    editor_id = excluded.editor_id,
    credits_ko = excluded.credits_ko,
    credits_en = excluded.credits_en
  $sql$, v_temp_schema, v_temp_exhibitions);

  execute format($sql$
    delete from public.exhibitions as target
    where not exists (
      select 1
      from %I.%I as source
      where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_exhibitions);
  execute format($sql$
    delete from public.events as target
    where not exists (
      select 1
      from %I.%I as source
      where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_events);
  execute format($sql$
    delete from public.editors as target
    where not exists (
      select 1
      from %I.%I as source
      where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_editors);

  delete from content_private.exhibition_catalog_legacy_write_context
  where backend_pid = pg_catalog.pg_backend_pid();

  update content_private.legacy_mobile_catalog_mirror_config as config
  set last_snapshot_sha256 = v_snapshot_sha256,
      last_applied_at = now(),
      reason = btrim(p_reason)
  where config.singleton;

  insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
  values (
    null,
    'legacy_mobile_catalog_mirror.applied',
    'system_setting',
    'legacy_mobile_catalog_mirror',
    jsonb_build_object(
      'source_project_ref', p_source_project_ref,
      'snapshot_sha256', v_snapshot_sha256,
      'exhibition_count', v_exhibition_count,
      'event_count', v_event_count,
      'editor_count', v_editor_count,
      'reason', btrim(p_reason)
    )
  )
  returning id into v_audit_id;

  return jsonb_build_object(
    'status', 'applied',
    'snapshot_sha256', v_snapshot_sha256,
    'exhibition_count', v_exhibition_count,
    'event_count', v_event_count,
    'editor_count', v_editor_count,
    'audit_id', v_audit_id
  );
end;
$function$;

comment on function public.service_replace_legacy_mobile_catalog(jsonb, text, text) is
  'Service-role-only, disabled-by-default snapshot bridge from the configured Seoul project into a frozen legacy mobile reader. A database owner must enable the exact legacy target first.';

revoke all on function public.service_replace_legacy_mobile_catalog(jsonb, text, text)
  from public, anon, authenticated, service_role;
grant execute on function public.service_replace_legacy_mobile_catalog(jsonb, text, text)
  to service_role;
