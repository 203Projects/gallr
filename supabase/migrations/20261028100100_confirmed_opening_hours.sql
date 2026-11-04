-- Spec 101 (US5): staff-confirmed weekly opening hours.
--
-- Staff confirm a seven-day table against the version's free-text hours. The public catalogue
-- carries the table only while the published version's hours text is unchanged since that
-- confirmation; otherwise readers keep the free text and the app keeps its own parser reading.

begin;

-- == columns ==

alter table content.exhibition_versions
  add column if not exists hours_weekly jsonb,
  add column if not exists hours_confirmed_by uuid references auth.users(id) on delete set null,
  add column if not exists hours_confirmed_at timestamptz,
  add column if not exists hours_source_hash text;

do $hours_constraints$
begin
  if not exists (
    select 1 from pg_catalog.pg_constraint
    where conrelid = 'content.exhibition_versions'::regclass
      and conname = 'exhibition_versions_hours_weekly_object'
  ) then
    alter table content.exhibition_versions
      add constraint exhibition_versions_hours_weekly_object
      check (hours_weekly is null or jsonb_typeof(hours_weekly) = 'object');
  end if;
  if not exists (
    select 1 from pg_catalog.pg_constraint
    where conrelid = 'content.exhibition_versions'::regclass
      and conname = 'exhibition_versions_hours_source_hash_format'
  ) then
    alter table content.exhibition_versions
      add constraint exhibition_versions_hours_source_hash_format
      check (hours_source_hash is null or hours_source_hash ~ '^[0-9a-f]{64}$');
  end if;
  if not exists (
    select 1 from pg_catalog.pg_constraint
    where conrelid = 'content.exhibition_versions'::regclass
      and conname = 'exhibition_versions_hours_confirmation_complete'
  ) then
    alter table content.exhibition_versions
      add constraint exhibition_versions_hours_confirmation_complete
      check (
        (hours_weekly is null and hours_source_hash is null and hours_confirmed_at is null)
        or (hours_weekly is not null and hours_source_hash is not null and hours_confirmed_at is not null)
      );
  end if;
end;
$hours_constraints$;

comment on column content.exhibition_versions.hours_weekly is
  'Staff-confirmed week: seven keys MONDAY..SUNDAY, each null (closed) or {"opens":"HH:MM","closes":"HH:MM"}.';
comment on column content.exhibition_versions.hours_source_hash is
  'SHA-256 of this version''s hours text when hours_weekly was confirmed. A different hash means the week is stale.';

-- == validation, hashes and staleness ==

create or replace function content_private.hours_weekly_is_valid(p_hours_weekly jsonb)
returns boolean
language sql
immutable
security invoker
set search_path = ''
as $function$
  select coalesce(
    jsonb_typeof(p_hours_weekly) = 'object'
    and (
      select array_agg(day.key order by day.key collate "C")
      from jsonb_object_keys(p_hours_weekly) as day(key)
    ) = array['FRIDAY', 'MONDAY', 'SATURDAY', 'SUNDAY', 'THURSDAY', 'TUESDAY', 'WEDNESDAY']
    and not exists (
      select 1
      from jsonb_each(p_hours_weekly) as day(key, value)
      where not (
        jsonb_typeof(day.value) = 'null'
        or (
          jsonb_typeof(day.value) = 'object'
          and (
            select array_agg(field.key order by field.key collate "C")
            from jsonb_object_keys(day.value) as field(key)
          ) = array['closes', 'opens']
          and jsonb_typeof(day.value -> 'opens') = 'string'
          and jsonb_typeof(day.value -> 'closes') = 'string'
          and (day.value ->> 'opens') ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
          and (day.value ->> 'closes') ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
          and (day.value ->> 'opens') collate "C" < (day.value ->> 'closes') collate "C"
        )
      )
    ),
    false
  );
$function$;

create or replace function content_private.exhibition_hours_text_hash(p_hours text)
returns text
language sql
stable
security invoker
set search_path = ''
as $function$
  select encode(
    extensions.digest(convert_to(coalesce(p_hours, ''), 'UTF8'), 'sha256'),
    'hex'
  );
$function$;

-- Stale hours: a confirmed week whose hours text changed since confirmation.
create or replace function content_private.exhibition_hours_is_stale(p_version_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
  select coalesce(
    (
      select version.hours_weekly is not null
        and version.hours_source_hash is distinct from
          content_private.exhibition_hours_text_hash(version.hours)
      from content.exhibition_versions as version
      where version.id = p_version_id
    ),
    false
  );
$function$;

-- The week readers may see for one version: only while it is fresh.
create or replace function content_private.exhibition_public_hours_weekly(p_version_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  select case
    when version.hours_weekly is not null
      and version.hours_source_hash = content_private.exhibition_hours_text_hash(version.hours)
      then version.hours_weekly
  end
  from content.exhibition_versions as version
  where version.id = p_version_id;
$function$;

revoke all on function
  content_private.hours_weekly_is_valid(jsonb),
  content_private.exhibition_hours_text_hash(text),
  content_private.exhibition_hours_is_stale(uuid),
  content_private.exhibition_public_hours_weekly(uuid)
from public, anon, authenticated, service_role;

-- == carrying provenance to the next version (now with hours) ==

create or replace function content_private.carry_exhibition_version_provenance()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_source content.exhibition_versions%rowtype;
begin
  if new.version_number <= 1 then
    return new;
  end if;

  select version.*
  into v_source
  from content.exhibition_versions as version
  where version.exhibition_id = new.exhibition_id
    and version.version_number < new.version_number
  order by version.version_number desc
  limit 1;

  if not found then
    return new;
  end if;

  if new.metadata_source_hash is null and new.metadata_reviewed_at is null then
    new.metadata_source_hash := v_source.metadata_source_hash;
    new.metadata_reviewed_at := v_source.metadata_reviewed_at;
  end if;

  if new.hours_weekly is null
     and new.hours_source_hash is null
     and new.hours_confirmed_at is null then
    new.hours_weekly := v_source.hours_weekly;
    new.hours_confirmed_by := v_source.hours_confirmed_by;
    new.hours_confirmed_at := v_source.hours_confirmed_at;
    new.hours_source_hash := v_source.hours_source_hash;
  end if;

  return new;
end;
$function$;

revoke all on function content_private.carry_exhibition_version_provenance()
  from public, anon, authenticated, service_role;

-- == staff responses ==

create or replace function content_private.admin_exhibition_json(
  p_exhibition_id text,
  p_version_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  select base.payload || jsonb_build_object(
    'artists', content_private.exhibition_artists_staff_json(version.id),
    'art_term_grounding', content_private.exhibition_art_term_grounding_json(version.id),
    'metadata_stale', content_private.exhibition_metadata_is_stale(version.id),
    'metadata_reviewed_at', version.metadata_reviewed_at,
    'hours_weekly', version.hours_weekly,
    'hours_confirmed_at', version.hours_confirmed_at,
    'hours_stale', content_private.exhibition_hours_is_stale(version.id)
  )
  from (
    select content_private.admin_exhibition_json_without_provenance(
      p_exhibition_id,
      p_version_id
    ) as payload
  ) as base
  join content.exhibition_versions as version on version.id = p_version_id
  where base.payload is not null;
$function$;

revoke all on function content_private.admin_exhibition_json(text, uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_list_stale_exhibition_metadata_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  perform content_private.admin_assert_staff('contributor'::content.staff_role);

  return (
    with candidates as (
      select
        exhibition.id,
        coalesce(draft.name_ko, published.name_ko, '') as name_ko,
        coalesce(draft.name_en, published.name_en, '') as name_en,
        greatest(exhibition.updated_at, published.updated_at, draft.updated_at)
          as changed_at,
        content_private.exhibition_metadata_is_stale(published.id)
          or content_private.exhibition_metadata_is_stale(draft.id)
          as metadata_stale,
        content_private.exhibition_hours_is_stale(published.id)
          or content_private.exhibition_hours_is_stale(draft.id)
          as hours_stale
      from content.exhibitions as exhibition
      left join content.exhibition_versions as published
        on published.id = exhibition.published_version_id
      left join content.exhibition_versions as draft
        on draft.exhibition_id = exhibition.id
       and draft.status = 'draft'::content.exhibition_version_status
      where exhibition.archived_at is null
        and content_private.admin_owner_exhibition_visible(exhibition.id)
    )
    select coalesce(
      jsonb_agg(
        jsonb_build_object(
          'exhibition_id', candidate.id,
          'name_ko', candidate.name_ko,
          'name_en', candidate.name_en,
          'metadata_stale', candidate.metadata_stale,
          'hours_stale', candidate.hours_stale
        )
        order by candidate.changed_at desc, candidate.id
      ),
      '[]'::jsonb
    )
    from candidates as candidate
    where candidate.metadata_stale or candidate.hours_stale
  );
end;
$function$;

revoke all on function content_private.admin_list_stale_exhibition_metadata_impl()
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_list_stale_exhibition_metadata_impl()
  to authenticated;

-- == confirm ==

-- Writes the week on the open draft when there is one, otherwise on the published version. A
-- change on the published version is visible to readers at once, so it needs the publisher role;
-- a draft change waits for the normal publish step and needs only a contributor.
create or replace function content_private.admin_confirm_exhibition_hours_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_hours_weekly jsonb
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_actor_id uuid := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  v_exhibition content.exhibitions%rowtype;
  v_target content.exhibition_versions%rowtype;
  v_updated content.exhibition_versions%rowtype;
begin
  if p_expected_revision is null or p_expected_revision < 1 then
    raise exception using
      errcode = '22023',
      message = 'expected_revision_must_be_positive';
  end if;
  if p_expected_version_id is null then
    raise exception using
      errcode = '22023',
      message = 'expected_version_id_is_required';
  end if;
  if not content_private.hours_weekly_is_valid(p_hours_weekly) then
    raise exception using
      errcode = '22023',
      message = 'hours_weekly_invalid';
  end if;

  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
  for update;

  if not found then
    raise exception using
      errcode = 'P0002',
      message = 'exhibition_not_found';
  end if;
  -- Owners' private, unsubmitted exhibitions are invisible to staff, as in admin_get_exhibition.
  if not content_private.admin_owner_exhibition_visible(v_exhibition.id) then
    raise exception using
      errcode = 'P0002',
      message = 'exhibition_not_found';
  end if;
  if v_exhibition.archived_at is not null then
    raise exception using
      errcode = '22023',
      message = 'restore_exhibition_before_editing';
  end if;

  v_target := content_private.lock_exhibition_working_version(
    p_exhibition_id,
    v_exhibition.published_version_id
  );
  if v_target.id is null then
    raise exception using
      errcode = 'P0002',
      message = 'working_version_not_found';
  end if;
  if v_target.status <> 'draft'::content.exhibition_version_status then
    perform content_private.admin_assert_staff('publisher'::content.staff_role);
  end if;
  if v_target.id <> p_expected_version_id
     or v_target.revision <> p_expected_revision then
    raise sqlstate 'PT409' using
      message = 'exhibition_metadata_stale_revision',
      detail = v_target.revision::text;
  end if;

  update content.exhibition_versions as version
  set
    hours_weekly = p_hours_weekly,
    hours_confirmed_by = v_actor_id,
    hours_confirmed_at = now(),
    hours_source_hash = content_private.exhibition_hours_text_hash(version.hours),
    revision = version.revision + 1,
    updated_by = v_actor_id
  where version.id = v_target.id
  returning version.* into v_updated;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  ) values (
    v_actor_id,
    'exhibition.hours_confirmed',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object(
      'version_id', v_updated.id,
      'version_status', v_updated.status::text,
      'revision', v_updated.revision,
      'hours_weekly', v_updated.hours_weekly,
      'hours_source_hash', v_updated.hours_source_hash
    )
  );

  return content_private.admin_exhibition_json(p_exhibition_id, v_updated.id);
end;
$function$;

revoke all on function
  content_private.admin_confirm_exhibition_hours_impl(text, uuid, integer, jsonb)
from public, anon, authenticated, service_role;
grant execute on function
  content_private.admin_confirm_exhibition_hours_impl(text, uuid, integer, jsonb)
to authenticated;

create or replace function public.admin_confirm_exhibition_hours(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_hours_weekly jsonb
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $function$
  select content_private.admin_confirm_exhibition_hours_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_hours_weekly
  );
$function$;

revoke all on function
  public.admin_confirm_exhibition_hours(text, uuid, integer, jsonb)
from public, anon, authenticated, service_role;
grant execute on function
  public.admin_confirm_exhibition_hours(text, uuid, integer, jsonb)
to authenticated;

-- == public catalogue ==

alter table public.exhibition_catalog_v2
  add column if not exists hours_weekly jsonb;

do $catalog_hours_constraint$
begin
  if not exists (
    select 1 from pg_catalog.pg_constraint
    where conrelid = 'public.exhibition_catalog_v2'::regclass
      and conname = 'exhibition_catalog_v2_hours_weekly_object'
  ) then
    alter table public.exhibition_catalog_v2
      add constraint exhibition_catalog_v2_hours_weekly_object
      check (hours_weekly is null or jsonb_typeof(hours_weekly) = 'object');
  end if;
end;
$catalog_hours_constraint$;

comment on column public.exhibition_catalog_v2.hours_weekly is
  'Staff-confirmed week of the published version, present only while its hours text is unchanged since confirmation.';

-- Every writer of the projection (refresh, backfill, compatibility snapshot) passes through this
-- trigger, so the column is always derived from the canonical published version. PostgreSQL runs
-- same-event triggers alphabetically: "assign_hours" runs before "derive_checksum", so the week is
-- part of the row checksum.
create or replace function content_private.assign_exhibition_catalog_hours_weekly()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  new.hours_weekly := (
    select content_private.exhibition_public_hours_weekly(exhibition.published_version_id)
    from content.exhibitions as exhibition
    where exhibition.id = new.id
      and exhibition.archived_at is null
  );
  return new;
end;
$function$;

revoke all on function content_private.assign_exhibition_catalog_hours_weekly()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_catalog_v2_assign_hours_weekly
  on public.exhibition_catalog_v2;
create trigger exhibition_catalog_v2_assign_hours_weekly
  before insert or update on public.exhibition_catalog_v2
  for each row
  execute function content_private.assign_exhibition_catalog_hours_weekly();

-- Reconciliation compares the canonical payload with the projection, so the canonical payload
-- carries the same derived week.
create or replace function
  content_private.exhibition_catalog_v2_source_payload(
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
      'country_code', version.country_code,
      'artists', content_private.exhibition_artists_json(version.id),
      'art_terms', content_private.exhibition_art_terms_json(version.id),
      'hours_weekly', content_private.exhibition_public_hours_weekly(version.id)
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

-- The legacy reader cannot expose the column; its parity payload borrows the projected value, as
-- it already does for artists and terms.
create or replace function
  content_private.legacy_exhibition_catalog_v2_payload(
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
    end,
    'gallery_id', (
      select source.gallery_id
      from content.gallery_catalog_sources as source
      where source.source = 'public.exhibition_catalog_v2'
        and source.source_key =
          content_private.normalize_gallery_catalog_name(p_row.venue_name_ko)
    ),
    'artists', coalesce(
      (
        select catalog.artists
        from public.exhibition_catalog_v2 as catalog
        where catalog.id = p_row.id
      ),
      '[]'::jsonb
    ),
    'art_terms', coalesce(
      (
        select catalog.art_terms
        from public.exhibition_catalog_v2 as catalog
        where catalog.id = p_row.id
      ),
      '[]'::jsonb
    ),
    'hours_weekly', (
      select catalog.hours_weekly
      from public.exhibition_catalog_v2 as catalog
      where catalog.id = p_row.id
    )
  );
$function$;

revoke all on function
  content_private.legacy_exhibition_catalog_v2_payload(public.exhibitions)
from public, anon, authenticated, service_role;

-- Adding a column does not fire the checksum trigger. Route every installed row through the
-- derivation and checksum triggers so stored checksums cover the expanded row shape.
update public.exhibition_catalog_v2 as catalog
set hours_weekly = null;

commit;
