-- Spec 101 (US5): provenance for controlled art metadata.
--
-- Each term and artist credit keeps the sentence that supports it (grounding) and who applied it.
-- Each version records the hash of the text its terms were last confirmed against, so a later
-- description or date change marks the metadata stale for staff. Grounding and review stamps are
-- staff-only: they are never added to exhibition_artists_json, exhibition_art_terms_json or the
-- public catalogue.

begin;

-- == columns ==

alter table content.exhibition_version_terms
  add column if not exists grounding text;
alter table content.exhibition_version_artists
  add column if not exists grounding text;
alter table content.exhibition_versions
  add column if not exists metadata_source_hash text,
  add column if not exists metadata_reviewed_at timestamptz;

do $provenance_constraints$
begin
  if not exists (
    select 1 from pg_catalog.pg_constraint
    where conrelid = 'content.exhibition_version_terms'::regclass
      and conname = 'exhibition_version_terms_grounding_length'
  ) then
    alter table content.exhibition_version_terms
      add constraint exhibition_version_terms_grounding_length
      check (grounding is null or length(grounding) between 1 and 400);
  end if;
  if not exists (
    select 1 from pg_catalog.pg_constraint
    where conrelid = 'content.exhibition_version_artists'::regclass
      and conname = 'exhibition_version_artists_grounding_length'
  ) then
    alter table content.exhibition_version_artists
      add constraint exhibition_version_artists_grounding_length
      check (grounding is null or length(grounding) between 1 and 400);
  end if;
  if not exists (
    select 1 from pg_catalog.pg_constraint
    where conrelid = 'content.exhibition_versions'::regclass
      and conname = 'exhibition_versions_metadata_source_hash_format'
  ) then
    alter table content.exhibition_versions
      add constraint exhibition_versions_metadata_source_hash_format
      check (metadata_source_hash is null or metadata_source_hash ~ '^[0-9a-f]{64}$');
  end if;
end;
$provenance_constraints$;

comment on column content.exhibition_version_terms.grounding is
  'Staff-only supporting sentence for this term, at most 400 characters. Never projected to readers.';
comment on column content.exhibition_version_artists.grounding is
  'Staff-only source line naming this artist, at most 400 characters. Never projected to readers.';
comment on column content.exhibition_versions.metadata_source_hash is
  'SHA-256 of description_ko, description_en, opening_date and closing_date when terms and credits were last confirmed.';
comment on column content.exhibition_versions.metadata_reviewed_at is
  'When terms and credits were last confirmed against this version''s text.';

-- == hashes and staleness ==

-- Contract: SHA-256 (hex) of the four fields joined by a unit separator, nulls as empty strings and
-- dates as ISO text.
create or replace function content_private.exhibition_metadata_fields_hash(
  p_description_ko text,
  p_description_en text,
  p_opening_date date,
  p_closing_date date
)
returns text
language sql
stable
security invoker
set search_path = ''
as $function$
  select encode(
    extensions.digest(
      convert_to(
        concat_ws(
          chr(31),
          coalesce(p_description_ko, ''),
          coalesce(p_description_en, ''),
          coalesce(to_char(p_opening_date, 'YYYY-MM-DD'), ''),
          coalesce(to_char(p_closing_date, 'YYYY-MM-DD'), '')
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  );
$function$;

create or replace function content_private.exhibition_metadata_source_hash(
  p_version_id uuid
)
returns text
language sql
stable
security definer
set search_path = ''
as $function$
  select content_private.exhibition_metadata_fields_hash(
    version.description_ko,
    version.description_en,
    version.opening_date,
    version.closing_date
  )
  from content.exhibition_versions as version
  where version.id = p_version_id;
$function$;

-- Stale: the version has at least one term or artist credit and its stored hash is missing or no
-- longer matches its text. A version without terms or credits is never stale.
create or replace function content_private.exhibition_metadata_is_stale(
  p_version_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
  select coalesce(
    (
      select (
          exists (
            select 1 from content.exhibition_version_terms as link
            where link.version_id = version.id
          )
          or exists (
            select 1 from content.exhibition_version_artists as credit
            where credit.version_id = version.id
          )
        )
        and version.metadata_source_hash is distinct from
          content_private.exhibition_metadata_fields_hash(
            version.description_ko,
            version.description_en,
            version.opening_date,
            version.closing_date
          )
      from content.exhibition_versions as version
      where version.id = p_version_id
    ),
    false
  );
$function$;

revoke all on function
  content_private.exhibition_metadata_fields_hash(text, text, date, date),
  content_private.exhibition_metadata_source_hash(uuid),
  content_private.exhibition_metadata_is_stale(uuid)
from public, anon, authenticated, service_role;

-- == carrying provenance to the next version ==

-- Every version-creation path copies the previous version's review stamp. The text is copied too,
-- so the new version stays fresh until its own text changes.
create or replace function content_private.carry_exhibition_version_provenance()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  if new.version_number <= 1
     or new.metadata_source_hash is not null
     or new.metadata_reviewed_at is not null then
    return new;
  end if;

  select version.metadata_source_hash, version.metadata_reviewed_at
  into new.metadata_source_hash, new.metadata_reviewed_at
  from content.exhibition_versions as version
  where version.exhibition_id = new.exhibition_id
    and version.version_number < new.version_number
  order by version.version_number desc
  limit 1;

  return new;
end;
$function$;

revoke all on function content_private.carry_exhibition_version_provenance()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_versions_carry_provenance
  on content.exhibition_versions;
create trigger exhibition_versions_carry_provenance
  before insert on content.exhibition_versions
  for each row
  execute function content_private.carry_exhibition_version_provenance();

-- A review stamp is not a content change: when an update touches nothing but the stamp, keep the
-- version's updated_at so the public catalogue row (whose updated_at follows the version) is not
-- rewritten. Named to run after exhibition_versions_set_updated_at.
create or replace function content_private.keep_updated_at_for_review_stamp()
returns trigger
language plpgsql
stable
security invoker
set search_path = ''
as $function$
begin
  if (to_jsonb(new) - 'metadata_source_hash' - 'metadata_reviewed_at' - 'updated_at')
     = (to_jsonb(old) - 'metadata_source_hash' - 'metadata_reviewed_at' - 'updated_at') then
    new.updated_at := old.updated_at;
  end if;
  return new;
end;
$function$;

revoke all on function content_private.keep_updated_at_for_review_stamp()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_versions_zz_review_stamp_keeps_updated_at
  on content.exhibition_versions;
create trigger exhibition_versions_zz_review_stamp_keeps_updated_at
  before update on content.exhibition_versions
  for each row
  execute function content_private.keep_updated_at_for_review_stamp();

-- Cloning copies grounding and keeps who first applied each term and credit.
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
    grounding,
    created_at,
    created_by
  )
  select
    new.id,
    credit.sort_order,
    credit.artist_id,
    credit.name_ko,
    credit.name_en,
    credit.grounding,
    credit.created_at,
    coalesce(credit.created_by, new.created_by, new.updated_by)
  from content.exhibition_version_artists as credit
  where credit.version_id = v_source_version_id
  order by credit.sort_order;

  insert into content.exhibition_version_terms (
    version_id,
    term_id,
    sort_order,
    grounding,
    created_at,
    created_by
  )
  select
    new.id,
    link.term_id,
    link.sort_order,
    link.grounding,
    link.created_at,
    coalesce(link.created_by, new.created_by, new.updated_by)
  from content.exhibition_version_terms as link
  where link.version_id = v_source_version_id
  order by link.sort_order;

  return new;
end;
$function$;

revoke all on function content_private.clone_exhibition_art_metadata()
  from public, anon, authenticated, service_role;

-- == draft patch validation ==

-- Keep the established artist and term validation intact and add grounding around it.
do $rename_art_metadata_validator$
begin
  if to_regprocedure(
    'content_private.validate_art_metadata_patch_without_grounding(jsonb)'
  ) is null then
    alter function content_private.validate_art_metadata_patch(jsonb)
      rename to validate_art_metadata_patch_without_grounding;
  end if;
end;
$rename_art_metadata_validator$;

revoke all on function
  content_private.validate_art_metadata_patch_without_grounding(jsonb)
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
  v_artists jsonb;
  v_term_grounding jsonb;
begin
  if p_patch is null or jsonb_typeof(p_patch) <> 'object' then
    perform content_private.validate_art_metadata_patch_without_grounding(p_patch);
    return;
  end if;

  v_artists := p_patch -> 'artists';
  if jsonb_typeof(v_artists) = 'array' then
    if exists (
      select 1
      from jsonb_array_elements(v_artists) as artist(value)
      where jsonb_typeof(artist.value) = 'object'
        and artist.value ? 'grounding'
        and (
          jsonb_typeof(artist.value -> 'grounding') not in ('string', 'null')
          or length(coalesce(artist.value ->> 'grounding', '')) > 400
        )
    ) then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_artist_grounding_invalid';
    end if;

    select coalesce(
      jsonb_agg(
        case
          when jsonb_typeof(artist.value) = 'object' then artist.value - 'grounding'
          else artist.value
        end
        order by artist.ordinality
      ),
      '[]'::jsonb
    )
    into v_artists
    from jsonb_array_elements(v_artists) with ordinality as artist(value, ordinality);

    perform content_private.validate_art_metadata_patch_without_grounding(
      jsonb_set(p_patch - 'art_term_grounding', '{artists}', v_artists)
    );
  else
    perform content_private.validate_art_metadata_patch_without_grounding(
      p_patch - 'art_term_grounding'
    );
  end if;

  if p_patch ? 'art_term_grounding' then
    v_term_grounding := p_patch -> 'art_term_grounding';
    if jsonb_typeof(v_term_grounding) <> 'object'
       or exists (
         select 1
         from jsonb_each(v_term_grounding) as entry(term_id, value)
         where jsonb_typeof(entry.value) not in ('string', 'null')
            or length(coalesce(entry.value #>> '{}', '')) > 400
       )
       or (
         p_patch ? 'art_term_ids'
         and exists (
           select 1
           from jsonb_object_keys(v_term_grounding) as entry(term_id)
           where not ((p_patch -> 'art_term_ids') ? entry.term_id)
         )
       ) then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_term_grounding_invalid';
    end if;
  end if;
end;
$function$;

revoke all on function content_private.validate_art_metadata_patch(jsonb)
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
    p_patch - 'artists' - 'art_term_ids' - 'art_term_grounding'
  );
end;
$function$;

revoke all on function content_private.admin_validate_patch(jsonb)
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
      p_patch - 'artists' - 'art_term_ids' - 'art_term_grounding'
    );
end;
$function$;

revoke all on function
  content_private.owner_validate_exhibition_patch(jsonb)
from public, anon, authenticated, service_role;

-- == applying the patch ==

-- Omission keeps what is stored; an explicit value (null clears) replaces it. A term or credit that
-- stays on the draft keeps its grounding, applier and creation time unless the patch says otherwise.
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
  v_has_term_grounding boolean := coalesce(
    current_setting('content.art_metadata_has_term_grounding', true),
    'false'
  ) = 'true';
  v_artists jsonb;
  v_terms jsonb;
  v_term_grounding jsonb := '{}'::jsonb;
  v_previous_artists jsonb;
  v_previous_terms jsonb;
  v_prior jsonb;
  v_grounding text;
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

  if v_has_term_grounding then
    v_term_grounding := coalesce(
      nullif(current_setting('content.art_metadata_term_grounding', true), '')::jsonb,
      '{}'::jsonb
    );
  end if;

  if v_has_artists then
    v_artists := coalesce(
      nullif(current_setting('content.art_metadata_artists', true), '')::jsonb,
      '[]'::jsonb
    );

    select coalesce(
      jsonb_agg(
        jsonb_build_object(
          'artist_id', credit.artist_id,
          'name_ko', credit.name_ko,
          'name_en', credit.name_en,
          'grounding', credit.grounding,
          'created_at', credit.created_at,
          'created_by', credit.created_by
        )
        order by credit.sort_order
      ),
      '[]'::jsonb
    )
    into v_previous_artists
    from content.exhibition_version_artists as credit
    where credit.version_id = new.id;

    delete from content.exhibition_version_artists
    where version_id = new.id;

    for v_credit in
      select value, ordinality
      from jsonb_array_elements(v_artists) with ordinality
        as credit(value, ordinality)
      order by ordinality
    loop
      v_artist_id := null;
      v_prior := null;
      if nullif(v_credit.value ->> 'id', '') is not null then
        v_artist_id := (v_credit.value ->> 'id')::uuid;
        -- FOR SHARE waits for a concurrent merge of this artist; once the merge commits the
        -- re-checked row is archived and the credit is refused instead of pointing at it.
        select artist.name_ko, artist.name_en
        into v_name_ko, v_name_en
        from content.artists as artist
        where artist.id = v_artist_id
          and artist.archived_at is null
        for share;
        if not found then
          raise exception using
            errcode = '22023',
            message = 'art_metadata_artist_unknown';
        end if;

        select prior.value
        into v_prior
        from jsonb_array_elements(v_previous_artists) as prior(value)
        where prior.value ->> 'artist_id' = v_artist_id::text
        limit 1;
      else
        v_name_ko := btrim(coalesce(v_credit.value ->> 'name_ko', ''));
        v_name_en := btrim(coalesce(v_credit.value ->> 'name_en', ''));

        select prior.value
        into v_prior
        from jsonb_array_elements(v_previous_artists) as prior(value)
        where jsonb_typeof(prior.value -> 'artist_id') = 'null'
          and prior.value ->> 'name_ko' = v_name_ko
          and prior.value ->> 'name_en' = v_name_en
        limit 1;
      end if;

      if v_credit.value ? 'grounding' then
        v_grounding := nullif(btrim(coalesce(v_credit.value ->> 'grounding', '')), '');
      else
        v_grounding := v_prior ->> 'grounding';
      end if;

      insert into content.exhibition_version_artists (
        version_id,
        sort_order,
        artist_id,
        name_ko,
        name_en,
        grounding,
        created_at,
        created_by
      ) values (
        new.id,
        (v_credit.ordinality - 1)::smallint,
        v_artist_id,
        v_name_ko,
        v_name_en,
        v_grounding,
        coalesce((v_prior ->> 'created_at')::timestamptz, now()),
        case
          when v_prior is null then v_actor_id
          else coalesce((v_prior ->> 'created_by')::uuid, v_actor_id)
        end
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

    select coalesce(
      jsonb_agg(
        jsonb_build_object(
          'term_id', link.term_id,
          'grounding', link.grounding,
          'created_at', link.created_at,
          'created_by', link.created_by
        )
      ),
      '[]'::jsonb
    )
    into v_previous_terms
    from content.exhibition_version_terms as link
    where link.version_id = new.id;

    delete from content.exhibition_version_terms
    where version_id = new.id;

    insert into content.exhibition_version_terms (
      version_id,
      term_id,
      sort_order,
      grounding,
      created_at,
      created_by
    )
    select
      new.id,
      requested.id,
      (requested.ordinality - 1)::smallint,
      case
        when v_term_grounding ? requested.id
          then nullif(btrim(coalesce(v_term_grounding ->> requested.id, '')), '')
        else prior.grounding
      end,
      coalesce(prior.created_at, now()),
      case
        when prior.term_id is null then v_actor_id
        else coalesce(prior.created_by, v_actor_id)
      end
    from jsonb_array_elements_text(v_terms) with ordinality
      as requested(id, ordinality)
    join content.art_taxonomy_terms as term
      on term.id = requested.id
     and term.active
    left join jsonb_to_recordset(v_previous_terms) as prior(
      term_id text,
      grounding text,
      created_at timestamptz,
      created_by uuid
    ) on prior.term_id = requested.id
    order by requested.ordinality;

    get diagnostics v_inserted_term_count = row_count;
    if v_inserted_term_count <> v_requested_term_count then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_term_unknown';
    end if;
  end if;

  if v_has_term_grounding then
    if exists (
      select 1
      from jsonb_object_keys(v_term_grounding) as entry(term_id)
      where not exists (
        select 1
        from content.exhibition_version_terms as link
        where link.version_id = new.id
          and link.term_id = entry.term_id
      )
    ) then
      raise exception using
        errcode = '22023',
        message = 'art_metadata_term_grounding_invalid';
    end if;

    if not v_has_terms then
      update content.exhibition_version_terms as link
      set grounding = nullif(btrim(coalesce(v_term_grounding ->> link.term_id, '')), '')
      where link.version_id = new.id
        and v_term_grounding ? link.term_id;
    end if;
  end if;

  return new;
end;
$function$;

revoke all on function content_private.apply_exhibition_art_metadata()
  from public, anon, authenticated, service_role;

-- Both browser save contracts keep their PostgREST signatures; the grounding envelope travels in
-- the same transaction-local settings as the artists and term ids.
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
  perform set_config(
    'content.art_metadata_has_term_grounding',
    (coalesce(p_patch, '{}'::jsonb) ? 'art_term_grounding')::text,
    true
  );
  perform set_config(
    'content.art_metadata_term_grounding',
    coalesce((p_patch -> 'art_term_grounding')::text, '{}'),
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
  perform set_config(
    'content.art_metadata_has_term_grounding',
    (coalesce(p_patch, '{}'::jsonb) ? 'art_term_grounding')::text,
    true
  );
  perform set_config(
    'content.art_metadata_term_grounding',
    coalesce((p_patch -> 'art_term_grounding')::text, '{}'),
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

-- == staff responses ==

create or replace function content_private.exhibition_artists_staff_json(
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
        'name_en', credit.name_en,
        'grounding', credit.grounding
      )
      order by credit.sort_order
    ),
    '[]'::jsonb
  )
  from content.exhibition_version_artists as credit
  where credit.version_id = p_version_id;
$function$;

create or replace function content_private.exhibition_art_term_grounding_json(
  p_version_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  select coalesce(
    jsonb_object_agg(link.term_id, link.grounding),
    '{}'::jsonb
  )
  from content.exhibition_version_terms as link
  where link.version_id = p_version_id;
$function$;

revoke all on function
  content_private.exhibition_artists_staff_json(uuid),
  content_private.exhibition_art_term_grounding_json(uuid)
from public, anon, authenticated, service_role;

do $rename_admin_exhibition_json$
begin
  if to_regprocedure(
    'content_private.admin_exhibition_json_without_provenance(text,uuid)'
  ) is null then
    alter function content_private.admin_exhibition_json(text, uuid)
      rename to admin_exhibition_json_without_provenance;
  end if;
end;
$rename_admin_exhibition_json$;

revoke all on function
  content_private.admin_exhibition_json_without_provenance(text, uuid)
from public, anon, authenticated, service_role;

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
    'metadata_reviewed_at', version.metadata_reviewed_at
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

-- == confirm and list ==

-- The version a staff edit targets: the open draft when there is one, otherwise the published
-- version. Both are locked; the caller holds the exhibition row lock already.
create or replace function content_private.lock_exhibition_working_version(
  p_exhibition_id text,
  p_published_version_id uuid
)
returns content.exhibition_versions
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_version content.exhibition_versions%rowtype;
begin
  select version.*
  into v_version
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and version.status = 'draft'::content.exhibition_version_status
  order by version.version_number desc
  limit 1
  for update;

  if not found then
    select version.*
    into v_version
    from content.exhibition_versions as version
    where version.exhibition_id = p_exhibition_id
      and version.id = p_published_version_id
    for update;
  end if;

  if p_published_version_id is not null then
    perform 1
    from content.exhibition_versions as version
    where version.id = p_published_version_id
    for update;
  end if;

  return v_version;
end;
$function$;

revoke all on function
  content_private.lock_exhibition_working_version(text, uuid)
from public, anon, authenticated, service_role;

create or replace function content_private.admin_confirm_exhibition_metadata_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
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
  v_stamped jsonb;
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
  if v_target.id <> p_expected_version_id
     or v_target.revision <> p_expected_revision then
    raise sqlstate 'PT409' using
      message = 'exhibition_metadata_stale_revision',
      detail = v_target.revision::text;
  end if;

  -- A review stamp only: the revision does not move and readers see no change.
  with stamped as (
    update content.exhibition_versions as version
    set
      metadata_source_hash = content_private.exhibition_metadata_fields_hash(
        version.description_ko,
        version.description_en,
        version.opening_date,
        version.closing_date
      ),
      metadata_reviewed_at = now()
    where version.exhibition_id = p_exhibition_id
      and version.id in (v_target.id, v_exhibition.published_version_id)
    returning version.id, version.metadata_source_hash
  )
  select jsonb_agg(
    jsonb_build_object(
      'version_id', stamped.id,
      'metadata_source_hash', stamped.metadata_source_hash
    )
    order by stamped.id
  )
  into v_stamped
  from stamped;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  ) values (
    v_actor_id,
    'exhibition.metadata_confirmed',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object(
      'working_version_id', v_target.id,
      'revision', v_target.revision,
      'versions', coalesce(v_stamped, '[]'::jsonb)
    )
  );

  return content_private.admin_exhibition_json(p_exhibition_id, v_target.id);
end;
$function$;

-- Exhibitions whose published version or open draft is stale, newest change first.
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
          as metadata_stale
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
          'hours_stale', false
        )
        order by candidate.changed_at desc, candidate.id
      ),
      '[]'::jsonb
    )
    from candidates as candidate
    where candidate.metadata_stale
  );
end;
$function$;

revoke all on function
  content_private.admin_confirm_exhibition_metadata_impl(text, uuid, integer),
  content_private.admin_list_stale_exhibition_metadata_impl()
from public, anon, authenticated, service_role;
grant execute on function
  content_private.admin_confirm_exhibition_metadata_impl(text, uuid, integer),
  content_private.admin_list_stale_exhibition_metadata_impl()
to authenticated;

create or replace function public.admin_confirm_exhibition_metadata(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $function$
  select content_private.admin_confirm_exhibition_metadata_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );
$function$;

create or replace function public.admin_list_stale_exhibition_metadata()
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $function$
  select content_private.admin_list_stale_exhibition_metadata_impl();
$function$;

revoke all on function
  public.admin_confirm_exhibition_metadata(text, uuid, integer),
  public.admin_list_stale_exhibition_metadata()
from public, anon, authenticated, service_role;
grant execute on function
  public.admin_confirm_exhibition_metadata(text, uuid, integer),
  public.admin_list_stale_exhibition_metadata()
to authenticated;

commit;
