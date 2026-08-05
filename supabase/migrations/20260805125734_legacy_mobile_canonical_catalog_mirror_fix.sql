-- Extend the temporary Seoul-to-Singapore compatibility bridge to the
-- canonical-v2 reader used by iOS 1.7.4 and 1.7.5. The original three-resource
-- payload remains accepted during the Edge Function rollout window.

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
  v_canonical_v2_count bigint := null;
  v_current_count bigint;
  v_stale_count bigint;
  v_has_duplicate boolean;
  v_checksum_mismatch boolean;
  v_has_canonical_v2 boolean;
  v_audit_id uuid;
  v_temp_schema name;
  v_temp_exhibitions name := 'legacy_mobile_exhibitions';
  v_temp_events name := 'legacy_mobile_events';
  v_temp_editors name := 'legacy_mobile_editors';
  v_temp_canonical_v2 name := 'legacy_mobile_exhibition_catalog_v2';
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

  v_has_canonical_v2 := p_snapshot ? 'exhibition_catalog_v2';
  if exists (
    select 1
    from jsonb_object_keys(p_snapshot) as key(value)
    where key.value not in (
      'exhibitions',
      'events',
      'editors',
      'exhibition_catalog_v2'
    )
  ) or not (p_snapshot ?& array['exhibitions', 'events', 'editors']) then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_snapshot_keys_are_invalid';
  end if;
  if jsonb_typeof(p_snapshot -> 'exhibitions') <> 'array'
      or jsonb_typeof(p_snapshot -> 'events') <> 'array'
      or jsonb_typeof(p_snapshot -> 'editors') <> 'array'
      or (
        v_has_canonical_v2
        and jsonb_typeof(p_snapshot -> 'exhibition_catalog_v2') <> 'array'
      ) then
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
  drop table if exists pg_temp.legacy_mobile_exhibition_catalog_v2;

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

  create temporary table legacy_mobile_exhibition_catalog_v2 on commit drop as
  select *
  from jsonb_to_recordset(
    coalesce(p_snapshot -> 'exhibition_catalog_v2', '[]'::jsonb)
  ) as canonical_row(
    id text,
    name_ko text,
    name_en text,
    venue_name_ko text,
    venue_name_en text,
    city_ko text,
    city_en text,
    region_ko text,
    region_en text,
    opening_date date,
    closing_date date,
    is_featured boolean,
    latitude double precision,
    longitude double precision,
    description_ko text,
    description_en text,
    address_ko text,
    address_en text,
    cover_image_url text,
    hours text,
    contact text,
    reception_date timestamptz,
    opening_time text,
    event_id text,
    editor_id text,
    is_homepage_featured boolean,
    ticket_url text,
    updated_at timestamptz,
    is_editors_pick boolean,
    guest_editor_id text,
    content_checksum_sha256 text,
    credits_ko text,
    credits_en text
  );

  select namespace.nspname
  into strict v_temp_schema
  from pg_catalog.pg_namespace as namespace
  where namespace.oid = pg_catalog.pg_my_temp_schema();

  execute pg_catalog.format(
    'select count(*) from %I.%I',
    v_temp_schema,
    v_temp_exhibitions
  )
    into v_exhibition_count;
  execute pg_catalog.format(
    'select count(*) from %I.%I',
    v_temp_schema,
    v_temp_events
  )
    into v_event_count;
  execute pg_catalog.format(
    'select count(*) from %I.%I',
    v_temp_schema,
    v_temp_editors
  )
    into v_editor_count;
  if v_has_canonical_v2 then
    execute pg_catalog.format(
      'select count(*) from %I.%I',
      v_temp_schema,
      v_temp_canonical_v2
    )
      into v_canonical_v2_count;
  end if;

  if v_exhibition_count = 0 then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_exhibitions_must_not_be_empty';
  end if;
  if v_has_canonical_v2 and v_canonical_v2_count = 0 then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_canonical_v2_must_not_be_empty';
  end if;

  execute pg_catalog.format($sql$
  select
    exists (
      select id from %I.%I
      group by id having count(*) > 1
    )
    or exists (
      select id from %I.%I
      group by id having count(*) > 1
    )
    or exists (
      select id from %I.%I
      group by id having count(*) > 1
    )
  $sql$,
    v_temp_schema, v_temp_exhibitions,
    v_temp_schema, v_temp_events,
    v_temp_schema, v_temp_editors
  ) into v_has_duplicate;
  if v_has_canonical_v2 and not v_has_duplicate then
    execute pg_catalog.format($sql$
      select exists (
        select id from %I.%I
        group by id having count(*) > 1
      )
    $sql$, v_temp_schema, v_temp_canonical_v2) into v_has_duplicate;
  end if;
  if v_has_duplicate then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_contains_duplicate_ids';
  end if;

  select count(*) into v_current_count from public.exhibitions;
  execute pg_catalog.format($sql$
    select count(*)
    from public.exhibitions as target
    where not exists (
      select 1 from %I.%I as source
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
  execute pg_catalog.format($sql$
    select count(*)
    from public.events as target
    where not exists (
      select 1 from %I.%I as source
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
  execute pg_catalog.format($sql$
    select count(*)
    from public.editors as target
    where not exists (
      select 1 from %I.%I as source
      where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_editors) into v_stale_count;
  if v_current_count > 0
      and v_stale_count::numeric / v_current_count > v_config.max_delete_fraction then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_delete_limit_exceeded';
  end if;

  if v_has_canonical_v2 then
    select count(*) into v_current_count from public.exhibition_catalog_v2;
    execute pg_catalog.format($sql$
      select count(*)
      from public.exhibition_catalog_v2 as target
      where not exists (
        select 1
        from %I.%I as source
        where source.id = target.id
      )
    $sql$, v_temp_schema, v_temp_canonical_v2) into v_stale_count;
    if v_current_count > 0
        and v_stale_count::numeric / v_current_count > v_config.max_delete_fraction then
      raise exception using
        errcode = '22023',
        message = 'legacy_mobile_catalog_delete_limit_exceeded';
    end if;
  end if;

  -- Serialize with the canonical-to-legacy projector and open its private write
  -- context only around the compatibility DML.
  perform pg_catalog.pg_advisory_xact_lock(73241, 1);
  insert into content_private.exhibition_catalog_legacy_write_context (backend_pid)
  values (pg_catalog.pg_backend_pid())
  on conflict (backend_pid) do nothing;

  execute pg_catalog.format($sql$
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

  execute pg_catalog.format($sql$
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

  execute pg_catalog.format($sql$
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

  execute pg_catalog.format($sql$
    delete from public.exhibitions as target
    where not exists (
      select 1 from %I.%I as source
      where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_exhibitions);
  execute pg_catalog.format($sql$
    delete from public.events as target
    where not exists (
      select 1 from %I.%I as source
      where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_events);
  execute pg_catalog.format($sql$
    delete from public.editors as target
    where not exists (
      select 1 from %I.%I as source
      where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_editors);

  if v_has_canonical_v2 then
    execute pg_catalog.format($sql$
    insert into public.exhibition_catalog_v2 as target (
      id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en,
      region_ko, region_en, opening_date, closing_date, is_featured, latitude,
      longitude, description_ko, description_en, address_ko, address_en,
      cover_image_url, hours, contact, reception_date, opening_time, event_id,
      editor_id, is_homepage_featured, ticket_url, updated_at, is_editors_pick,
      guest_editor_id, content_checksum_sha256, credits_ko, credits_en
    )
    select
      id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en,
      region_ko, region_en, opening_date, closing_date, is_featured, latitude,
      longitude, description_ko, description_en, address_ko, address_en,
      cover_image_url, hours, contact, reception_date, opening_time, event_id,
      editor_id, is_homepage_featured, ticket_url, updated_at, is_editors_pick,
      guest_editor_id, content_checksum_sha256, credits_ko, credits_en
    from %I.%I
    on conflict (id) do update set
      name_ko = excluded.name_ko,
      name_en = excluded.name_en,
      venue_name_ko = excluded.venue_name_ko,
      venue_name_en = excluded.venue_name_en,
      city_ko = excluded.city_ko,
      city_en = excluded.city_en,
      region_ko = excluded.region_ko,
      region_en = excluded.region_en,
      opening_date = excluded.opening_date,
      closing_date = excluded.closing_date,
      is_featured = excluded.is_featured,
      latitude = excluded.latitude,
      longitude = excluded.longitude,
      description_ko = excluded.description_ko,
      description_en = excluded.description_en,
      address_ko = excluded.address_ko,
      address_en = excluded.address_en,
      cover_image_url = excluded.cover_image_url,
      hours = excluded.hours,
      contact = excluded.contact,
      reception_date = excluded.reception_date,
      opening_time = excluded.opening_time,
      event_id = excluded.event_id,
      editor_id = excluded.editor_id,
      is_homepage_featured = excluded.is_homepage_featured,
      ticket_url = excluded.ticket_url,
      updated_at = excluded.updated_at,
      is_editors_pick = excluded.is_editors_pick,
      guest_editor_id = excluded.guest_editor_id,
      content_checksum_sha256 = excluded.content_checksum_sha256,
      credits_ko = excluded.credits_ko,
      credits_en = excluded.credits_en
    where target.content_checksum_sha256
      is distinct from excluded.content_checksum_sha256
    $sql$, v_temp_schema, v_temp_canonical_v2);

    execute pg_catalog.format($sql$
      delete from public.exhibition_catalog_v2 as target
      where not exists (
        select 1
        from %I.%I as source
        where source.id = target.id
      )
    $sql$, v_temp_schema, v_temp_canonical_v2);

    -- The target derives its own checksum before conflict resolution. Comparing
    -- it with the source checksum makes corrupt or incomplete payloads fail
    -- atomically instead of becoming a trusted compatibility snapshot.
    execute pg_catalog.format($sql$
      select exists (
        select 1
        from public.exhibition_catalog_v2 as target
        join %I.%I as source using (id)
        where target.content_checksum_sha256
          is distinct from source.content_checksum_sha256
      )
    $sql$, v_temp_schema, v_temp_canonical_v2) into v_checksum_mismatch;
    if v_checksum_mismatch then
      raise exception using
        errcode = '22023',
        message = 'legacy_mobile_catalog_canonical_v2_checksum_mismatch';
    end if;
  end if;

  delete from content_private.exhibition_catalog_legacy_write_context
  where backend_pid = pg_catalog.pg_backend_pid();

  update content_private.legacy_mobile_catalog_mirror_config as config
  set last_snapshot_sha256 = v_snapshot_sha256,
      last_applied_at = now(),
      reason = btrim(p_reason)
  where config.singleton;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  ) values (
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
      'canonical_v2_count', v_canonical_v2_count,
      'reason', btrim(p_reason)
    )
  ) returning id into v_audit_id;

  return jsonb_build_object(
    'status', 'applied',
    'snapshot_sha256', v_snapshot_sha256,
    'exhibition_count', v_exhibition_count,
    'event_count', v_event_count,
    'editor_count', v_editor_count,
    'canonical_v2_count', v_canonical_v2_count,
    'audit_id', v_audit_id
  );
end;
$function$;

comment on function public.service_replace_legacy_mobile_catalog(jsonb, text, text) is
  'Service-role-only Seoul snapshot bridge for installed mobile readers, including the canonical-v2 catalog used by iOS 1.7.4 and 1.7.5. Three-resource snapshots remain accepted during rollout.';

revoke all
  on function public.service_replace_legacy_mobile_catalog(jsonb, text, text)
  from public, anon, authenticated, service_role;
grant execute
  on function public.service_replace_legacy_mobile_catalog(jsonb, text, text)
  to service_role;

drop trigger if exists exhibition_catalog_v2_enqueue_legacy_mobile_catalog_sync
  on public.exhibition_catalog_v2;
create trigger exhibition_catalog_v2_enqueue_legacy_mobile_catalog_sync
  after insert or update or delete on public.exhibition_catalog_v2
  for each row
  execute function content_private.enqueue_legacy_mobile_catalog_sync();

drop trigger if exists exhibition_catalog_v2_invalidate_legacy_mobile_catalog_snapshot
  on public.exhibition_catalog_v2;
create trigger exhibition_catalog_v2_invalidate_legacy_mobile_catalog_snapshot
  after insert or update or delete or truncate on public.exhibition_catalog_v2
  for each statement
  execute function content_private.invalidate_legacy_mobile_catalog_snapshot();

-- An enabled target may contain stale canonical-v2 data from before this
-- migration. Force the next reconciliation to apply the expanded snapshot.
update content_private.legacy_mobile_catalog_mirror_config as config
set last_snapshot_sha256 = null
where config.singleton
  and config.enabled
  and config.last_snapshot_sha256 is not null;
