-- Explainable artist and controlled art metadata.
--
-- Recommendation ranking remains on-device. This migration only adds reviewed,
-- versioned catalogue evidence. The active public.exhibitions compatibility
-- reader remains structurally unchanged.

begin;

do $create_art_taxonomy_category$
begin
  if not exists (
    select 1
    from pg_catalog.pg_type as type
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = type.typnamespace
    where namespace.nspname = 'content'
      and type.typname = 'art_taxonomy_category'
  ) then
    create type content.art_taxonomy_category as enum (
      'medium',
      'style',
      'theme',
      'mood'
    );
  end if;
end;
$create_art_taxonomy_category$;

create table if not exists content.artists (
  id uuid primary key default gen_random_uuid(),
  name_ko text not null,
  name_en text not null,
  archived_at timestamptz,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null,
  constraint artists_name_ko_not_blank check (
    length(btrim(name_ko)) between 1 and 200
  ),
  constraint artists_name_en_not_blank check (
    length(btrim(name_en)) between 1 and 200
  )
);

comment on table content.artists is
  'Stable bilingual artist identities. Exhibition versions snapshot display labels so later identity maintenance cannot rewrite published credits.';

create table if not exists content.art_taxonomy_terms (
  id text primary key,
  category content.art_taxonomy_category not null,
  name_ko text not null,
  name_en text not null,
  sort_order smallint not null,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  constraint art_taxonomy_terms_id_format check (
    id ~ '^(medium|style|theme|mood):[a-z0-9]+(-[a-z0-9]+)*$'
  ),
  constraint art_taxonomy_terms_category_prefix check (
    split_part(id, ':', 1) = category::text
  ),
  constraint art_taxonomy_terms_name_ko_not_blank check (
    length(btrim(name_ko)) between 1 and 100
  ),
  constraint art_taxonomy_terms_name_en_not_blank check (
    length(btrim(name_en)) between 1 and 100
  ),
  constraint art_taxonomy_terms_sort_nonnegative check (sort_order >= 0),
  unique (category, sort_order)
);

comment on table content.art_taxonomy_terms is
  'Controlled, append-only art vocabulary. A semantic change requires a new stable identifier rather than reinterpreting an existing identifier.';

create table if not exists content.exhibition_version_artists (
  version_id uuid not null
    references content.exhibition_versions(id) on delete cascade,
  sort_order smallint not null,
  artist_id uuid references content.artists(id) on delete restrict,
  name_ko text not null default '',
  name_en text not null default '',
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  primary key (version_id, sort_order),
  constraint exhibition_version_artists_sort_range check (
    sort_order between 0 and 31
  ),
  constraint exhibition_version_artists_name_length check (
    length(name_ko) <= 200 and length(name_en) <= 200
  ),
  constraint exhibition_version_artists_name_present check (
    nullif(btrim(name_ko), '') is not null
    or nullif(btrim(name_en), '') is not null
  ),
  constraint exhibition_version_artists_resolved_bilingual check (
    artist_id is null
    or (
      nullif(btrim(name_ko), '') is not null
      and nullif(btrim(name_en), '') is not null
    )
  )
);

create unique index if not exists
  exhibition_version_artists_resolved_identity_idx
  on content.exhibition_version_artists (version_id, artist_id)
  where artist_id is not null;

create index if not exists exhibition_version_artists_artist_idx
  on content.exhibition_version_artists (artist_id, version_id)
  where artist_id is not null;

comment on table content.exhibition_version_artists is
  'Ordered artist credits snapshotted on an exhibition version. A null artist_id is a private owner suggestion and is rejected at publication.';

create table if not exists content.exhibition_version_terms (
  version_id uuid not null
    references content.exhibition_versions(id) on delete cascade,
  term_id text not null
    references content.art_taxonomy_terms(id) on delete restrict,
  sort_order smallint not null,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  primary key (version_id, term_id),
  constraint exhibition_version_terms_sort_range check (
    sort_order between 0 and 15
  ),
  unique (version_id, sort_order)
);

create index if not exists exhibition_version_terms_term_idx
  on content.exhibition_version_terms (term_id, version_id);

comment on table content.exhibition_version_terms is
  'Ordered controlled descriptors attached to one immutable exhibition version.';

insert into content.art_taxonomy_terms (
  id,
  category,
  name_ko,
  name_en,
  sort_order
)
values
  ('medium:painting', 'medium', '회화', 'Painting', 0),
  ('medium:sculpture', 'medium', '조각', 'Sculpture', 1),
  ('medium:photography', 'medium', '사진', 'Photography', 2),
  ('medium:installation', 'medium', '설치', 'Installation', 3),
  ('medium:video', 'medium', '비디오', 'Video', 4),
  ('medium:digital', 'medium', '디지털', 'Digital', 5),
  ('medium:performance', 'medium', '퍼포먼스', 'Performance', 6),
  ('medium:drawing', 'medium', '드로잉', 'Drawing', 7),
  ('medium:printmaking', 'medium', '판화', 'Printmaking', 8),
  ('medium:craft', 'medium', '공예', 'Craft', 9),
  ('style:abstract', 'style', '추상', 'Abstract', 0),
  ('style:figurative', 'style', '구상', 'Figurative', 1),
  ('style:minimalist', 'style', '미니멀', 'Minimalist', 2),
  ('style:conceptual', 'style', '개념', 'Conceptual', 3),
  ('style:documentary', 'style', '다큐멘터리', 'Documentary', 4),
  ('style:experimental', 'style', '실험', 'Experimental', 5),
  ('theme:identity', 'theme', '정체성', 'Identity', 0),
  ('theme:memory', 'theme', '기억', 'Memory', 1),
  ('theme:nature', 'theme', '자연', 'Nature', 2),
  ('theme:city', 'theme', '도시', 'City', 3),
  ('theme:technology', 'theme', '기술', 'Technology', 4),
  ('theme:society', 'theme', '사회', 'Society', 5),
  ('mood:quiet-meditative', 'mood', '고요함·명상적', 'Quiet / meditative', 0),
  ('mood:energetic', 'mood', '역동적', 'Energetic', 1),
  ('mood:playful', 'mood', '유희적', 'Playful', 2),
  ('mood:unsettling', 'mood', '불안함', 'Unsettling', 3),
  ('mood:intimate', 'mood', '친밀함', 'Intimate', 4),
  ('mood:monumental', 'mood', '기념비적', 'Monumental', 5)
on conflict (id) do nothing;

create or replace function
  content_private.guard_art_taxonomy_term_semantics()
returns trigger
language plpgsql
volatile
security invoker
set search_path = ''
as $function$
begin
  if row(old.id, old.category, old.name_ko, old.name_en)
     is distinct from
     row(new.id, new.category, new.name_ko, new.name_en) then
    raise exception using
      errcode = '23514',
      message = 'art_taxonomy_term_semantics_immutable';
  end if;
  return new;
end;
$function$;

revoke all on function
  content_private.guard_art_taxonomy_term_semantics()
from public, anon, authenticated, service_role;

drop trigger if exists art_taxonomy_terms_guard_semantics
  on content.art_taxonomy_terms;
create trigger art_taxonomy_terms_guard_semantics
  before update of id, category, name_ko, name_en
  on content.art_taxonomy_terms
  for each row
  execute function
    content_private.guard_art_taxonomy_term_semantics();

drop trigger if exists artists_set_updated_at on content.artists;
create trigger artists_set_updated_at
  before update on content.artists
  for each row execute function content_private.set_updated_at();

alter table content.artists enable row level security;
alter table content.art_taxonomy_terms enable row level security;
alter table content.exhibition_version_artists enable row level security;
alter table content.exhibition_version_terms enable row level security;

revoke all on content.artists,
  content.art_taxonomy_terms,
  content.exhibition_version_artists,
  content.exhibition_version_terms
from public, anon, authenticated, service_role;

grant usage on type content.art_taxonomy_category to service_role;
grant all privileges on content.artists,
  content.art_taxonomy_terms,
  content.exhibition_version_artists,
  content.exhibition_version_terms
to service_role;

-- Private JSON projections are shared by Admin, Gallery, submissions, and the
-- public catalogue projector. Draft responses retain unresolved owner names;
-- publication is guarded so public payloads always contain stable IDs.
create or replace function content_private.exhibition_artists_json(
  p_version_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', credit.artist_id,
        'name_ko', credit.name_ko,
        'name_en', credit.name_en
      )
      order by credit.sort_order
    ),
    '[]'::jsonb
  )
  from content.exhibition_version_artists as credit
  where credit.version_id = p_version_id;
$function$;

create or replace function content_private.exhibition_art_terms_json(
  p_version_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', term.id,
        'category', term.category::text,
        'name_ko', term.name_ko,
        'name_en', term.name_en
      )
      order by link.sort_order
    ),
    '[]'::jsonb
  )
  from content.exhibition_version_terms as link
  join content.art_taxonomy_terms as term on term.id = link.term_id
  where link.version_id = p_version_id;
$function$;

revoke all on function content_private.exhibition_artists_json(uuid),
  content_private.exhibition_art_terms_json(uuid)
from public, anon, authenticated, service_role;

create or replace function content_private.validate_art_metadata_patch(
  p_patch jsonb
)
returns void
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_artist jsonb;
  v_artist_id uuid;
  v_term_count integer;
begin
  if p_patch is null or jsonb_typeof(p_patch) <> 'object' then
    raise exception using
      errcode = '22023',
      message = 'patch_must_be_an_object';
  end if;

  if p_patch ? 'artists' then
    if jsonb_typeof(p_patch -> 'artists') <> 'array'
       or jsonb_array_length(p_patch -> 'artists') > 32 then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_artists_invalid';
    end if;

    for v_artist in
      select value
      from jsonb_array_elements(p_patch -> 'artists') as artist(value)
    loop
      if jsonb_typeof(v_artist) <> 'object'
         or exists (
           select 1
           from jsonb_object_keys(v_artist) as key(value)
           where key.value not in ('id', 'name_ko', 'name_en')
         ) then
        raise exception using
          errcode = '22023',
          message = 'art_metadata_artist_invalid';
      end if;

      if v_artist ? 'id'
         and jsonb_typeof(v_artist -> 'id') not in ('string', 'null') then
        raise exception using
          errcode = '22023',
          message = 'art_metadata_artist_id_invalid';
      end if;
      if jsonb_typeof(v_artist -> 'id') = 'string'
         and nullif(btrim(v_artist ->> 'id'), '') is null then
        raise exception using
          errcode = '22023',
          message = 'art_metadata_artist_id_invalid';
      end if;

      if (v_artist ? 'name_ko'
          and jsonb_typeof(v_artist -> 'name_ko') not in ('string', 'null'))
         or (v_artist ? 'name_en'
          and jsonb_typeof(v_artist -> 'name_en') not in ('string', 'null'))
         or length(coalesce(v_artist ->> 'name_ko', '')) > 200
         or length(coalesce(v_artist ->> 'name_en', '')) > 200 then
        raise exception using
          errcode = '22023',
          message = 'art_metadata_artist_labels_invalid';
      end if;

      if coalesce(v_artist ->> 'id', '') <> '' then
        begin
          v_artist_id := (v_artist ->> 'id')::uuid;
        exception
          when invalid_text_representation then
            raise exception using
              errcode = '22023',
              message = 'art_metadata_artist_id_invalid';
        end;

        if not exists (
          select 1
          from content.artists as artist
          where artist.id = v_artist_id
            and artist.archived_at is null
        ) then
          raise exception using
            errcode = '22023',
            message = 'art_metadata_artist_unknown';
        end if;
      else
        if (
             nullif(btrim(coalesce(v_artist ->> 'name_ko', '')), '') is null
             and nullif(btrim(coalesce(v_artist ->> 'name_en', '')), '') is null
           ) then
          raise exception using
            errcode = '22023',
            message = 'art_metadata_unresolved_artist_invalid';
        end if;
      end if;
    end loop;

    if exists (
      select 1
      from jsonb_array_elements(p_patch -> 'artists') as artist(value)
      where nullif(artist.value ->> 'id', '') is not null
      group by lower(artist.value ->> 'id')
      having count(*) > 1
    ) then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_duplicate_artist';
    end if;

    if exists (
      select 1
      from jsonb_array_elements(p_patch -> 'artists') as artist(value)
      where nullif(artist.value ->> 'id', '') is null
      group by
        lower(regexp_replace(
          btrim(coalesce(artist.value ->> 'name_ko', '')),
          '[[:space:]]+',
          ' ',
          'g'
        )),
        lower(regexp_replace(
          btrim(coalesce(artist.value ->> 'name_en', '')),
          '[[:space:]]+',
          ' ',
          'g'
        ))
      having count(*) > 1
    ) then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_duplicate_artist';
    end if;
  end if;

  if p_patch ? 'art_term_ids' then
    if jsonb_typeof(p_patch -> 'art_term_ids') <> 'array'
       or jsonb_array_length(p_patch -> 'art_term_ids') > 16
       or exists (
         select 1
         from jsonb_array_elements(p_patch -> 'art_term_ids') as term(value)
         where jsonb_typeof(term.value) <> 'string'
            or nullif(btrim(term.value #>> '{}'), '') is null
       ) then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_terms_invalid';
    end if;

    if exists (
      select 1
      from jsonb_array_elements_text(p_patch -> 'art_term_ids') as term(id)
      group by term.id
      having count(*) > 1
    ) then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_duplicate_term';
    end if;

    select count(*)::integer
    into v_term_count
    from jsonb_array_elements_text(p_patch -> 'art_term_ids') as requested(id)
    join content.art_taxonomy_terms as term
      on term.id = requested.id
     and term.active;

    if v_term_count <> jsonb_array_length(p_patch -> 'art_term_ids') then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_term_unknown';
    end if;

    if exists (
      select 1
      from jsonb_array_elements_text(p_patch -> 'art_term_ids') as requested(id)
      join content.art_taxonomy_terms as term on term.id = requested.id
      group by term.category
      having count(*) > 6
    ) then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_category_limit_exceeded';
    end if;
  end if;
end;
$function$;

revoke all on function content_private.validate_art_metadata_patch(jsonb)
  from public, anon, authenticated, service_role;

-- Preserve all established patch validation by accepting only the two new
-- fields here and delegating every pre-existing field to the installed chain.
do $rename_admin_validator$
begin
  if to_regprocedure(
    'content_private.admin_validate_patch_without_art_metadata(jsonb)'
  ) is null then
    alter function content_private.admin_validate_patch(jsonb)
      rename to admin_validate_patch_without_art_metadata;
  end if;
end;
$rename_admin_validator$;

revoke all on function
  content_private.admin_validate_patch_without_art_metadata(jsonb)
from public, anon, authenticated, service_role;

create or replace function content_private.admin_validate_patch(p_patch jsonb)
returns void
language plpgsql
stable
security invoker
set search_path = ''
as $function$
begin
  perform content_private.validate_art_metadata_patch(p_patch);
  perform content_private.admin_validate_patch_without_art_metadata(
    p_patch - 'artists' - 'art_term_ids'
  );
end;
$function$;

revoke all on function content_private.admin_validate_patch(jsonb)
  from public, anon, authenticated, service_role;

do $rename_owner_validator$
begin
  if to_regprocedure(
    'content_private.owner_validate_exhibition_patch_without_art_metadata(jsonb)'
  ) is null then
    alter function content_private.owner_validate_exhibition_patch(jsonb)
      rename to owner_validate_exhibition_patch_without_art_metadata;
  end if;
end;
$rename_owner_validator$;

revoke all on function
  content_private.owner_validate_exhibition_patch_without_art_metadata(jsonb)
from public, anon, authenticated, service_role;

create or replace function content_private.owner_validate_exhibition_patch(
  p_patch jsonb
)
returns void
language plpgsql
stable
security invoker
set search_path = ''
as $function$
begin
  perform content_private.validate_art_metadata_patch(p_patch);
  perform
    content_private.owner_validate_exhibition_patch_without_art_metadata(
      p_patch - 'artists' - 'art_term_ids'
    );
end;
$function$;

revoke all on function
  content_private.owner_validate_exhibition_patch(jsonb)
from public, anon, authenticated, service_role;

-- Every version-creation path inherits the previous version's immutable
-- evidence. Explicit Admin/owner patch values replace that clone later in the
-- same revision-checked transaction.
create or replace function content_private.clone_exhibition_art_metadata()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_source_version_id uuid;
begin
  if new.version_number <= 1 then
    return new;
  end if;

  select version.id
  into v_source_version_id
  from content.exhibition_versions as version
  where version.exhibition_id = new.exhibition_id
    and version.id <> new.id
    and version.version_number < new.version_number
  order by version.version_number desc
  limit 1;

  if v_source_version_id is null then
    return new;
  end if;

  insert into content.exhibition_version_artists (
    version_id,
    sort_order,
    artist_id,
    name_ko,
    name_en,
    created_by
  )
  select
    new.id,
    credit.sort_order,
    credit.artist_id,
    credit.name_ko,
    credit.name_en,
    coalesce(new.created_by, new.updated_by)
  from content.exhibition_version_artists as credit
  where credit.version_id = v_source_version_id
  order by credit.sort_order;

  insert into content.exhibition_version_terms (
    version_id,
    term_id,
    sort_order,
    created_by
  )
  select
    new.id,
    link.term_id,
    link.sort_order,
    coalesce(new.created_by, new.updated_by)
  from content.exhibition_version_terms as link
  where link.version_id = v_source_version_id
  order by link.sort_order;

  return new;
end;
$function$;

revoke all on function content_private.clone_exhibition_art_metadata()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_versions_clone_art_metadata
  on content.exhibition_versions;
create trigger exhibition_versions_clone_art_metadata
  after insert on content.exhibition_versions
  for each row
  execute function content_private.clone_exhibition_art_metadata();

create or replace function content_private.apply_exhibition_art_metadata()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_active boolean := coalesce(
    current_setting('content.art_metadata_patch_active', true),
    'false'
  ) = 'true';
  v_has_artists boolean := coalesce(
    current_setting('content.art_metadata_has_artists', true),
    'false'
  ) = 'true';
  v_has_terms boolean := coalesce(
    current_setting('content.art_metadata_has_terms', true),
    'false'
  ) = 'true';
  v_artists jsonb;
  v_terms jsonb;
  v_credit record;
  v_artist_id uuid;
  v_name_ko text;
  v_name_en text;
  v_term record;
  v_requested_term_count integer;
  v_locked_term_count integer;
  v_inserted_term_count integer;
  v_actor_id uuid := (select auth.uid());
begin
  if not v_active then
    return new;
  end if;
  if new.status <> 'draft'::content.exhibition_version_status then
    raise exception using
      errcode = '22023',
      message = 'art_metadata_version_not_editable';
  end if;

  if v_has_artists then
    v_artists := coalesce(
      nullif(current_setting('content.art_metadata_artists', true), '')::jsonb,
      '[]'::jsonb
    );
    delete from content.exhibition_version_artists
    where version_id = new.id;

    for v_credit in
      select value, ordinality
      from jsonb_array_elements(v_artists) with ordinality
        as credit(value, ordinality)
      order by ordinality
    loop
      v_artist_id := null;
      if nullif(v_credit.value ->> 'id', '') is not null then
        v_artist_id := (v_credit.value ->> 'id')::uuid;
        select artist.name_ko, artist.name_en
        into v_name_ko, v_name_en
        from content.artists as artist
        where artist.id = v_artist_id
          and artist.archived_at is null;
        if not found then
          raise exception using
            errcode = '22023',
            message = 'art_metadata_artist_unknown';
        end if;
      else
        v_name_ko := btrim(coalesce(v_credit.value ->> 'name_ko', ''));
        v_name_en := btrim(coalesce(v_credit.value ->> 'name_en', ''));
      end if;

      insert into content.exhibition_version_artists (
        version_id,
        sort_order,
        artist_id,
        name_ko,
        name_en,
        created_by
      ) values (
        new.id,
        (v_credit.ordinality - 1)::smallint,
        v_artist_id,
        v_name_ko,
        v_name_en,
        v_actor_id
      );
    end loop;
  end if;

  if v_has_terms then
    v_terms := coalesce(
      nullif(current_setting('content.art_metadata_term_ids', true), '')::jsonb,
      '[]'::jsonb
    );
    v_requested_term_count := jsonb_array_length(v_terms);
    v_locked_term_count := 0;

    -- Validation happens before the save acquires the exhibition revision
    -- lock. Re-read and lock every requested term here, in stable ID order,
    -- so retirement cannot race between validation and link replacement.
    for v_term in
      select term.id
      from jsonb_array_elements_text(v_terms) as requested(id)
      join content.art_taxonomy_terms as term
        on term.id = requested.id
       and term.active
      order by term.id
      for share of term
    loop
      v_locked_term_count := v_locked_term_count + 1;
    end loop;

    if v_locked_term_count <> v_requested_term_count then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_term_unknown';
    end if;

    delete from content.exhibition_version_terms
    where version_id = new.id;

    insert into content.exhibition_version_terms (
      version_id,
      term_id,
      sort_order,
      created_by
    )
    select
      new.id,
      requested.id,
      (requested.ordinality - 1)::smallint,
      v_actor_id
    from jsonb_array_elements_text(v_terms) with ordinality
      as requested(id, ordinality)
    join content.art_taxonomy_terms as term
      on term.id = requested.id
     and term.active
    order by requested.ordinality;

    get diagnostics v_inserted_term_count = row_count;
    if v_inserted_term_count <> v_requested_term_count then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_term_unknown';
    end if;
  end if;

  return new;
end;
$function$;

revoke all on function content_private.apply_exhibition_art_metadata()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_versions_apply_art_metadata
  on content.exhibition_versions;
create trigger exhibition_versions_apply_art_metadata
  after update of revision on content.exhibition_versions
  for each row
  when (new.revision is distinct from old.revision)
  execute function content_private.apply_exhibition_art_metadata();

create or replace function content_private.require_resolved_art_metadata()
returns trigger
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  if new.status = 'published'::content.exhibition_version_status
     and old.status is distinct from new.status
     and exists (
       select 1
       from content.exhibition_version_artists as credit
       where credit.version_id = new.id
         and credit.artist_id is null
     ) then
    raise exception using
      errcode = '23514',
      message = 'unresolved_artist_credits';
  end if;
  return new;
end;
$function$;

revoke all on function content_private.require_resolved_art_metadata()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_versions_require_resolved_art_metadata
  on content.exhibition_versions;
create trigger exhibition_versions_require_resolved_art_metadata
  before update of status on content.exhibition_versions
  for each row
  execute function content_private.require_resolved_art_metadata();

-- Keep both browser contracts on their existing PostgREST signatures. The
-- transaction-local envelope is consumed only by the revision-update trigger;
-- omission preserves links and an explicit empty array clears them.
create or replace function public.admin_save_exhibition_draft(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_patch jsonb
)
returns jsonb
language plpgsql
volatile
security invoker
set search_path = ''
as $function$
declare
  v_result jsonb;
  v_message text;
  v_detail text;
begin
  perform set_config('content.admin_credits_active', 'true', true);
  perform set_config(
    'content.admin_credits_has_ko',
    (coalesce(p_patch, '{}'::jsonb) ? 'credits_ko')::text,
    true
  );
  perform set_config(
    'content.admin_credits_has_en',
    (coalesce(p_patch, '{}'::jsonb) ? 'credits_en')::text,
    true
  );
  perform set_config(
    'content.admin_credits_ko',
    coalesce(p_patch ->> 'credits_ko', ''),
    true
  );
  perform set_config(
    'content.admin_credits_en',
    coalesce(p_patch ->> 'credits_en', ''),
    true
  );
  perform set_config('content.admin_reception_end_active', 'true', true);
  perform set_config(
    'content.admin_reception_end_has_value',
    (coalesce(p_patch, '{}'::jsonb) ? 'reception_end_time')::text,
    true
  );
  perform set_config(
    'content.admin_reception_end_value',
    coalesce(p_patch ->> 'reception_end_time', ''),
    true
  );
  perform set_config('content.art_metadata_patch_active', 'true', true);
  perform set_config(
    'content.art_metadata_has_artists',
    (coalesce(p_patch, '{}'::jsonb) ? 'artists')::text,
    true
  );
  perform set_config(
    'content.art_metadata_artists',
    coalesce((p_patch -> 'artists')::text, '[]'),
    true
  );
  perform set_config(
    'content.art_metadata_has_terms',
    (coalesce(p_patch, '{}'::jsonb) ? 'art_term_ids')::text,
    true
  );
  perform set_config(
    'content.art_metadata_term_ids',
    coalesce((p_patch -> 'art_term_ids')::text, '[]'),
    true
  );

  v_result := content_private.admin_save_exhibition_draft_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_patch
  );

  perform set_config('content.admin_credits_active', 'false', true);
  perform set_config('content.admin_reception_end_active', 'false', true);
  perform set_config('content.art_metadata_patch_active', 'false', true);
  return v_result;
exception
  when sqlstate '40001' then
    get stacked diagnostics
      v_message = message_text,
      v_detail = pg_exception_detail;

    if v_message = 'revision_conflict' then
      raise sqlstate 'PT409' using
        message = v_message,
        detail = v_detail;
    end if;

    raise;
end;
$function$;

revoke all on function public.admin_save_exhibition_draft(
  text, uuid, integer, jsonb
) from public, anon, authenticated, service_role;
grant execute on function public.admin_save_exhibition_draft(
  text, uuid, integer, jsonb
) to authenticated;

create or replace function public.owner_save_exhibition_draft(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_patch jsonb
)
returns jsonb
language plpgsql
volatile
security invoker
set search_path = ''
as $function$
declare
  v_result jsonb;
begin
  perform set_config('content.art_metadata_patch_active', 'true', true);
  perform set_config(
    'content.art_metadata_has_artists',
    (coalesce(p_patch, '{}'::jsonb) ? 'artists')::text,
    true
  );
  perform set_config(
    'content.art_metadata_artists',
    coalesce((p_patch -> 'artists')::text, '[]'),
    true
  );
  perform set_config(
    'content.art_metadata_has_terms',
    (coalesce(p_patch, '{}'::jsonb) ? 'art_term_ids')::text,
    true
  );
  perform set_config(
    'content.art_metadata_term_ids',
    coalesce((p_patch -> 'art_term_ids')::text, '[]'),
    true
  );

  v_result := content_private.owner_save_exhibition_draft_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_patch
  );
  perform set_config('content.art_metadata_patch_active', 'false', true);
  return v_result;
end;
$function$;

revoke all on function public.owner_save_exhibition_draft(
  text, uuid, integer, jsonb
) from public, anon, authenticated, service_role;
grant execute on function public.owner_save_exhibition_draft(
  text, uuid, integer, jsonb
) to authenticated;

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
  select jsonb_build_object(
    'id', exhibition.id,
    'working_version_id', version.id,
    'version_number', version.version_number,
    'published_version_id', exhibition.published_version_id,
    'has_unpublished_changes', version.status = 'draft'::content.exhibition_version_status,
    'name_ko', version.name_ko,
    'name_en', version.name_en,
    'venue_name_ko', version.venue_name_ko,
    'venue_name_en', version.venue_name_en,
    'city_ko', version.city_ko,
    'city_en', version.city_en,
    'region_ko', version.region_ko,
    'region_en', version.region_en,
    'address_ko', version.address_ko,
    'address_en', version.address_en,
    'latitude', coalesce(version.latitude::text, ''),
    'longitude', coalesce(version.longitude::text, ''),
    'event_id', coalesce(version.event_id, ''),
    'editor_id', coalesce(version.editor_id, ''),
    'opening_date', coalesce(to_char(version.opening_date, 'YYYY-MM-DD'), ''),
    'closing_date', coalesce(to_char(version.closing_date, 'YYYY-MM-DD'), ''),
    'description_ko', version.description_ko,
    'description_en', version.description_en,
    'credits_ko', version.credits_ko,
    'credits_en', version.credits_en,
    'hours', coalesce(version.hours, ''),
    'contact', coalesce(version.contact, ''),
    'ticket_url', coalesce(version.ticket_url, ''),
    'reception_date', coalesce(
      to_char(version.reception_date at time zone 'Asia/Seoul', 'YYYY-MM-DD'),
      ''
    ),
    'reception_start_time', coalesce(version.opening_time, ''),
    'reception_end_time', coalesce(version.reception_end_time, ''),
    'cover_image_url', coalesce(cover.public_url, version.legacy_cover_image_url),
    'cover_alt_ko', coalesce(cover.alt_ko, ''),
    'cover_alt_en', coalesce(cover.alt_en, ''),
    'image_credit', coalesce(cover.credit, ''),
    'is_featured', version.is_featured,
    'is_homepage_featured', version.is_homepage_featured,
    'artists', content_private.exhibition_artists_json(version.id),
    'art_terms', content_private.exhibition_art_terms_json(version.id),
    'has_open_owner_submission', exists (
      select 1
      from content.exhibition_submissions as owner_round
      where owner_round.owner_exhibition_id = exhibition.id
        and owner_round.source = 'owner_workspace'
        and owner_round.status in (
          'submitted'::content.submission_status,
          'in_review'::content.submission_status
        )
    ),
    'status', case
      when exhibition.archived_at is not null then 'archived'
      when version.status = 'draft'::content.exhibition_version_status then 'draft'
      else 'published'
    end,
    'revision', version.revision,
    'created_at', exhibition.created_at,
    'published_at', published.published_at,
    'updated_at', greatest(exhibition.updated_at, version.updated_at),
    'updated_by', coalesce(
      nullif(updater_profile.display_name, ''),
      nullif(updater.email, ''),
      'Unknown staff member'
    )
  )
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.exhibition_id = exhibition.id
   and version.id = p_version_id
  left join content.exhibition_versions as published
    on published.id = exhibition.published_version_id
  left join lateral (
    select
      asset.public_url,
      attachment.alt_ko,
      attachment.alt_en,
      attachment.credit
    from content.exhibition_version_media as attachment
    join content.media_assets as asset on asset.id = attachment.media_id
    where attachment.version_id = version.id
      and attachment.role = 'cover'::content.media_role
    order by attachment.sort_order, attachment.created_at
    limit 1
  ) as cover on true
  left join auth.users as updater
    on updater.id = case
      when exhibition.updated_at > version.updated_at then exhibition.updated_by
      else version.updated_by
    end
  left join public.profiles as updater_profile on updater_profile.id = updater.id
  where exhibition.id = p_exhibition_id;
$function$;

revoke all on function content_private.admin_exhibition_json(text, uuid)
  from public, anon, authenticated, service_role;


create or replace function content_private.owner_exhibition_json(
  p_exhibition_id text,
  p_version_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', exhibition.id,
    'working_version_id', version.id,
    'version_number', version.version_number,
    'revision', version.revision,
    'owner_status', exhibition.owner_status::text,
    'review_notes', coalesce(exhibition.owner_review_notes, ''),
    'name_ko', version.name_ko,
    'name_en', version.name_en,
    'venue_name_ko', version.venue_name_ko,
    'venue_name_en', version.venue_name_en,
    'city_ko', version.city_ko,
    'city_en', version.city_en,
    'region_ko', version.region_ko,
    'region_en', version.region_en,
    'address_ko', version.address_ko,
    'address_en', version.address_en,
    'latitude', version.latitude,
    'longitude', version.longitude,
    'opening_date', coalesce(to_char(version.opening_date, 'YYYY-MM-DD'), ''),
    'closing_date', coalesce(to_char(version.closing_date, 'YYYY-MM-DD'), ''),
    'description_ko', version.description_ko,
    'description_en', version.description_en,
    'hours', coalesce(version.hours, ''),
    'contact', coalesce(version.contact, ''),
    'reception_date', coalesce(
      to_char(version.reception_date at time zone 'Asia/Seoul', 'YYYY-MM-DD'),
      ''
    ),
    'reception_start_time', coalesce(version.opening_time, ''),
    'ticket_url', coalesce(version.ticket_url, ''),
    'artists', content_private.exhibition_artists_json(version.id),
    'art_terms', content_private.exhibition_art_terms_json(version.id),
    'updated_at', greatest(exhibition.updated_at, version.updated_at),
    'page_loads_30d', case
      when exhibition.owner_status = 'published'::content.owner_exhibition_status
        and exhibition.archived_at is null then impact.page_loads_30d
      else 0
    end,
    'page_loads_all_time', case
      when exhibition.owner_status = 'published'::content.owner_exhibition_status
        and exhibition.archived_at is null then impact.page_loads_all_time
      else 0
    end,
    'cover', cover.payload
  )
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.exhibition_id = exhibition.id
   and version.id = p_version_id
  left join lateral (
    select
      coalesce(sum(metric.page_loads) filter (
        where metric.metric_date >= (now() at time zone 'UTC')::date - 29
      ), 0)::bigint as page_loads_30d,
      coalesce(sum(metric.page_loads), 0)::bigint as page_loads_all_time
    from content.exhibition_daily_metrics as metric
    where metric.exhibition_id = exhibition.id
  ) as impact on true
  left join lateral (
    select jsonb_build_object(
      'asset_id', asset.id,
      'status', asset.status::text,
      'bucket_id', asset.bucket_id,
      'object_path', asset.object_path,
      'public_url', asset.public_url,
      'mime_type', coalesce(asset.mime_type, ''),
      'byte_size', coalesce(asset.byte_size, 0),
      'original_filename', coalesce(asset.metadata ->> 'original_filename', '')
    ) as payload
    from content.exhibition_version_media as attachment
    join content.media_assets as asset on asset.id = attachment.media_id
    where attachment.version_id = version.id
      and attachment.role = 'cover'::content.media_role
    order by attachment.created_at desc, attachment.media_id
    limit 1
  ) as cover on true;
$$;


revoke all on function content_private.owner_exhibition_json(text, uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_get_exhibition_lookups_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_venues jsonb;
  v_events jsonb;
  v_editors jsonb;
  v_art_terms jsonb;
  v_locations jsonb;
begin
  perform content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );

  with venue_candidates as (
    select
      'venue:' || venue.id::text as id,
      venue.name_ko,
      venue.name_en,
      venue.city_ko,
      venue.city_en,
      venue.region_ko,
      venue.region_en,
      venue.address_ko,
      venue.address_en,
      coalesce(venue.latitude::text, '') as latitude,
      coalesce(venue.longitude::text, '') as longitude,
      0 as source_rank,
      venue.updated_at as source_updated_at
    from content.venues as venue
    where venue.archived_at is null

    union all

    select
      'history:' || version.id::text as id,
      version.venue_name_ko as name_ko,
      version.venue_name_en as name_en,
      version.city_ko,
      version.city_en,
      version.region_ko,
      version.region_en,
      version.address_ko,
      version.address_en,
      coalesce(version.latitude::text, '') as latitude,
      coalesce(version.longitude::text, '') as longitude,
      case
        when version.status = 'published'::content.exhibition_version_status
          then 1
        else 2
      end as source_rank,
      version.updated_at as source_updated_at
    from content.exhibition_versions as version
    where nullif(btrim(version.venue_name_ko), '') is not null
  ),
  ranked_venues as (
    select
      candidate.*,
      row_number() over (
        partition by
          lower(regexp_replace(btrim(candidate.name_ko), '[[:space:]]+', ' ', 'g')),
          lower(
            regexp_replace(
              btrim(
                coalesce(
                  nullif(candidate.address_ko, ''),
                  candidate.city_ko || ' ' || candidate.region_ko
                )
              ),
              '[[:space:]]+',
              ' ',
              'g'
            )
          )
        order by
          candidate.source_rank,
          candidate.source_updated_at desc,
          candidate.id
      ) as location_rank
    from venue_candidates as candidate
  )
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', venue.id,
        'name_ko', venue.name_ko,
        'name_en', venue.name_en,
        'city_ko', venue.city_ko,
        'city_en', venue.city_en,
        'region_ko', venue.region_ko,
        'region_en', venue.region_en,
        'address_ko', venue.address_ko,
        'address_en', venue.address_en,
        'latitude', venue.latitude,
        'longitude', venue.longitude
      )
      order by lower(venue.name_ko), lower(venue.address_ko), venue.id
    ),
    '[]'::jsonb
  )
  into v_venues
  from ranked_venues as venue
  where venue.location_rank = 1
    and exists (
      select 1
      from content.location_cities as city
      join content.location_regions as region
        on region.city_code = city.code
      where city.is_active
        and region.is_active
        and city.city_ko = venue.city_ko
        and city.city_en = venue.city_en
        and region.region_ko = venue.region_ko
        and region.region_en = venue.region_en
    );

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', event.id,
        'name_ko', event.name_ko,
        'name_en', event.name_en,
        'location_label_ko', event.location_label_ko,
        'location_label_en', event.location_label_en,
        'start_date', to_char(event.start_date, 'YYYY-MM-DD'),
        'end_date', to_char(event.end_date, 'YYYY-MM-DD'),
        'short_label', event.short_label,
        'is_active', event.is_active
      )
      order by event.is_active desc, event.start_date desc, event.id
    ),
    '[]'::jsonb
  )
  into v_events
  from public.events as event;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', editor.id,
        'name_ko', editor.name_ko,
        'name_en', editor.name_en,
        'title_ko', editor.title_ko,
        'title_en', editor.title_en,
        'is_active', editor.is_active,
        'active_from', to_char(editor.active_from, 'YYYY-MM-DD'),
        'active_to', case
          when editor.active_to is null then null
          else to_char(editor.active_to, 'YYYY-MM-DD')
        end
      )
      order by
        (editor.id = 'gallr-editors') desc,
        editor.is_active desc,
        editor.active_from desc,
        editor.id
    ),
    '[]'::jsonb
  )
  into v_editors
  from public.editors as editor;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', term.id,
        'category', term.category::text,
        'name_ko', term.name_ko,
        'name_en', term.name_en
      ) order by term.category, term.sort_order, term.id
    ),
    '[]'::jsonb
  )
  into v_art_terms
  from content.art_taxonomy_terms as term
  where term.active;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'city_ko', city.city_ko,
        'city_en', city.city_en,
        'region_ko', region.region_ko,
        'region_en', region.region_en
      ) order by city.sort_order, region.sort_order
    ),
    '[]'::jsonb
  )
  into v_locations
  from content.location_cities as city
  join content.location_regions as region on region.city_code = city.code
  where city.is_active and region.is_active;

  return jsonb_build_object(
    'venues', v_venues,
    'events', v_events,
    'editors', v_editors,
    'art_terms', v_art_terms,
    'locations', v_locations
  );
end;
$$;

revoke all on function content_private.admin_get_exhibition_lookups_impl()
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_get_exhibition_lookups_impl()
  to authenticated;

create or replace function content_private.search_artists_impl(
  p_query text,
  p_limit integer
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_query text := lower(btrim(coalesce(p_query, '')));
begin
  if length(v_query) not between 1 and 100 then
    raise exception using
      errcode = '22023',
      message = 'artist_search_query_invalid';
  end if;
  if p_limit is null or p_limit not between 1 and 20 then
    raise exception using
      errcode = '22023',
      message = 'artist_search_limit_invalid';
  end if;

  return query
  select jsonb_build_object(
    'id', artist.id,
    'name_ko', artist.name_ko,
    'name_en', artist.name_en
  )
  from content.artists as artist
  where artist.archived_at is null
    and (
      position(v_query in lower(artist.name_ko)) > 0
      or position(v_query in lower(artist.name_en)) > 0
    )
  order by
    (lower(artist.name_ko) = v_query or lower(artist.name_en) = v_query) desc,
    (position(v_query in lower(artist.name_ko)) = 1
      or position(v_query in lower(artist.name_en)) = 1) desc,
    lower(artist.name_ko),
    lower(artist.name_en),
    artist.id
  limit p_limit;
end;
$function$;

create or replace function content_private.admin_search_artists_impl(
  p_query text,
  p_limit integer
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  perform content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  return query
  select *
  from content_private.search_artists_impl(p_query, p_limit);
end;
$function$;

create or replace function content_private.owner_search_artists_impl(
  p_query text,
  p_limit integer
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  perform content_private.owner_assert_gallery_membership(true);
  return query
  select *
  from content_private.search_artists_impl(p_query, p_limit);
end;
$function$;

create or replace function content_private.owner_list_art_terms_impl()
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  perform content_private.owner_assert_gallery_membership(true);
  return query
  select jsonb_build_object(
    'id', term.id,
    'category', term.category::text,
    'name_ko', term.name_ko,
    'name_en', term.name_en
  )
  from content.art_taxonomy_terms as term
  where term.active
  order by term.category, term.sort_order, term.id;
end;
$function$;

create or replace function content_private.admin_create_artist_impl(
  p_name_ko text,
  p_name_en text,
  p_request_id uuid
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
  v_name_ko text := btrim(coalesce(p_name_ko, ''));
  v_name_en text := btrim(coalesce(p_name_en, ''));
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_artist_id uuid;
  v_response jsonb;
begin
  if length(v_name_ko) not between 1 and 200
     or length(v_name_en) not between 1 and 200 then
    raise exception using
      errcode = '22023',
      message = 'artist_names_invalid';
  end if;

  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object('name_ko', v_name_ko, 'name_en', v_name_en)
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_actor_id,
    p_request_id,
    'admin_create_artist',
    v_fingerprint
  ) as request;
  if v_is_replay then
    return v_stored;
  end if;

  insert into content.artists (
    name_ko,
    name_en,
    created_by,
    updated_by
  ) values (
    v_name_ko,
    v_name_en,
    v_actor_id,
    v_actor_id
  ) returning id into v_artist_id;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    request_id,
    metadata
  ) values (
    v_actor_id,
    'artist.created',
    'artist',
    v_artist_id::text,
    p_request_id,
    '{}'::jsonb
  );

  v_response := jsonb_build_object(
    'id', v_artist_id,
    'name_ko', v_name_ko,
    'name_en', v_name_en
  );
  return content_private.complete_command_request(
    v_actor_id,
    p_request_id,
    'admin_create_artist',
    v_fingerprint,
    v_response
  );
end;
$function$;

revoke all on function content_private.search_artists_impl(text, integer),
  content_private.admin_search_artists_impl(text, integer),
  content_private.owner_search_artists_impl(text, integer),
  content_private.owner_list_art_terms_impl(),
  content_private.admin_create_artist_impl(text, text, uuid)
from public, anon, authenticated, service_role;

grant execute on function
  content_private.admin_search_artists_impl(text, integer),
  content_private.owner_search_artists_impl(text, integer),
  content_private.owner_list_art_terms_impl(),
  content_private.admin_create_artist_impl(text, text, uuid)
to authenticated;

create or replace function public.admin_search_artists(
  p_query text,
  p_limit integer default 20
)
returns setof jsonb
language sql
stable
security invoker
set search_path = ''
as $function$
  select *
  from content_private.admin_search_artists_impl(p_query, p_limit);
$function$;

create or replace function public.owner_search_artists(
  p_query text,
  p_limit integer default 20
)
returns setof jsonb
language sql
stable
security invoker
set search_path = ''
as $function$
  select *
  from content_private.owner_search_artists_impl(p_query, p_limit);
$function$;

create or replace function public.owner_list_art_terms()
returns setof jsonb
language sql
stable
security invoker
set search_path = ''
as $function$
  select * from content_private.owner_list_art_terms_impl();
$function$;

create or replace function public.admin_create_artist(
  p_name_ko text,
  p_name_en text,
  p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $function$
  select content_private.admin_create_artist_impl(
    p_name_ko,
    p_name_en,
    p_request_id
  );
$function$;

revoke all on function public.admin_search_artists(text, integer),
  public.owner_search_artists(text, integer),
  public.owner_list_art_terms(),
  public.admin_create_artist(text, text, uuid)
from public, anon, authenticated, service_role;

grant execute on function public.admin_search_artists(text, integer),
  public.owner_search_artists(text, integer),
  public.owner_list_art_terms(),
  public.admin_create_artist(text, text, uuid)
to authenticated;

-- Preserve exactly what the owner submitted for each review round. Public and
-- editor submissions intentionally remain metadata-optional.
create or replace function
  content_private.snapshot_owner_submission_art_metadata()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_version_id uuid;
begin
  if new.source <> 'owner_workspace' then
    return new;
  end if;

  begin
    v_version_id := nullif(new.payload ->> 'version_id', '')::uuid;
  exception
    when invalid_text_representation then
      raise exception using
        errcode = '22023',
        message = 'owner_submission_version_invalid';
  end;

  if v_version_id is null
     or not exists (
       select 1
       from content.exhibition_versions as version
       where version.id = v_version_id
         and version.exhibition_id = new.owner_exhibition_id
         and version.status = 'draft'::content.exhibition_version_status
     ) then
    raise exception using
      errcode = '22023',
      message = 'owner_submission_version_invalid';
  end if;

  new.payload := new.payload || jsonb_build_object(
    'artists', content_private.exhibition_artists_json(v_version_id),
    'art_terms', content_private.exhibition_art_terms_json(v_version_id)
  );
  return new;
end;
$function$;

revoke all on function
  content_private.snapshot_owner_submission_art_metadata()
from public, anon, authenticated, service_role;

drop trigger if exists exhibition_submissions_snapshot_owner_art_metadata
  on content.exhibition_submissions;
create trigger exhibition_submissions_snapshot_owner_art_metadata
  before insert on content.exhibition_submissions
  for each row
  execute function
    content_private.snapshot_owner_submission_art_metadata();

-- Additive public envelope. public.exhibitions remains unchanged for the
-- active legacy app; only canonical-v2 readers receive structured evidence.
alter table public.exhibition_catalog_v2
  add column if not exists artists jsonb not null default '[]'::jsonb,
  add column if not exists art_terms jsonb not null default '[]'::jsonb;

do $catalog_art_metadata_constraints$
begin
  if not exists (
    select 1
    from pg_catalog.pg_constraint
    where conrelid = 'public.exhibition_catalog_v2'::regclass
      and conname = 'exhibition_catalog_v2_artists_array'
  ) then
    alter table public.exhibition_catalog_v2
      add constraint exhibition_catalog_v2_artists_array check (
        jsonb_typeof(artists) = 'array'
        and jsonb_array_length(artists) <= 32
      );
  end if;
  if not exists (
    select 1
    from pg_catalog.pg_constraint
    where conrelid = 'public.exhibition_catalog_v2'::regclass
      and conname = 'exhibition_catalog_v2_art_terms_array'
  ) then
    alter table public.exhibition_catalog_v2
      add constraint exhibition_catalog_v2_art_terms_array check (
        jsonb_typeof(art_terms) = 'array'
        and jsonb_array_length(art_terms) <= 16
      );
  end if;
end;
$catalog_art_metadata_constraints$;

comment on column public.exhibition_catalog_v2.artists is
  'Ordered resolved artist identities and bilingual version snapshots used by the local recommender.';
comment on column public.exhibition_catalog_v2.art_terms is
  'Ordered controlled medium, style, theme, and mood descriptors used by the local recommender.';

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
  v_version_id uuid;
  v_artists jsonb;
  v_art_terms jsonb;
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

  select exhibition.published_version_id
  into v_version_id
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id;
  v_artists := content_private.exhibition_artists_json(v_version_id);
  v_art_terms := content_private.exhibition_art_terms_json(v_version_id);

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
    guest_editor_id,
    artists,
    art_terms
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
    v_source.guest_editor_id,
    v_artists,
    v_art_terms
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
    guest_editor_id = excluded.guest_editor_id,
    artists = excluded.artists,
    art_terms = excluded.art_terms
  where catalog.content_checksum_sha256
    is distinct from excluded.content_checksum_sha256;

  perform content_private.mirror_exhibition_catalog_v2_to_legacy(
    p_exhibition_id
  );
end;
$function$;


revoke all on function content_private.refresh_exhibition_catalog_v2(text)
  from public, anon, authenticated, service_role;

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
      'art_terms', content_private.exhibition_art_terms_json(version.id)
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
    )
  );
$function$;

revoke all on function
  content_private.legacy_exhibition_catalog_v2_payload(public.exhibitions)
from public, anon, authenticated, service_role;

-- Adding a JSONB column does not fire the existing checksum trigger. Rebuild
-- every installed V2 row in place so primary rows receive canonical metadata,
-- compatibility-only rows retain empty arrays, and both environments derive a
-- checksum over the expanded row shape before the next mirror pass.
update public.exhibition_catalog_v2 as catalog
set
  artists = coalesce(
    (
      select content_private.exhibition_artists_json(
        exhibition.published_version_id
      )
      from content.exhibitions as exhibition
      where exhibition.id = catalog.id
        and exhibition.published_version_id is not null
    ),
    '[]'::jsonb
  ),
  art_terms = coalesce(
    (
      select content_private.exhibition_art_terms_json(
        exhibition.published_version_id
      )
      from content.exhibitions as exhibition
      where exhibition.id = catalog.id
        and exhibition.published_version_id is not null
    ),
    '[]'::jsonb
  );

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
  v_canonical_count bigint;
  v_event_count bigint;
  v_editor_count bigint;
  v_current_count bigint;
  v_stale_count bigint;
  v_has_duplicate boolean;
  v_checksum_mismatch boolean;
  v_missing_gallery_identity boolean;
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
  if pg_catalog.pg_column_size(p_snapshot) > 16777216 then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_snapshot_is_too_large';
  end if;
  if exists (
    select 1
    from jsonb_object_keys(p_snapshot) as key(value)
    where key.value not in (
      'exhibitions', 'events', 'editors', 'exhibition_catalog_v2'
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
        p_snapshot ? 'exhibition_catalog_v2'
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
    country_code text,
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
    country_code text,
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
    gallery_id uuid,
    content_checksum_sha256 text,
    credits_ko text,
    credits_en text,
    artists jsonb,
    art_terms jsonb
  );

  select namespace.nspname
  into strict v_temp_schema
  from pg_catalog.pg_namespace as namespace
  where namespace.oid = pg_catalog.pg_my_temp_schema();

  execute pg_catalog.format($sql$
    select exists (
      select 1
      from %I.%I as source
      where (source.artists is null) <> (source.art_terms is null)
    )
  $sql$, v_temp_schema, v_temp_canonical_v2)
  into v_checksum_mismatch;
  if v_checksum_mismatch then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_art_metadata_pair_invalid';
  end if;

  execute pg_catalog.format(
    'select count(*) from %I.%I', v_temp_schema, v_temp_exhibitions
  ) into v_exhibition_count;
  execute pg_catalog.format(
    'select count(*) from %I.%I', v_temp_schema, v_temp_canonical_v2
  ) into v_canonical_count;
  execute pg_catalog.format(
    'select count(*) from %I.%I', v_temp_schema, v_temp_events
  ) into v_event_count;
  execute pg_catalog.format(
    'select count(*) from %I.%I', v_temp_schema, v_temp_editors
  ) into v_editor_count;

  if v_exhibition_count = 0 then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_exhibitions_must_not_be_empty';
  end if;
  -- Transition compatibility: a coordinator that predates the canonical-v2
  -- payload may still send three resources. Only enforce the canonical rules
  -- when the key is actually present, matching the prior contract.
  v_has_canonical_v2 := p_snapshot ? 'exhibition_catalog_v2';
  if v_has_canonical_v2 and v_canonical_count = 0 then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_canonical_v2_must_not_be_empty';
  end if;

  -- A snapshot that omits the carried gallery identity would silently write
  -- nulls and permanently diverge the canonical checksum from Seoul. Fail
  -- closed instead, so a stale coordinator cannot poison the mirror.
  if v_has_canonical_v2 then
    execute pg_catalog.format($sql$
      select exists (select 1 from %I.%I where gallery_id is null)
    $sql$, v_temp_schema, v_temp_canonical_v2) into v_missing_gallery_identity;
    if v_missing_gallery_identity then
      raise exception using
        errcode = '22023',
        message = 'legacy_mobile_catalog_gallery_identity_is_missing';
    end if;
  end if;

  execute pg_catalog.format($sql$
    select
      exists (select id from %I.%I group by id having count(*) > 1)
      or exists (select id from %I.%I group by id having count(*) > 1)
      or exists (select id from %I.%I group by id having count(*) > 1)
  $sql$,
    v_temp_schema, v_temp_exhibitions,
    v_temp_schema, v_temp_events,
    v_temp_schema, v_temp_editors
  ) into v_has_duplicate;
  if v_has_canonical_v2 and not v_has_duplicate then
    execute pg_catalog.format($sql$
      select exists (select id from %I.%I group by id having count(*) > 1)
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
      select 1 from %I.%I as source where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_exhibitions) into v_stale_count;
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
        select 1 from %I.%I as source where source.id = target.id
      )
    $sql$, v_temp_schema, v_temp_canonical_v2) into v_stale_count;
    if v_current_count > 0
        and v_stale_count::numeric / v_current_count > v_config.max_delete_fraction then
      raise exception using
        errcode = '22023',
        message = 'legacy_mobile_catalog_delete_limit_exceeded';
    end if;
  end if;

  select count(*) into v_current_count from public.events;
  execute pg_catalog.format($sql$
    select count(*)
    from public.events as target
    where not exists (
      select 1 from %I.%I as source where source.id = target.id
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
      select 1 from %I.%I as source where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_editors) into v_stale_count;
  if v_current_count > 0
      and v_stale_count::numeric / v_current_count > v_config.max_delete_fraction then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_delete_limit_exceeded';
  end if;

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
      id, name_ko, venue_name_ko, country_code, city_ko, region_ko,
      opening_date, closing_date, is_featured, latitude, longitude,
      description_ko, cover_image_url, updated_at, name_en, venue_name_en,
      city_en, region_en, description_en, address_ko, address_en, hours,
      contact, reception_date, opening_time, ticket_url, is_homepage_featured,
      event_id, editor_id, credits_ko, credits_en
    )
    select
      id, name_ko, venue_name_ko, coalesce(country_code, 'KR'), city_ko,
      region_ko, opening_date, closing_date, is_featured, latitude, longitude,
      description_ko, cover_image_url, updated_at, name_en, venue_name_en,
      city_en, region_en, description_en, address_ko, address_en, hours,
      contact, reception_date, opening_time, ticket_url, is_homepage_featured,
      event_id, editor_id, credits_ko, credits_en
    from %I.%I
    on conflict (id) do update set
      name_ko = excluded.name_ko,
      venue_name_ko = excluded.venue_name_ko,
      country_code = excluded.country_code,
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
      select 1 from %I.%I as source where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_exhibitions);

  execute pg_catalog.format($sql$
    delete from public.events as target
    where not exists (
      select 1 from %I.%I as source where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_events);

  execute pg_catalog.format($sql$
    delete from public.editors as target
    where not exists (
      select 1 from %I.%I as source where source.id = target.id
    )
  $sql$, v_temp_schema, v_temp_editors);

  if v_has_canonical_v2 then
    execute pg_catalog.format($sql$
      insert into public.exhibition_catalog_v2 as target (
      id, name_ko, name_en, venue_name_ko, venue_name_en, country_code,
      city_ko, city_en, region_ko, region_en, opening_date, closing_date,
      is_featured, latitude, longitude, description_ko, description_en,
      address_ko, address_en, cover_image_url, hours, contact, reception_date,
      opening_time, event_id, editor_id, is_homepage_featured, ticket_url,
      updated_at, is_editors_pick, guest_editor_id, gallery_id,
      content_checksum_sha256, credits_ko, credits_en, artists, art_terms
    )
    select
      id, name_ko, name_en, venue_name_ko, venue_name_en,
      coalesce(country_code, 'KR'),
      city_ko, city_en, region_ko, region_en, opening_date, closing_date,
      is_featured, latitude, longitude, description_ko, description_en,
      address_ko, address_en, cover_image_url, hours, contact, reception_date,
      opening_time, event_id, editor_id, is_homepage_featured, ticket_url,
      updated_at, is_editors_pick, guest_editor_id, gallery_id,
      content_checksum_sha256, credits_ko, credits_en,
      coalesce(artists, '[]'::jsonb),
      coalesce(art_terms, '[]'::jsonb)
    from %I.%I
    on conflict (id) do update set
      name_ko = excluded.name_ko,
      name_en = excluded.name_en,
      venue_name_ko = excluded.venue_name_ko,
      venue_name_en = excluded.venue_name_en,
      country_code = excluded.country_code,
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
      gallery_id = excluded.gallery_id,
      content_checksum_sha256 = excluded.content_checksum_sha256,
      credits_ko = excluded.credits_ko,
      credits_en = excluded.credits_en,
      artists = excluded.artists,
      art_terms = excluded.art_terms
    where target.content_checksum_sha256
      is distinct from excluded.content_checksum_sha256
    $sql$, v_temp_schema, v_temp_canonical_v2);

    execute pg_catalog.format($sql$
      delete from public.exhibition_catalog_v2 as target
      where not exists (
        select 1 from %I.%I as source where source.id = target.id
      )
    $sql$, v_temp_schema, v_temp_canonical_v2);

    -- The target derives its own checksum before conflict resolution. Comparing
    -- it with the source checksum makes corrupt or incomplete payloads fail
    -- atomically instead of becoming a trusted compatibility snapshot.
    execute pg_catalog.format($sql$
      select exists (
        select 1
        from public.exhibition_catalog_v2 as target
        join %I.%I as source on source.id = target.id
        where source.artists is not null
          and source.art_terms is not null
          and target.content_checksum_sha256
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

  update content_private.legacy_mobile_catalog_mirror_config
  set last_snapshot_sha256 = v_snapshot_sha256,
      last_applied_at = now(),
      reason = p_reason
  where singleton;

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
      'canonical_v2_count', v_canonical_count,
      'reason', btrim(p_reason)
    )
  ) returning id into v_audit_id;

  return jsonb_build_object(
    'status', 'applied',
    'snapshot_sha256', v_snapshot_sha256,
    'exhibition_count', v_exhibition_count,
    'event_count', v_event_count,
    'editor_count', v_editor_count,
    'canonical_v2_count', v_canonical_count,
    'audit_id', v_audit_id
  );
end;
$function$;

comment on function public.service_replace_legacy_mobile_catalog(jsonb, text, text) is
  'Service-role-only Seoul snapshot bridge for installed mobile readers. Both reader contracts preserve country identity, carried gallery identity, structured art metadata, and canonical-v2 checksums; three-resource snapshots remain accepted during rollout.';

revoke all
  on function public.service_replace_legacy_mobile_catalog(jsonb, text, text)
  from public, anon, authenticated, service_role;
grant execute
  on function public.service_replace_legacy_mobile_catalog(jsonb, text, text)
  to service_role;


-- Force the next compatibility pass to carry the expanded canonical-v2 row.
update content_private.legacy_mobile_catalog_mirror_config
set last_snapshot_sha256 = null
where singleton;

commit;
