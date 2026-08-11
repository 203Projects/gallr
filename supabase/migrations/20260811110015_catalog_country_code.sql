-- Add explicit country identity to the canonical and compatibility catalogs.
-- The version follows the editor migrations already recorded in production.
-- Korea remains the default while country is carried as immutable versioned
-- content so future city names do not need to imply their country.

alter table content.venues
  add column if not exists country_code text not null default 'KR';

alter table content.exhibition_versions
  add column if not exists country_code text not null default 'KR';

alter table public.exhibition_catalog_v2
  add column if not exists country_code text not null default 'KR';

alter table public.exhibitions
  add column if not exists country_code text not null default 'KR';

do $country_code_constraints$
declare
  v_relation regclass;
  v_name text;
begin
  for v_relation, v_name in
    values
      ('content.venues'::regclass, 'venues_country_code_format'),
      ('content.exhibition_versions'::regclass, 'exhibition_versions_country_code_format'),
      ('public.exhibition_catalog_v2'::regclass, 'exhibition_catalog_v2_country_code_format'),
      ('public.exhibitions'::regclass, 'exhibitions_country_code_format')
  loop
    if not exists (
      select 1
      from pg_catalog.pg_constraint as constraint_record
      where constraint_record.conrelid = v_relation
        and constraint_record.conname = v_name
    ) then
      execute pg_catalog.format(
        'alter table %s add constraint %I check (country_code ~ ''^[A-Z]{2}$'')',
        v_relation,
        v_name
      );
    end if;
  end loop;
end;
$country_code_constraints$;

-- Exhibition versions are immutable venue snapshots. On insert, copy the
-- canonical venue country; later venue edits deliberately do not propagate.
create or replace function content_private.snapshot_exhibition_version_country()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_country_code text;
begin
  if tg_op = 'INSERT' and new.venue_id is not null then
    select venue.country_code
    into v_country_code
    from content.venues as venue
    where venue.id = new.venue_id;

    if found then
      new.country_code := v_country_code;
    end if;
  end if;
  return new;
end;
$function$;

drop trigger if exists exhibition_versions_snapshot_country
  on content.exhibition_versions;
create trigger exhibition_versions_snapshot_country
  before insert on content.exhibition_versions
  for each row
  execute function content_private.snapshot_exhibition_version_country();

-- The established catalog projector predates country_code. Derive it from the
-- selected immutable published version before the checksum trigger runs. This
-- keeps every existing refresh path current without replacing its large,
-- security-sensitive projection function.
create or replace function content_private.derive_catalog_v2_country()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_country_code text;
begin
  select version.country_code
  into v_country_code
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.id = exhibition.published_version_id
  where exhibition.id = new.id
    and exhibition.archived_at is null;

  if found then
    new.country_code := v_country_code;
  end if;
  return new;
end;
$function$;

drop trigger if exists aa_exhibition_catalog_v2_derive_country
  on public.exhibition_catalog_v2;
create trigger aa_exhibition_catalog_v2_derive_country
  before insert or update on public.exhibition_catalog_v2
  for each row
  execute function content_private.derive_catalog_v2_country();

-- Extend canonical reconciliation without changing the return type of the
-- older projector function used by transactional refreshes.
create or replace function content_private.exhibition_catalog_v2_source_payload(
  p_exhibition_id text
)
returns table (
  id text,
  payload jsonb
)
language sql
stable
security definer
set search_path = ''
set timezone = 'UTC'
as $function$
  select
    source.id,
    to_jsonb(source) || jsonb_build_object(
      'credits_ko', version.credits_ko,
      'credits_en', version.credits_en,
      'country_code', version.country_code
    ) as payload
  from content_private.exhibition_catalog_v2_source(p_exhibition_id) as source
  join content.exhibitions as exhibition on exhibition.id = source.id
  join content.exhibition_versions as version
    on version.id = exhibition.published_version_id
  order by source.id;
$function$;

revoke all on function
  content_private.exhibition_catalog_v2_source_payload(text)
from public, anon, authenticated, service_role;

-- A published version can be updated by existing editorial commands. Keep its
-- flattened country snapshot aligned, just as the later credits migration does.
create or replace function content_private.sync_catalog_v2_country_from_version()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  if tg_op <> 'DELETE'
     and new.status = 'published'::content.exhibition_version_status
     and exists (
       select 1
       from content.exhibitions as exhibition
       where exhibition.id = new.exhibition_id
         and exhibition.published_version_id = new.id
         and exhibition.archived_at is null
     ) then
    update public.exhibition_catalog_v2 as catalog
    set country_code = new.country_code
    where catalog.id = new.exhibition_id
      and catalog.country_code is distinct from new.country_code;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$function$;

drop trigger if exists zz_exhibition_catalog_v2_version_country
  on content.exhibition_versions;
create trigger zz_exhibition_catalog_v2_version_country
  after insert or update or delete on content.exhibition_versions
  for each row
  execute function content_private.sync_catalog_v2_country_from_version();

-- Keep the enabled legacy compatibility mirror aligned without granting a new
-- browser write path.
create or replace function content_private.sync_legacy_exhibition_country()
returns trigger
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
    return new;
  end if;

  insert into content_private.exhibition_catalog_legacy_write_context (
    backend_pid
  ) values (
    pg_catalog.pg_backend_pid()
  ) on conflict (backend_pid) do nothing;

  update public.exhibitions as legacy
  set country_code = new.country_code
  where legacy.id = new.id;

  delete from content_private.exhibition_catalog_legacy_write_context
  where backend_pid = pg_catalog.pg_backend_pid();
  return new;
exception
  when others then
    delete from content_private.exhibition_catalog_legacy_write_context
    where backend_pid = pg_catalog.pg_backend_pid();
    raise;
end;
$function$;

drop trigger if exists exhibition_catalog_v2_mirror_country
  on public.exhibition_catalog_v2;
create trigger exhibition_catalog_v2_mirror_country
  after insert or update of country_code
  on public.exhibition_catalog_v2
  for each row
  execute function content_private.sync_legacy_exhibition_country();

-- Re-derive the country/checksum for installed projections. Existing data is
-- Korea-first; non-Korean canonical rows, if any, are copied from their version.
update public.exhibition_catalog_v2
set country_code = country_code;
