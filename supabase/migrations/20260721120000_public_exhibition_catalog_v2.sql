-- Canonical, published-only exhibition read model for anonymous clients.
--
-- The private versioned model remains authoritative. This flat table is updated
-- in the same transaction as canonical writes and deliberately coexists with
-- public.exhibitions so readers can be switched and rolled back independently.

create table public.exhibition_catalog_v2 (
  id text primary key,
  name_ko text not null,
  name_en text not null,
  venue_name_ko text not null,
  venue_name_en text not null,
  city_ko text not null,
  city_en text not null,
  region_ko text not null,
  region_en text not null,
  opening_date date not null,
  closing_date date not null,
  is_featured boolean not null,
  latitude double precision,
  longitude double precision,
  description_ko text not null,
  description_en text not null,
  address_ko text not null,
  address_en text not null,
  cover_image_url text,
  hours text,
  contact text,
  reception_date timestamptz,
  opening_time text,
  event_id text,
  editor_id text,
  is_homepage_featured boolean not null,
  ticket_url text,
  updated_at timestamptz not null,
  is_editors_pick boolean not null,
  guest_editor_id text,
  content_checksum_sha256 text not null,
  constraint exhibition_catalog_v2_id_not_blank check (
    id = btrim(id) and length(id) between 1 and 128
  ),
  constraint exhibition_catalog_v2_required_text_not_blank check (
    length(btrim(name_ko)) > 0
    and length(btrim(venue_name_ko)) > 0
    and length(btrim(city_ko)) > 0
    and length(btrim(region_ko)) > 0
  ),
  constraint exhibition_catalog_v2_date_order check (
    closing_date >= opening_date
  ),
  constraint exhibition_catalog_v2_latitude_range check (
    latitude is null or latitude between -90 and 90
  ),
  constraint exhibition_catalog_v2_longitude_range check (
    longitude is null or longitude between -180 and 180
  ),
  constraint exhibition_catalog_v2_coordinate_pair check (
    (latitude is null) = (longitude is null)
  ),
  constraint exhibition_catalog_v2_editor_pick_alias check (
    is_editors_pick = coalesce(editor_id = 'gallr-editors', false)
  ),
  constraint exhibition_catalog_v2_guest_editor_alias check (
    guest_editor_id is not distinct from case
      when editor_id is null or editor_id = 'gallr-editors' then null
      else editor_id
    end
  ),
  constraint exhibition_catalog_v2_content_checksum_format check (
    content_checksum_sha256 ~ '^[0-9a-f]{64}$'
  )
);

comment on table public.exhibition_catalog_v2 is
  'Published-only, transactionally maintained public exhibition read model. The private content schema remains authoritative.';
comment on column public.exhibition_catalog_v2.content_checksum_sha256 is
  'SHA-256 of a canonical JSONB payload containing every other public catalog field.';

create index exhibition_catalog_v2_event_id_id_idx
  on public.exhibition_catalog_v2 (event_id, id)
  where event_id is not null;
create index exhibition_catalog_v2_featured_id_idx
  on public.exhibition_catalog_v2 (id)
  where is_featured;
create index exhibition_catalog_v2_homepage_closing_id_idx
  on public.exhibition_catalog_v2 (closing_date, id)
  where is_homepage_featured;

alter table public.exhibition_catalog_v2 enable row level security;

create policy "public readers can read exhibition catalog v2"
  on public.exhibition_catalog_v2
  for select
  to anon, authenticated, service_role
  using (true);

revoke all on public.exhibition_catalog_v2
  from public, anon, authenticated, service_role;
grant select on public.exhibition_catalog_v2
  to anon, authenticated, service_role;

-- Older Supabase projects can retain broad table defaults from before explicit
-- Data API grants became the platform default. Preserve the installed-client
-- legacy read endpoint while removing every direct reader-role write path,
-- including any historical column-level grant.
revoke all privileges on table public.exhibitions from anon, authenticated;
grant select on table public.exhibitions to anon, authenticated;

-- Sheet/Apps Script continues to own legacy writes until mirror activation.
-- Encode that temporary ownership explicitly instead of relying on a
-- project's public-schema default privileges.
grant select, insert, update, delete, truncate
  on table public.exhibitions to service_role;

do $revoke_legacy_reader_column_writes$
declare
  v_columns text;
begin
  select string_agg(
    pg_catalog.quote_ident(attribute.attname),
    ', ' order by attribute.attnum
  )
  into v_columns
  from pg_catalog.pg_attribute as attribute
  where attribute.attrelid = 'public.exhibitions'::regclass
    and attribute.attnum > 0
    and not attribute.attisdropped;

  if v_columns is not null then
    execute pg_catalog.format(
      'revoke all privileges (%s) on table public.exhibitions from anon, authenticated',
      v_columns
    );
  end if;
end
$revoke_legacy_reader_column_writes$;

-- The service role invokes fixed SECURITY DEFINER command APIs, which append
-- their own evidence as the database owner. It does not need to fabricate or
-- rewrite audit rows directly. PostgreSQL owners remain an operational trust
-- boundary, but API credentials cannot mutate the recorded history.
revoke all privileges on table content.audit_log from service_role;
grant select on table content.audit_log to service_role;

-- The Sheet writer owns public.exhibitions during coexistence, so canonical
-- projection must not touch that table by default. The final cutover command
-- flips this singleton only after both reader contracts have identical ID
-- membership. From then until legacy clients retire, the canonical transaction
-- also keeps the legacy table fresh. Mirror and write-block state are separate
-- so an incident freeze can stop projection while keeping every legacy writer
-- denied: Sheet-owned=(false,false), canonical-owned=(true,true), and
-- frozen=(false,true).
create table content_private.exhibition_catalog_runtime (
  singleton boolean primary key default true check (singleton),
  legacy_mirror_enabled boolean not null default false,
  legacy_writes_blocked boolean not null default false,
  legacy_mirror_enabled_at timestamptz,
  baseline_row_count bigint,
  baseline_id_checksum_sha256 text,
  baseline_catalog_checksum_sha256 text,
  reason text not null default 'installed disabled',
  constraint exhibition_catalog_runtime_enabled_timestamp check (
    legacy_mirror_enabled = (legacy_mirror_enabled_at is not null)
  ),
  constraint exhibition_catalog_runtime_mirror_requires_write_block check (
    not legacy_mirror_enabled or legacy_writes_blocked
  ),
  constraint exhibition_catalog_runtime_baseline_count check (
    baseline_row_count is null or baseline_row_count >= 0
  ),
  constraint exhibition_catalog_runtime_baseline_id_checksum check (
    baseline_id_checksum_sha256 is null
    or baseline_id_checksum_sha256 ~ '^[0-9a-f]{64}$'
  ),
  constraint exhibition_catalog_runtime_baseline_catalog_checksum check (
    baseline_catalog_checksum_sha256 is null
    or baseline_catalog_checksum_sha256 ~ '^[0-9a-f]{64}$'
  ),
  constraint exhibition_catalog_runtime_reason_not_blank check (
    length(btrim(reason)) > 0
  )
);

insert into content_private.exhibition_catalog_runtime (
  singleton,
  legacy_mirror_enabled,
  legacy_writes_blocked,
  legacy_mirror_enabled_at
) values (true, false, false, null);

alter table content_private.exhibition_catalog_runtime enable row level security;

revoke all on content_private.exhibition_catalog_runtime
  from public, anon, authenticated, service_role;

-- The mirror helper opens this private context only around its own legacy DML.
-- A row is transaction-scoped in practice: it is deleted on success and rolls
-- back with the surrounding statement on failure. API roles cannot forge it.
create table content_private.exhibition_catalog_legacy_write_context (
  backend_pid integer primary key,
  constraint exhibition_catalog_legacy_write_context_pid_positive check (
    backend_pid > 0
  )
);

alter table content_private.exhibition_catalog_legacy_write_context
  enable row level security;

revoke all on content_private.exhibition_catalog_legacy_write_context
  from public, anon, authenticated, service_role;

create or replace function content_private.guard_legacy_exhibitions_owner()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_legacy_writes_blocked boolean;
begin
  -- A writer can have observed its table privilege before waiting behind the
  -- activation SHARE lock. Locking the singleton makes PostgreSQL return the
  -- newly committed row under READ COMMITTED, or abort a stale higher-isolation
  -- transaction, instead of trusting the writer statement's older snapshot.
  select runtime.legacy_writes_blocked
  into v_legacy_writes_blocked
  from content_private.exhibition_catalog_runtime as runtime
  where runtime.singleton
  for share;

  if not found then
    raise exception using
      errcode = '55000',
      message = 'exhibition_catalog_runtime_invalid';
  end if;

  if coalesce(v_legacy_writes_blocked, false) and not exists (
    select 1
    from content_private.exhibition_catalog_legacy_write_context as context
    where context.backend_pid = pg_catalog.pg_backend_pid()
  ) then
    raise exception using
      errcode = '55000',
      message = 'legacy_exhibitions_managed_by_canonical';
  end if;
  return null;
end;
$function$;

create trigger guard_legacy_exhibitions_owner
  before insert or update or delete or truncate on public.exhibitions
  for each statement
  execute function content_private.guard_legacy_exhibitions_owner();

-- JSONB has a deterministic key order. Fix TimeZone to UTC before converting
-- the composite row so timestamptz output cannot depend on a caller setting.
-- Null-valued fields remain in the object; absence and null are not conflated.
create or replace function content_private.exhibition_catalog_v2_payload(
  p_row public.exhibition_catalog_v2
)
returns jsonb
language sql
stable
security invoker
set search_path = ''
set timezone = 'UTC'
as $function$
  select to_jsonb(p_row) - 'content_checksum_sha256';
$function$;

create or replace function content_private.sha256_canonical_jsonb(
  p_payload jsonb
)
returns text
language sql
immutable
security invoker
set search_path = ''
as $function$
  select encode(
    extensions.digest(
      convert_to(coalesce(p_payload, 'null'::jsonb)::text, 'UTF8'),
      'sha256'
    ),
    'hex'
  );
$function$;

create or replace function content_private.exhibition_catalog_v2_checksum(
  p_row public.exhibition_catalog_v2
)
returns text
language sql
stable
security invoker
set search_path = ''
set timezone = 'UTC'
as $function$
  select content_private.sha256_canonical_jsonb(
    content_private.exhibition_catalog_v2_payload(p_row)
  );
$function$;

create or replace function content_private.legacy_exhibition_catalog_v2_payload(
  p_row public.exhibitions
)
returns jsonb
language sql
stable
security invoker
set search_path = ''
set timezone = 'UTC'
as $function$
  select to_jsonb(p_row) || jsonb_build_object(
    'is_editors_pick', coalesce(p_row.editor_id = 'gallr-editors', false),
    'guest_editor_id', case
      when p_row.editor_id is null or p_row.editor_id = 'gallr-editors'
        then null
      else p_row.editor_id
    end
  );
$function$;

create or replace function content_private.legacy_exhibition_catalog_v2_checksum(
  p_row public.exhibitions
)
returns text
language sql
stable
security invoker
set search_path = ''
set timezone = 'UTC'
as $function$
  select content_private.sha256_canonical_jsonb(
    content_private.legacy_exhibition_catalog_v2_payload(p_row)
  );
$function$;

create or replace function content_private.set_exhibition_catalog_v2_checksum()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
set timezone = 'UTC'
as $function$
begin
  new.content_checksum_sha256 :=
    content_private.exhibition_catalog_v2_checksum(new);
  return new;
end;
$function$;

create trigger exhibition_catalog_v2_derive_checksum
  before insert or update on public.exhibition_catalog_v2
  for each row
  execute function content_private.set_exhibition_catalog_v2_checksum();

create or replace function content_private.mirror_exhibition_catalog_v2_to_legacy(
  p_exhibition_id text
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  if not coalesce(
    (
      select runtime.legacy_mirror_enabled
      from content_private.exhibition_catalog_runtime as runtime
      where runtime.singleton
    ),
    false
  ) then
    return;
  end if;

  insert into content_private.exhibition_catalog_legacy_write_context (
    backend_pid
  ) values (
    pg_catalog.pg_backend_pid()
  );

  if not exists (
    select 1
    from public.exhibition_catalog_v2 as catalog
    where catalog.id = p_exhibition_id
  ) then
    delete from public.exhibitions as legacy
    where legacy.id = p_exhibition_id;
    delete from content_private.exhibition_catalog_legacy_write_context
    where backend_pid = pg_catalog.pg_backend_pid();
    return;
  end if;

  insert into public.exhibitions as legacy (
    id,
    name_ko,
    venue_name_ko,
    city_ko,
    region_ko,
    opening_date,
    closing_date,
    is_featured,
    latitude,
    longitude,
    description_ko,
    cover_image_url,
    updated_at,
    name_en,
    venue_name_en,
    city_en,
    region_en,
    description_en,
    address_ko,
    address_en,
    hours,
    contact,
    reception_date,
    opening_time,
    event_id,
    is_homepage_featured,
    editor_id,
    ticket_url
  )
  select
    catalog.id,
    catalog.name_ko,
    catalog.venue_name_ko,
    catalog.city_ko,
    catalog.region_ko,
    catalog.opening_date,
    catalog.closing_date,
    catalog.is_featured,
    catalog.latitude,
    catalog.longitude,
    catalog.description_ko,
    catalog.cover_image_url,
    catalog.updated_at,
    catalog.name_en,
    catalog.venue_name_en,
    catalog.city_en,
    catalog.region_en,
    catalog.description_en,
    catalog.address_ko,
    catalog.address_en,
    catalog.hours,
    catalog.contact,
    catalog.reception_date,
    catalog.opening_time,
    catalog.event_id,
    catalog.is_homepage_featured,
    catalog.editor_id,
    catalog.ticket_url
  from public.exhibition_catalog_v2 as catalog
  where catalog.id = p_exhibition_id
  on conflict (id) do update
  set
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
    event_id = excluded.event_id,
    is_homepage_featured = excluded.is_homepage_featured,
    editor_id = excluded.editor_id,
    ticket_url = excluded.ticket_url;

  delete from content_private.exhibition_catalog_legacy_write_context
  where backend_pid = pg_catalog.pg_backend_pid();
exception
  when others then
    raise;
end;
$function$;

-- This function is the single canonical flattening query used by backfill,
-- trigger refresh, and reconciliation. A null ID returns the complete catalog.
create or replace function content_private.exhibition_catalog_v2_source(
  p_exhibition_id text
)
returns table (
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
  guest_editor_id text
)
language sql
stable
security definer
set search_path = ''
as $function$
  select
    exhibition.id,
    version.name_ko,
    version.name_en,
    version.venue_name_ko,
    version.venue_name_en,
    version.city_ko,
    version.city_en,
    version.region_ko,
    version.region_en,
    version.opening_date,
    version.closing_date,
    coalesce(app_featured.enabled, version.is_featured) as is_featured,
    version.latitude,
    version.longitude,
    version.description_ko,
    version.description_en,
    version.address_ko,
    version.address_en,
    coalesce(cover.public_url, version.legacy_cover_image_url) as cover_image_url,
    version.hours,
    version.contact,
    version.reception_date,
    version.opening_time,
    version.event_id,
    version.editor_id,
    coalesce(homepage.enabled, version.is_homepage_featured)
      as is_homepage_featured,
    version.ticket_url,
    coalesce(version.legacy_source_updated_at, version.updated_at) as updated_at,
    coalesce(version.editor_id = 'gallr-editors', false) as is_editors_pick,
    case
      when version.editor_id is distinct from 'gallr-editors'
        then version.editor_id
      else null
    end as guest_editor_id
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.exhibition_id = exhibition.id
   and version.id = exhibition.published_version_id
   and version.status = 'published'::content.exhibition_version_status
  left join content.curation_placements as app_featured
    on app_featured.exhibition_id = exhibition.id
   and app_featured.surface = 'app_featured'::content.curation_surface
  left join content.curation_placements as homepage
    on homepage.exhibition_id = exhibition.id
   and homepage.surface = 'homepage'::content.curation_surface
  left join lateral (
    select asset.public_url
    from content.exhibition_version_media as attachment
    join content.media_assets as asset on asset.id = attachment.media_id
    where attachment.version_id = version.id
      and attachment.role = 'cover'::content.media_role
      and asset.status = 'published'::content.media_asset_status
      and asset.purged_at is null
    order by attachment.sort_order, attachment.created_at, attachment.media_id
    limit 1
  ) as cover on true
  where exhibition.archived_at is null
    and (p_exhibition_id is null or exhibition.id = p_exhibition_id)
  order by exhibition.id;
$function$;

-- Idempotently replace one flattened row or remove it when the canonical
-- identity is no longer both published and unarchived. The checksum trigger
-- derives excluded.content_checksum_sha256 before conflict resolution.
create or replace function content_private.refresh_exhibition_catalog_v2(
  p_exhibition_id text
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_source record;
begin
  if p_exhibition_id is null or length(btrim(p_exhibition_id)) = 0 then
    raise exception using
      errcode = '22023',
      message = 'exhibition_id_is_required';
  end if;

  -- Every projector takes the global control lock first. The legacy row, when
  -- canonical owns it, precedes the per-ID projector lock; the ID lock still
  -- makes the source snapshot current after any earlier same-ID writer commits.
  -- The global lock lets cutover stop all projectors without a lost-update window.
  perform pg_catalog.pg_advisory_xact_lock_shared(73241, 1);
  -- Once canonical owns the compatibility table, take its row before the
  -- per-ID projector lock and before reading or writing V2. All mirror paths
  -- therefore use legacy -> projector -> V2 ordering.
  if coalesce(
    (
      select runtime.legacy_mirror_enabled
      from content_private.exhibition_catalog_runtime as runtime
      where runtime.singleton
    ),
    false
  ) then
    perform 1
    from public.exhibitions as legacy
    where legacy.id = p_exhibition_id
    for update;
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    73242,
    pg_catalog.hashtext(p_exhibition_id)
  );

  select *
  into v_source
  from content_private.exhibition_catalog_v2_source(p_exhibition_id);

  if not found then
    delete from public.exhibition_catalog_v2 as catalog
    where catalog.id = p_exhibition_id;
    perform content_private.mirror_exhibition_catalog_v2_to_legacy(
      p_exhibition_id
    );
    return;
  end if;

  insert into public.exhibition_catalog_v2 as catalog (
    id,
    name_ko,
    name_en,
    venue_name_ko,
    venue_name_en,
    city_ko,
    city_en,
    region_ko,
    region_en,
    opening_date,
    closing_date,
    is_featured,
    latitude,
    longitude,
    description_ko,
    description_en,
    address_ko,
    address_en,
    cover_image_url,
    hours,
    contact,
    reception_date,
    opening_time,
    event_id,
    editor_id,
    is_homepage_featured,
    ticket_url,
    updated_at,
    is_editors_pick,
    guest_editor_id
  ) values (
    v_source.id,
    v_source.name_ko,
    v_source.name_en,
    v_source.venue_name_ko,
    v_source.venue_name_en,
    v_source.city_ko,
    v_source.city_en,
    v_source.region_ko,
    v_source.region_en,
    v_source.opening_date,
    v_source.closing_date,
    v_source.is_featured,
    v_source.latitude,
    v_source.longitude,
    v_source.description_ko,
    v_source.description_en,
    v_source.address_ko,
    v_source.address_en,
    v_source.cover_image_url,
    v_source.hours,
    v_source.contact,
    v_source.reception_date,
    v_source.opening_time,
    v_source.event_id,
    v_source.editor_id,
    v_source.is_homepage_featured,
    v_source.ticket_url,
    v_source.updated_at,
    v_source.is_editors_pick,
    v_source.guest_editor_id
  )
  on conflict (id) do update
  set
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
    guest_editor_id = excluded.guest_editor_id
  where catalog.content_checksum_sha256
    is distinct from excluded.content_checksum_sha256;

  perform content_private.mirror_exhibition_catalog_v2_to_legacy(
    p_exhibition_id
  );
end;
$function$;

-- Source trigger functions gather both OLD and NEW identities where applicable,
-- de-duplicate them, and refresh in database ID order for deterministic locks.
create or replace function content_private.sync_catalog_v2_from_exhibition()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_exhibition_id text;
begin
  v_exhibition_id := case when tg_op = 'DELETE' then old.id else new.id end;
  perform content_private.refresh_exhibition_catalog_v2(v_exhibition_id);
  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$function$;

create or replace function content_private.sync_catalog_v2_from_version()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_old_exhibition_id text;
  v_old_version_id uuid;
  v_old_status content.exhibition_version_status;
  v_new_exhibition_id text;
  v_new_version_id uuid;
  v_new_status content.exhibition_version_status;
  v_exhibition_id text;
begin
  if tg_op <> 'INSERT' then
    v_old_exhibition_id := old.exhibition_id;
    v_old_version_id := old.id;
    v_old_status := old.status;
  end if;
  if tg_op <> 'DELETE' then
    v_new_exhibition_id := new.exhibition_id;
    v_new_version_id := new.id;
    v_new_status := new.status;
  end if;

  for v_exhibition_id in
    select distinct candidate.exhibition_id
    from (
      values
        (v_old_exhibition_id, v_old_version_id, v_old_status),
        (v_new_exhibition_id, v_new_version_id, v_new_status)
    ) as candidate(exhibition_id, version_id, status)
    left join content.exhibitions as exhibition
      on exhibition.id = candidate.exhibition_id
    where candidate.exhibition_id is not null
      and (
        candidate.status = 'published'::content.exhibition_version_status
        or exhibition.published_version_id = candidate.version_id
      )
    order by candidate.exhibition_id
  loop
    perform content_private.refresh_exhibition_catalog_v2(v_exhibition_id);
  end loop;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$function$;

create or replace function content_private.sync_catalog_v2_from_curation()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_old_exhibition_id text;
  v_new_exhibition_id text;
  v_exhibition_id text;
begin
  if tg_op <> 'INSERT' then
    v_old_exhibition_id := old.exhibition_id;
  end if;
  if tg_op <> 'DELETE' then
    v_new_exhibition_id := new.exhibition_id;
  end if;

  for v_exhibition_id in
    select distinct candidate.exhibition_id
    from (values (v_old_exhibition_id), (v_new_exhibition_id))
      as candidate(exhibition_id)
    where candidate.exhibition_id is not null
    order by candidate.exhibition_id
  loop
    perform content_private.refresh_exhibition_catalog_v2(v_exhibition_id);
  end loop;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$function$;

create or replace function content_private.sync_catalog_v2_from_attachment()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_old_version_id uuid;
  v_old_role content.media_role;
  v_new_version_id uuid;
  v_new_role content.media_role;
  v_exhibition_id text;
begin
  if tg_op <> 'INSERT' then
    v_old_version_id := old.version_id;
    v_old_role := old.role;
  end if;
  if tg_op <> 'DELETE' then
    v_new_version_id := new.version_id;
    v_new_role := new.role;
  end if;

  for v_exhibition_id in
    select distinct exhibition.id
    from (
      values
        (v_old_version_id, v_old_role),
        (v_new_version_id, v_new_role)
    ) as candidate(version_id, role)
    join content.exhibitions as exhibition
      on exhibition.published_version_id = candidate.version_id
    where candidate.version_id is not null
      and candidate.role = 'cover'::content.media_role
    order by exhibition.id
  loop
    perform content_private.refresh_exhibition_catalog_v2(v_exhibition_id);
  end loop;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$function$;

create or replace function content_private.sync_catalog_v2_from_media_asset()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_exhibition_id text;
begin
  for v_exhibition_id in
    select distinct exhibition.id
    from content.exhibitions as exhibition
    join content.exhibition_version_media as attachment
      on attachment.version_id = exhibition.published_version_id
     and attachment.role = 'cover'::content.media_role
    where attachment.media_id in (old.id, new.id)
    order by exhibition.id
  loop
    perform content_private.refresh_exhibition_catalog_v2(v_exhibition_id);
  end loop;

  return new;
end;
$function$;

create trigger exhibition_catalog_v2_exhibition_insert_delete
  after insert or delete on content.exhibitions
  for each row
  execute function content_private.sync_catalog_v2_from_exhibition();
create trigger exhibition_catalog_v2_exhibition_visibility_update
  after update of published_version_id, archived_at on content.exhibitions
  for each row
  when (
    old.published_version_id is distinct from new.published_version_id
    or old.archived_at is distinct from new.archived_at
  )
  execute function content_private.sync_catalog_v2_from_exhibition();

create trigger exhibition_catalog_v2_version_change
  after insert or update or delete on content.exhibition_versions
  for each row
  execute function content_private.sync_catalog_v2_from_version();

create trigger exhibition_catalog_v2_curation_insert_delete
  after insert or delete on content.curation_placements
  for each row
  execute function content_private.sync_catalog_v2_from_curation();
create trigger exhibition_catalog_v2_curation_update
  after update of exhibition_id, surface, enabled on content.curation_placements
  for each row
  when (
    old.exhibition_id is distinct from new.exhibition_id
    or old.surface is distinct from new.surface
    or old.enabled is distinct from new.enabled
  )
  execute function content_private.sync_catalog_v2_from_curation();

create trigger exhibition_catalog_v2_attachment_insert_delete
  after insert or delete on content.exhibition_version_media
  for each row
  execute function content_private.sync_catalog_v2_from_attachment();
create trigger exhibition_catalog_v2_attachment_update
  after update of version_id, media_id, role, sort_order
  on content.exhibition_version_media
  for each row
  when (
    old.version_id is distinct from new.version_id
    or old.media_id is distinct from new.media_id
    or old.role is distinct from new.role
    or old.sort_order is distinct from new.sort_order
  )
  execute function content_private.sync_catalog_v2_from_attachment();

create trigger exhibition_catalog_v2_media_asset_update
  after update of id, status, public_url, purged_at on content.media_assets
  for each row
  when (
    old.id is distinct from new.id
    or old.status is distinct from new.status
    or old.public_url is distinct from new.public_url
    or old.purged_at is distinct from new.purged_at
  )
  execute function content_private.sync_catalog_v2_from_media_asset();

-- Install all source triggers before taking the initial snapshot. The migration
-- transaction's DDL locks prevent a writer from committing between the snapshot
-- and trigger installation.
insert into public.exhibition_catalog_v2 (
  id,
  name_ko,
  name_en,
  venue_name_ko,
  venue_name_en,
  city_ko,
  city_en,
  region_ko,
  region_en,
  opening_date,
  closing_date,
  is_featured,
  latitude,
  longitude,
  description_ko,
  description_en,
  address_ko,
  address_en,
  cover_image_url,
  hours,
  contact,
  reception_date,
  opening_time,
  event_id,
  editor_id,
  is_homepage_featured,
  ticket_url,
  updated_at,
  is_editors_pick,
  guest_editor_id
)
select
  source.id,
  source.name_ko,
  source.name_en,
  source.venue_name_ko,
  source.venue_name_en,
  source.city_ko,
  source.city_en,
  source.region_ko,
  source.region_en,
  source.opening_date,
  source.closing_date,
  source.is_featured,
  source.latitude,
  source.longitude,
  source.description_ko,
  source.description_en,
  source.address_ko,
  source.address_en,
  source.cover_image_url,
  source.hours,
  source.contact,
  source.reception_date,
  source.opening_time,
  source.event_id,
  source.editor_id,
  source.is_homepage_featured,
  source.ticket_url,
  source.updated_at,
  source.is_editors_pick,
  source.guest_editor_id
from content_private.exhibition_catalog_v2_source(null) as source
order by source.id;

-- Reader integrity extends the existing ID-only contract with a framed digest
-- of every row's content checksum. Frames are unambiguous for arbitrary UTF-8
-- IDs and remain ordered by the same database ID cursor used for pagination.
create or replace function public.exhibition_catalog_v2_integrity(
  p_event_id text default null,
  p_featured_only boolean default false
)
returns table (
  row_count bigint,
  id_checksum_sha256 text,
  catalog_checksum_sha256 text
)
language sql
stable
security invoker
set search_path = ''
as $function$
  with scoped as (
    select catalog.id, catalog.content_checksum_sha256
    from public.exhibition_catalog_v2 as catalog
    where (p_event_id is null or catalog.event_id = p_event_id)
      and (
        not coalesce(p_featured_only, false)
        or catalog.is_featured = true
      )
  )
  select
    count(*)::bigint,
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(convert_to(scoped.id, 'UTF8'))::text
                || ':' || scoped.id,
              '' order by scoped.id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    ),
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(convert_to(scoped.id, 'UTF8'))::text
                || ':' || scoped.id
                || octet_length(
                  convert_to(scoped.content_checksum_sha256, 'UTF8')
                )::text
                || ':' || scoped.content_checksum_sha256,
              '' order by scoped.id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    )
  from scoped;
$function$;

comment on function public.exhibition_catalog_v2_integrity(text, boolean) is
  'Returns a single-snapshot count, ID checksum, and framed catalog-content checksum for v2 public exhibition readers.';

revoke all
  on function public.exhibition_catalog_v2_integrity(text, boolean)
  from public, anon, authenticated, service_role;
grant execute
  on function public.exhibition_catalog_v2_integrity(text, boolean)
  to anon, authenticated, service_role;

-- Service-only drift report. The source payload has exactly the public preview
-- field set; the stored checksum is also independently re-derived so corruption
-- cannot be hidden by matching business fields.
create or replace function public.admin_reconcile_exhibition_catalog_v2()
returns jsonb
language sql
stable
security definer
set search_path = ''
set timezone = 'UTC'
as $function$
  with canonical_rows as (
    select
      source.id,
      to_jsonb(source) as payload,
      content_private.sha256_canonical_jsonb(to_jsonb(source))
        as expected_content_checksum_sha256
    from content_private.exhibition_catalog_v2_source(null) as source
  ),
  projection_rows as (
    select
      catalog.id,
      content_private.exhibition_catalog_v2_payload(catalog) as payload,
      catalog.content_checksum_sha256,
      content_private.exhibition_catalog_v2_checksum(catalog)
        as derived_content_checksum_sha256
    from public.exhibition_catalog_v2 as catalog
  ),
  comparison as (
    select
      coalesce(canonical.id, projection.id) as id,
      canonical.payload as canonical_payload,
      projection.payload as projection_payload,
      canonical.expected_content_checksum_sha256,
      projection.content_checksum_sha256,
      projection.derived_content_checksum_sha256,
      case
        when canonical.id is null then 'only_in_projection'
        when projection.id is null then 'only_in_canonical'
        when canonical.payload is distinct from projection.payload
          then 'field_mismatch'
        when projection.content_checksum_sha256
          is distinct from canonical.expected_content_checksum_sha256
          or projection.content_checksum_sha256
            is distinct from projection.derived_content_checksum_sha256
          then 'checksum_mismatch'
        else 'match'
      end as status
    from canonical_rows as canonical
    full join projection_rows as projection using (id)
  ),
  differences as (
    select
      comparison.id,
      comparison.status,
      comparison.expected_content_checksum_sha256,
      comparison.content_checksum_sha256,
      comparison.derived_content_checksum_sha256,
      case
        when comparison.status <> 'field_mismatch' then '[]'::jsonb
        else coalesce(
          (
            select jsonb_agg(field.key order by field.key)
            from (
              select coalesce(canonical_field.key, projection_field.key) as key
              from jsonb_each(comparison.canonical_payload)
                as canonical_field(key, value)
              full join jsonb_each(comparison.projection_payload)
                as projection_field(key, value) using (key)
              where canonical_field.value is distinct from projection_field.value
            ) as field
          ),
          '[]'::jsonb
        )
      end as differing_fields
    from comparison
    where comparison.status <> 'match'
  ),
  reported_differences as (
    select *
    from differences
    order by id
    limit 100
  )
  select jsonb_build_object(
    'schema_version', 1,
    'in_sync', not exists (select 1 from differences),
    'canonical_count', (select count(*) from canonical_rows),
    'projection_count', (select count(*) from projection_rows),
    'matching_count', (select count(*) from comparison where status = 'match'),
    'difference_count', (select count(*) from differences),
    'missing_count', (
      select count(*) from differences where status = 'only_in_canonical'
    ),
    'unexpected_count', (
      select count(*) from differences where status = 'only_in_projection'
    ),
    'mismatched_count', (
      select count(*)
      from differences
      where status in ('field_mismatch', 'checksum_mismatch')
    ),
    'reported_difference_count', (select count(*) from reported_differences),
    'truncated', (select count(*) from differences) > 100,
    'differences', coalesce(
      (
        select jsonb_agg(to_jsonb(reported_differences) order by id)
        from reported_differences
      ),
      '[]'::jsonb
    )
  );
$function$;

comment on function public.admin_reconcile_exhibition_catalog_v2() is
  'Service-role-only field and checksum reconciliation between canonical published content and exhibition_catalog_v2.';

revoke all on function public.admin_reconcile_exhibition_catalog_v2()
  from public, anon, authenticated, service_role;
grant execute on function public.admin_reconcile_exhibition_catalog_v2()
  to service_role;

-- Final-cutover bridge for installed legacy clients. The operator must first
-- disable the Apps Script writer and supply the exact, previously recorded V2
-- snapshot. Activation requires field-for-field parity, blocks queued legacy
-- writers with table locks, revokes their DML, and then enables dual projection.
create or replace function public.admin_enable_legacy_exhibition_mirror(
  p_expected_row_count bigint,
  p_expected_id_checksum_sha256 text,
  p_expected_catalog_checksum_sha256 text,
  p_reason text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_v2 record;
  v_legacy record;
  v_legacy_catalog_checksum_sha256 text;
  v_legacy_payloads_match boolean;
  v_reconciliation jsonb;
  v_runtime_row_count bigint;
begin
  if p_expected_row_count is null or p_expected_row_count < 0 then
    raise exception using errcode = '22023', message = 'expected_row_count_is_invalid';
  end if;
  if lower(btrim(coalesce(p_expected_id_checksum_sha256, '')))
      !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '22023', message = 'expected_id_checksum_is_invalid';
  end if;
  if lower(btrim(coalesce(p_expected_catalog_checksum_sha256, '')))
      !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '22023', message = 'expected_catalog_checksum_is_invalid';
  end if;
  if p_reason is null or length(btrim(p_reason)) = 0 then
    raise exception using errcode = '22023', message = 'legacy_mirror_reason_is_required';
  end if;

  -- Exclusive form of the lock every projector takes in shared mode. No source
  -- refresh can cross the validation/enable boundary. Legacy is locked before
  -- V2 so a queued Apps Script DELETE/POST cannot commit after ownership moves.
  perform pg_catalog.pg_advisory_xact_lock(73241, 1);
  lock table public.exhibitions in share mode;
  lock table public.exhibition_catalog_v2 in share mode;

  select * into strict v_v2
  from public.exhibition_catalog_v2_integrity(null, false);
  select * into strict v_legacy
  from public.exhibition_reader_integrity(null, false);
  select encode(
    extensions.digest(
      convert_to(
        coalesce(
          string_agg(
            octet_length(convert_to(legacy.id, 'UTF8'))::text
              || ':' || legacy.id
              || octet_length(
                convert_to(
                  content_private.legacy_exhibition_catalog_v2_checksum(legacy),
                  'UTF8'
                )
              )::text
              || ':'
              || content_private.legacy_exhibition_catalog_v2_checksum(legacy),
            '' order by legacy.id
          ),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  )
  into v_legacy_catalog_checksum_sha256
  from public.exhibitions as legacy;

  select not exists (
    select 1
    from public.exhibition_catalog_v2 as catalog
    full join public.exhibitions as legacy using (id)
    where catalog.id is null
      or legacy.id is null
      or content_private.exhibition_catalog_v2_payload(catalog)
        is distinct from
          content_private.legacy_exhibition_catalog_v2_payload(legacy)
  ) into v_legacy_payloads_match;
  v_reconciliation := public.admin_reconcile_exhibition_catalog_v2();

  if not coalesce((v_reconciliation ->> 'in_sync')::boolean, false)
      or v_v2.row_count is distinct from p_expected_row_count
      or v_v2.id_checksum_sha256 is distinct from
        lower(btrim(p_expected_id_checksum_sha256))
      or v_v2.catalog_checksum_sha256 is distinct from
        lower(btrim(p_expected_catalog_checksum_sha256))
      or v_legacy.row_count is distinct from v_v2.row_count
      or v_legacy.id_checksum_sha256 is distinct from v_v2.id_checksum_sha256
      or v_legacy_catalog_checksum_sha256 is distinct from
        v_v2.catalog_checksum_sha256
      or not coalesce(v_legacy_payloads_match, false) then
    raise exception using
      errcode = '40001',
      message = 'legacy_mirror_precondition_failed',
      detail = jsonb_build_object(
        'expected_row_count', p_expected_row_count,
        'expected_id_checksum_sha256', lower(btrim(p_expected_id_checksum_sha256)),
        'expected_catalog_checksum_sha256',
          lower(btrim(p_expected_catalog_checksum_sha256)),
        'v2_row_count', v_v2.row_count,
        'v2_id_checksum_sha256', v_v2.id_checksum_sha256,
        'v2_catalog_checksum_sha256', v_v2.catalog_checksum_sha256,
        'legacy_row_count', v_legacy.row_count,
        'legacy_id_checksum_sha256', v_legacy.id_checksum_sha256,
        'legacy_catalog_checksum_sha256', v_legacy_catalog_checksum_sha256,
        'legacy_payloads_match', v_legacy_payloads_match,
        'reconciliation', v_reconciliation
      )::text;
  end if;

  update content_private.exhibition_catalog_runtime as runtime
  set
    legacy_mirror_enabled = true,
    legacy_writes_blocked = true,
    legacy_mirror_enabled_at = coalesce(runtime.legacy_mirror_enabled_at, now()),
    baseline_row_count = v_v2.row_count,
    baseline_id_checksum_sha256 = v_v2.id_checksum_sha256,
    baseline_catalog_checksum_sha256 = v_v2.catalog_checksum_sha256,
    reason = btrim(p_reason)
  where runtime.singleton;

  get diagnostics v_runtime_row_count = row_count;
  if v_runtime_row_count <> 1 then
    raise exception using
      errcode = '55000',
      message = 'exhibition_catalog_runtime_invalid';
  end if;

  execute
    'revoke insert, update, delete, truncate on public.exhibitions from service_role';

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  ) values (
    null,
    'legacy_exhibition_mirror.enabled',
    'system_setting',
    'legacy_exhibition_mirror',
    jsonb_build_object(
      'reason', btrim(p_reason),
      'legacy_writes_blocked', true,
      'row_count', v_v2.row_count,
      'id_checksum_sha256', v_v2.id_checksum_sha256,
      'catalog_checksum_sha256', v_v2.catalog_checksum_sha256
    )
  );

  return jsonb_build_object(
    'legacy_mirror_enabled', true,
    'legacy_writes_blocked', true,
    'row_count', v_v2.row_count,
    'id_checksum_sha256', v_v2.id_checksum_sha256,
    'catalog_checksum_sha256', v_v2.catalog_checksum_sha256,
    'reason', btrim(p_reason)
  );
end;
$function$;

comment on function public.admin_enable_legacy_exhibition_mirror(bigint, text, text, text) is
  'Service-role final-cutover command: after exact field and snapshot parity, revokes legacy DML and keeps public.exhibitions transactionally synchronized from canonical V2.';

create or replace function public.admin_disable_legacy_exhibition_mirror(
  p_reason text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_runtime_row_count bigint;
begin
  if p_reason is null or length(btrim(p_reason)) = 0 then
    raise exception using errcode = '22023', message = 'legacy_mirror_reason_is_required';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(73241, 1);
  lock table public.exhibitions in share mode;
  lock table public.exhibition_catalog_v2 in share mode;

  execute
    'revoke insert, update, delete, truncate on public.exhibitions from service_role';

  update content_private.exhibition_catalog_runtime as runtime
  set
    legacy_mirror_enabled = false,
    legacy_writes_blocked = true,
    legacy_mirror_enabled_at = null,
    reason = btrim(p_reason)
  where runtime.singleton;

  get diagnostics v_runtime_row_count = row_count;
  if v_runtime_row_count <> 1 then
    raise exception using
      errcode = '55000',
      message = 'exhibition_catalog_runtime_invalid';
  end if;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  ) values (
    null,
    'legacy_exhibition_mirror.disabled',
    'system_setting',
    'legacy_exhibition_mirror',
    jsonb_build_object(
      'reason', btrim(p_reason),
      'legacy_writes_blocked', true,
      'legacy_dml_remains_revoked', true
    )
  );

  return jsonb_build_object(
    'legacy_mirror_enabled', false,
    'legacy_writes_blocked', true,
    'reason', btrim(p_reason),
    'legacy_dml_remains_revoked', true
  );
end;
$function$;

comment on function public.admin_disable_legacy_exhibition_mirror(text) is
  'Service-role freeze command: stops canonical mirroring, keeps the ownership guard active, and idempotently revokes legacy DML until a separately reconciled Sheet-writer resumption.';

revoke all
  on function public.admin_enable_legacy_exhibition_mirror(bigint, text, text, text),
  public.admin_disable_legacy_exhibition_mirror(text)
  from public, anon, authenticated, service_role;
grant execute
  on function public.admin_enable_legacy_exhibition_mirror(bigint, text, text, text),
  public.admin_disable_legacy_exhibition_mirror(text)
  to service_role;

-- New functions otherwise inherit EXECUTE for PUBLIC. Keep every private helper
-- and trigger function owner-only. Public reader/reconciliation/control RPCs
-- are granted explicitly above.
revoke all
  on function content_private.guard_legacy_exhibitions_owner(),
  content_private.exhibition_catalog_v2_payload(public.exhibition_catalog_v2),
  content_private.sha256_canonical_jsonb(jsonb),
  content_private.exhibition_catalog_v2_checksum(public.exhibition_catalog_v2),
  content_private.legacy_exhibition_catalog_v2_payload(public.exhibitions),
  content_private.legacy_exhibition_catalog_v2_checksum(public.exhibitions),
  content_private.set_exhibition_catalog_v2_checksum(),
  content_private.mirror_exhibition_catalog_v2_to_legacy(text),
  content_private.exhibition_catalog_v2_source(text),
  content_private.refresh_exhibition_catalog_v2(text),
  content_private.sync_catalog_v2_from_exhibition(),
  content_private.sync_catalog_v2_from_version(),
  content_private.sync_catalog_v2_from_curation(),
  content_private.sync_catalog_v2_from_attachment(),
  content_private.sync_catalog_v2_from_media_asset()
  from public, anon, authenticated, service_role;
