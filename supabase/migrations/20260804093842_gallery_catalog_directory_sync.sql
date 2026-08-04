-- Seed and maintain the claimable gallery directory from the canonical public
-- catalogue. A gallery is an organization; catalogue addresses and coordinates
-- are exhibition snapshots and may represent multiple branches, so this bridge
-- deliberately does not synthesize or select a canonical content.venue.

create table content.gallery_catalog_sources (
  source text not null,
  source_key text not null,
  gallery_id uuid not null
    references content.galleries(id) on delete restrict,
  last_name_ko text not null,
  last_name_en text not null default '',
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  primary key (source, source_key),
  constraint gallery_catalog_sources_source_format check (
    source ~ '^[a-z][a-z0-9_.]*$'
  ),
  constraint gallery_catalog_sources_key_not_blank check (
    length(btrim(source_key)) > 0
  ),
  constraint gallery_catalog_sources_seen_order check (
    last_seen_at >= first_seen_at
  )
);

create index gallery_catalog_sources_gallery_idx
  on content.gallery_catalog_sources (gallery_id);

alter table content.gallery_catalog_sources enable row level security;

revoke all on table content.gallery_catalog_sources
  from public, anon, authenticated, service_role;

comment on table content.gallery_catalog_sources is
  'Private, durable mapping from an external catalogue identity to a claimable gallery organization. Source disappearance never deletes the organization.';
comment on column content.gallery_catalog_sources.source_key is
  'Source-scoped normalized organization name. Multiple locations may intentionally share one key.';

create or replace function content_private.normalize_gallery_catalog_name(
  p_name text
)
returns text
language sql
immutable
strict
security invoker
set search_path = ''
as $$
  select lower(regexp_replace(btrim(p_name), '[[:space:]]+', ' ', 'g'));
$$;

revoke all on function content_private.normalize_gallery_catalog_name(text)
  from public, anon, authenticated, service_role;

create index galleries_catalog_normalized_name_idx
  on content.galleries (
    content_private.normalize_gallery_catalog_name(name_ko)
  )
  where status in (
    'pending'::content.gallery_status,
    'active'::content.gallery_status
  );

create or replace function content_private.sync_gallery_from_catalog(
  p_name_ko text,
  p_name_en text
)
returns uuid
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_source constant text := 'public.exhibition_catalog_v2';
  v_source_key text :=
    content_private.normalize_gallery_catalog_name(p_name_ko);
  v_display_name_ko text := left(
    regexp_replace(btrim(p_name_ko), '[[:space:]]+', ' ', 'g'),
    300
  );
  v_display_name_en text := left(
    regexp_replace(btrim(coalesce(p_name_en, '')), '[[:space:]]+', ' ', 'g'),
    300
  );
  v_gallery_id uuid;
begin
  if nullif(v_source_key, '') is null then
    return null;
  end if;

  -- The source key is unique, but its gallery row must be chosen first. Lock
  -- one deterministic key so concurrent catalogue writes cannot create two
  -- organizations before either transaction inserts the mapping.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(v_source || chr(31) || v_source_key, 0)
  );

  select source.gallery_id
    into v_gallery_id
  from content.gallery_catalog_sources as source
  where source.source = v_source
    and source.source_key = v_source_key
  for update;

  if v_gallery_id is not null then
    update content.gallery_catalog_sources as source
    set last_name_ko = v_display_name_ko,
        last_name_en = v_display_name_en,
        last_seen_at = now()
    where source.source = v_source
      and source.source_key = v_source_key;

    return v_gallery_id;
  end if;

  -- Reuse a manually created exact-name organization when one exists. A
  -- catalogue match can activate the organization, but it never approves a
  -- pending gallery membership; staff review remains mandatory for ownership.
  select gallery.id
    into v_gallery_id
  from content.galleries as gallery
  where content_private.normalize_gallery_catalog_name(gallery.name_ko) =
      v_source_key
    and gallery.status in (
      'pending'::content.gallery_status,
      'active'::content.gallery_status
    )
  order by
    case gallery.status
      when 'active'::content.gallery_status then 0
      else 1
    end,
    gallery.created_at,
    gallery.id
  limit 1
  for update;

  if v_gallery_id is null then
    insert into content.galleries (
      canonical_venue_id,
      name_ko,
      name_en,
      status,
      created_by,
      updated_by
    )
    values (
      null,
      v_display_name_ko,
      v_display_name_en,
      'active'::content.gallery_status,
      null,
      null
    )
    returning id into v_gallery_id;
  else
    update content.galleries as gallery
    set status = 'active'::content.gallery_status,
        name_en = case
          when nullif(btrim(gallery.name_en), '') is null
            then v_display_name_en
          else gallery.name_en
        end,
        updated_by = null
    where gallery.id = v_gallery_id
      and gallery.status = 'pending'::content.gallery_status;
  end if;

  insert into content.gallery_catalog_sources (
    source,
    source_key,
    gallery_id,
    last_name_ko,
    last_name_en
  )
  values (
    v_source,
    v_source_key,
    v_gallery_id,
    v_display_name_ko,
    v_display_name_en
  );

  return v_gallery_id;
end;
$$;

revoke all on function content_private.sync_gallery_from_catalog(text, text)
  from public, anon, authenticated, service_role;

create or replace function content_private.sync_gallery_directory_from_catalog()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  perform content_private.sync_gallery_from_catalog(
    new.venue_name_ko,
    new.venue_name_en
  );
  return new;
end;
$$;

revoke all on function content_private.sync_gallery_directory_from_catalog()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_catalog_v2_sync_gallery_directory
  on public.exhibition_catalog_v2;
create trigger exhibition_catalog_v2_sync_gallery_directory
  after insert or update of venue_name_ko, venue_name_en
  on public.exhibition_catalog_v2
  for each row
  execute function content_private.sync_gallery_directory_from_catalog();

-- Reconcile the catalogue that predates this migration. Pick the latest
-- display spelling for each normalized organization key; the sync itself is
-- idempotent and safe to run again during operational reconciliation.
do $$
declare
  v_catalog_gallery record;
begin
  for v_catalog_gallery in
    select distinct on (
      content_private.normalize_gallery_catalog_name(catalog.venue_name_ko)
    )
      catalog.venue_name_ko,
      catalog.venue_name_en
    from public.exhibition_catalog_v2 as catalog
    order by
      content_private.normalize_gallery_catalog_name(catalog.venue_name_ko),
      catalog.updated_at desc,
      catalog.id
  loop
    perform content_private.sync_gallery_from_catalog(
      v_catalog_gallery.venue_name_ko,
      v_catalog_gallery.venue_name_en
    );
  end loop;
end;
$$;
