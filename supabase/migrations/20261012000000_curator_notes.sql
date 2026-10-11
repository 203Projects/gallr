-- Spec 090 curator POV notes: automatically drafted, staff-reviewed curator notes.
--
--   (none) ──draft──► draft ──approve──► approved ──newer approval──► superseded
--                     │  ▲ edit / mark unsupported (status unchanged)
--                     ├──decline──► declined         (auto drafting blocked until staff request one)
--                     ├──regenerate──► replaced      (a fresh draft takes its place; the old one is kept)
--                     └──validation failed──► unusable
--
-- Drafts are written by the draft-curator-notes Edge Function through store_curator_note_draft (service role
-- only) and never reach a visitor: the app reads public.curator_notes_published, which carries approved versions
-- of exhibitions that are still in exhibition_catalog_v2. Staff functions require the publisher tier, bind
-- every write to the version's revision (updated_at) and write content.audit_log rows.
begin;

create type content.curator_note_status as enum (
  'draft', 'unusable', 'replaced', 'declined', 'approved', 'superseded'
);

create table if not exists content.curator_notes (
  exhibition_id text primary key,
  approved_version_id uuid,
  auto_draft_blocked boolean not null default false,
  last_source_hash text,
  updated_at timestamptz not null default now(),
  constraint curator_notes_exhibition_id_not_blank check (length(btrim(exhibition_id)) between 1 and 128)
);

comment on table content.curator_notes is
  'One row per exhibition that has ever had a curator note drafted: the live version pointer and the auto-draft block.';
comment on column content.curator_notes.auto_draft_blocked is
  'Set by a decline; the scheduler skips the exhibition until staff request a fresh draft (FR-010).';
comment on column content.curator_notes.last_source_hash is
  'Hash of the source packet the newest version was drafted from; a changed hash queues a new draft (FR-009).';

create table if not exists content.curator_note_versions (
  id uuid primary key default gen_random_uuid(),
  exhibition_id text not null references content.curator_notes(exhibition_id) on delete cascade,
  version_number integer not null,
  status content.curator_note_status not null,
  hook_ko text not null default '',
  hook_en text not null default '',
  insights_ko jsonb not null default '[]'::jsonb,
  insights_en jsonb not null default '[]'::jsonb,
  tip_ko text not null default '',
  tip_en text not null default '',
  unsupported_refs text[] not null default '{}',
  edited boolean not null default false,
  producer text not null,
  model text not null,
  prompt_version text not null,
  source_fields text[] not null,
  source_hash text not null,
  source_version_id uuid,
  request_kind text not null,
  requested_by uuid references auth.users(id) on delete set null,
  generated_at timestamptz not null,
  rejection_code text,
  reviewed_by uuid references auth.users(id) on delete set null,
  reviewer_name_ko text,
  reviewer_name_en text,
  decided_at timestamptz,
  decline_reason text,
  decline_note text,
  approved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint curator_note_versions_number_positive check (version_number >= 1),
  constraint curator_note_versions_unique_number unique (exhibition_id, version_number),
  constraint curator_note_versions_hook_length check (char_length(hook_ko) <= 120 and char_length(hook_en) <= 120),
  constraint curator_note_versions_tip_length check (char_length(tip_ko) <= 240 and char_length(tip_en) <= 240),
  constraint curator_note_versions_insights_arrays check (
    jsonb_typeof(insights_ko) = 'array' and jsonb_typeof(insights_en) = 'array'
  ),
  constraint curator_note_versions_producer_not_blank check (length(btrim(producer)) > 0),
  constraint curator_note_versions_model_not_blank check (length(btrim(model)) > 0),
  constraint curator_note_versions_prompt_version_not_blank check (length(btrim(prompt_version)) > 0),
  constraint curator_note_versions_source_hash_hex check (source_hash ~ '^[0-9a-f]{64}$'),
  constraint curator_note_versions_request_kind check (request_kind in ('scheduled', 'single', 'regenerate')),
  constraint curator_note_versions_rejection_code check (
    (status = 'unusable') = (rejection_code is not null)
    and (rejection_code is null or rejection_code in (
      'grounding_unknown_key', 'number_not_in_sources', 'language_mismatch', 'length_exceeded',
      'insight_count', 'provider_refused', 'parse_failed', 'source_changed'
    ))
  ),
  constraint curator_note_versions_decline_reason check (
    (status = 'declined') = (decline_reason is not null)
    and (decline_reason is null or decline_reason in (
      'not_insightful', 'wrong_facts', 'tone', 'exhibition_not_suitable', 'other'
    ))
  ),
  constraint curator_note_versions_decline_note_length check (decline_note is null or char_length(decline_note) <= 500),
  constraint curator_note_versions_approved_fields check (
    status not in ('approved', 'superseded')
    or (approved_at is not null and reviewer_name_ko is not null and reviewer_name_en is not null)
  )
);

comment on table content.curator_note_versions is
  'Every drafted, reviewed and published version of a curator note with its provenance (spec 090 FR-005, FR-008).';
comment on column content.curator_note_versions.unsupported_refs is
  'Sentence references (<lang>:<section>:<index>) an editor marked unsupported; approval refuses a non-empty list (FR-007).';
comment on column content.curator_note_versions.source_fields is
  'Packet keys present when the draft was made; every grounding key in the insights must be one of them (FR-003).';
comment on column content.curator_note_versions.updated_at is
  'The revision Admin sends back with every write; a mismatch raises curator_note_stale.';

create unique index if not exists curator_note_versions_one_open_draft
  on content.curator_note_versions (exhibition_id)
  where status = 'draft';
create index if not exists curator_note_versions_exhibition_status_idx
  on content.curator_note_versions (exhibition_id, status);
create index if not exists curator_note_versions_approved_at_idx
  on content.curator_note_versions (approved_at desc)
  where status = 'approved';

alter table content.curator_notes
  add constraint curator_notes_approved_version_fk
  foreign key (approved_version_id) references content.curator_note_versions(id) on delete set null
  deferrable initially deferred;

alter table content.curator_notes enable row level security;
alter table content.curator_note_versions enable row level security;
revoke all on content.curator_notes from public, anon, authenticated, service_role;
revoke all on content.curator_note_versions from public, anon, authenticated, service_role;

-- Visitors read approved notes only, and only while the exhibition is still in the public catalogue (FR-006, FR-017).
create or replace view public.curator_notes_published as
select
  version.exhibition_id,
  version.version_number,
  version.hook_ko,
  version.hook_en,
  version.insights_ko,
  version.insights_en,
  version.tip_ko,
  version.tip_en,
  version.reviewer_name_ko,
  version.reviewer_name_en,
  version.approved_at,
  catalog.closing_date
from content.curator_notes as note
join content.curator_note_versions as version on version.id = note.approved_version_id
join public.exhibition_catalog_v2 as catalog on catalog.id = note.exhibition_id
where version.status = 'approved';

comment on view public.curator_notes_published is
  'Approved curator notes of exhibitions in the public catalogue; the only read path for visitors (spec 090).';

revoke all on public.curator_notes_published from public, anon, authenticated, service_role;
grant select on public.curator_notes_published to anon, authenticated, service_role;

-- Aggregate analytics: the home carousel attributes impressions and opens to curator_note (FR-012).
alter table content.mobile_analytics_daily drop constraint if exists mobile_analytics_discovery_kind;
alter table content.mobile_analytics_daily add constraint mobile_analytics_discovery_kind check (discovery_kind in (
  'none', 'featured', 'organic', 'search', 'editor', 'event', 'gallery',
  'nearby', 'saved', 'notification', 'recommendation', 'route', 'curator_note'
));

-- == functions ==

create or replace function content_private.require_curator_note_staff()
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not content_private.has_staff_role('publisher'::content.staff_role) then
    raise exception using errcode = '42501', message = 'curator_note_not_staff';
  end if;
end;
$$;

create or replace function content_private.record_curator_note_audit(
  p_actor uuid,
  p_action text,
  p_exhibition_id text,
  p_version_id uuid,
  p_metadata jsonb
)
returns void
language sql
volatile
security definer
set search_path = ''
as $$
  insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
  values (
    p_actor, p_action, 'curator_note', p_exhibition_id,
    coalesce(p_metadata, '{}'::jsonb) || jsonb_build_object('version_id', p_version_id)
  );
$$;

-- The source packet: the published catalogue row with blank and null fields removed, plus the reviewed
-- artists and terms. Its hash identifies the facts a draft was written from (R3); the Edge Function receives
-- the hash with the packet and hands it back, and store_curator_note_draft refuses a draft whose facts changed.
create or replace function content_private.curator_note_packet(p_exhibition_id text)
returns table (packet jsonb, packet_hash text, source_version_id uuid)
language sql
stable
security definer
set search_path = ''
as $$
  with catalog_row as (
    select
      jsonb_strip_nulls(jsonb_build_object(
        'id', catalog.id,
        'name_ko', nullif(btrim(catalog.name_ko), ''),
        'name_en', nullif(btrim(catalog.name_en), ''),
        'description_ko', nullif(btrim(catalog.description_ko), ''),
        'description_en', nullif(btrim(catalog.description_en), ''),
        'venue_name_ko', nullif(btrim(catalog.venue_name_ko), ''),
        'venue_name_en', nullif(btrim(catalog.venue_name_en), ''),
        'region_ko', nullif(btrim(catalog.region_ko), ''),
        'region_en', nullif(btrim(catalog.region_en), ''),
        'city_ko', nullif(btrim(catalog.city_ko), ''),
        'city_en', nullif(btrim(catalog.city_en), ''),
        'opening_date', catalog.opening_date::text,
        'closing_date', catalog.closing_date::text,
        'hours', nullif(btrim(catalog.hours), ''),
        'opening_time', nullif(btrim(catalog.opening_time), ''),
        'reception_date', catalog.reception_date::text,
        'credits_ko', nullif(btrim(catalog.credits_ko), ''),
        'credits_en', nullif(btrim(catalog.credits_en), ''),
        'artists', case when jsonb_typeof(catalog.artists) = 'array' and jsonb_array_length(catalog.artists) > 0
                        then catalog.artists end,
        'art_terms', case when jsonb_typeof(catalog.art_terms) = 'array' and jsonb_array_length(catalog.art_terms) > 0
                          then catalog.art_terms end
      )) as packet,
      (select exhibition.published_version_id from content.exhibitions as exhibition where exhibition.id = catalog.id)
        as source_version_id
    from public.exhibition_catalog_v2 as catalog
    where catalog.id = p_exhibition_id
  )
  select
    catalog_row.packet,
    encode(extensions.digest(convert_to(catalog_row.packet::text, 'UTF8'), 'sha256'), 'hex'),
    catalog_row.source_version_id
  from catalog_row;
$$;

create or replace function content_private.curator_note_qualifies(p_exhibition_id text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.exhibition_catalog_v2 as catalog
    where catalog.id = p_exhibition_id
      and catalog.closing_date >= (now() at time zone 'Asia/Seoul')::date
      and char_length(btrim(catalog.description_ko)) >= 150
      and btrim(catalog.venue_name_ko) <> ''
      and catalog.cover_image_url is not null
  );
$$;

create or replace function content_private.curator_note_version_json(p_version_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', version.id,
    'exhibition_id', version.exhibition_id,
    'version_number', version.version_number,
    'status', version.status,
    'hook_ko', version.hook_ko,
    'hook_en', version.hook_en,
    'insights_ko', version.insights_ko,
    'insights_en', version.insights_en,
    'tip_ko', version.tip_ko,
    'tip_en', version.tip_en,
    'unsupported_refs', to_jsonb(version.unsupported_refs),
    'edited', version.edited,
    'producer', version.producer,
    'model', version.model,
    'prompt_version', version.prompt_version,
    'source_fields', to_jsonb(version.source_fields),
    'source_hash', version.source_hash,
    'source_version_id', version.source_version_id,
    'request_kind', version.request_kind,
    'generated_at', version.generated_at,
    'rejection_code', version.rejection_code,
    'reviewer_name_ko', version.reviewer_name_ko,
    'reviewer_name_en', version.reviewer_name_en,
    'decided_at', version.decided_at,
    'decline_reason', version.decline_reason,
    'decline_note', version.decline_note,
    'approved_at', version.approved_at,
    'created_at', version.created_at,
    'revision', version.updated_at
  )
  from content.curator_note_versions as version
  where version.id = p_version_id;
$$;

-- Insight arrays: 2–4 objects (English may be empty) whose text fits and whose grounding keys are packet keys.
create or replace function content_private.curator_note_insights_valid(
  p_insights jsonb,
  p_source_fields text[],
  p_allow_empty boolean
)
returns boolean
language sql
immutable
security definer
set search_path = ''
as $$
  select jsonb_typeof(p_insights) = 'array'
    and (
      (p_allow_empty and jsonb_array_length(p_insights) = 0)
      or jsonb_array_length(p_insights) between 2 and 4
    )
    and not exists (
      select 1
      from jsonb_array_elements(p_insights) as insight
      where jsonb_typeof(insight) <> 'object'
        or jsonb_typeof(insight -> 'text') <> 'string'
        or char_length(insight ->> 'text') not between 1 and 420
        or jsonb_typeof(insight -> 'grounding') <> 'array'
        or jsonb_array_length(insight -> 'grounding') = 0
        or exists (
          select 1 from jsonb_array_elements_text(insight -> 'grounding') as key
          where not (key = any (p_source_fields))
        )
    );
$$;

create or replace function content_private.list_curator_note_candidates_impl(p_limit integer)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_limit integer := least(greatest(coalesce(p_limit, 20), 1), 50);
begin
  return (
    select coalesce(jsonb_agg(candidate.row_json order by candidate.is_featured desc, candidate.opening_date desc, candidate.id), '[]'::jsonb)
    from (
      select
        catalog.id,
        catalog.is_featured,
        catalog.opening_date,
        jsonb_build_object(
          'exhibition_id', catalog.id,
          'is_featured', catalog.is_featured,
          'packet', packet.packet,
          'packet_hash', packet.packet_hash,
          'source_version_id', packet.source_version_id
        ) as row_json
      from public.exhibition_catalog_v2 as catalog
      cross join lateral content_private.curator_note_packet(catalog.id) as packet
      left join content.curator_notes as note on note.exhibition_id = catalog.id
      where content_private.curator_note_qualifies(catalog.id)
        and coalesce(note.auto_draft_blocked, false) = false
        and not exists (
          select 1 from content.curator_note_versions as open_draft
          where open_draft.exhibition_id = catalog.id and open_draft.status = 'draft'
        )
        and (
          note.approved_version_id is null
          or (select approved.source_hash from content.curator_note_versions as approved
              where approved.id = note.approved_version_id) <> packet.packet_hash
        )
        and not exists (
          select 1 from content.curator_note_versions as failed
          where failed.exhibition_id = catalog.id
            and failed.status = 'unusable'
            and failed.source_hash = packet.packet_hash
            and failed.generated_at > now() - interval '7 days'
        )
      order by catalog.is_featured desc, catalog.opening_date desc, catalog.id
      limit v_limit
    ) as candidate
  );
end;
$$;

create or replace function content_private.store_curator_note_draft_impl(p_payload jsonb)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_exhibition_id text := nullif(btrim(coalesce(p_payload ->> 'exhibition_id', '')), '');
  v_status text := p_payload ->> 'status';
  v_source_fields text[];
  v_packet record;
  v_version_id uuid;
  v_version_number integer;
  v_requested_by uuid;
begin
  if v_exhibition_id is null or v_status not in ('draft', 'unusable') then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end if;
  if jsonb_typeof(p_payload -> 'source_fields') <> 'array' then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end if;
  select array_agg(value) into v_source_fields from jsonb_array_elements_text(p_payload -> 'source_fields') as value;
  select * into v_packet from content_private.curator_note_packet(v_exhibition_id);
  if v_packet.packet_hash is null then
    raise exception using errcode = 'P0002', message = 'curator_note_not_found';
  end if;
  if coalesce(p_payload ->> 'source_hash', '') <> v_packet.packet_hash then
    raise sqlstate 'PT409' using message = 'curator_note_source_changed';
  end if;
  if v_status = 'draft' then
    if not content_private.curator_note_insights_valid(p_payload -> 'insights_ko', v_source_fields, false)
       or not content_private.curator_note_insights_valid(coalesce(p_payload -> 'insights_en', '[]'::jsonb), v_source_fields, true)
       or nullif(btrim(coalesce(p_payload ->> 'hook_ko', '')), '') is null
       or nullif(btrim(coalesce(p_payload ->> 'tip_ko', '')), '') is null then
      raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
    end if;
    if exists (
      select 1 from content.curator_note_versions as open_draft
      where open_draft.exhibition_id = v_exhibition_id and open_draft.status = 'draft'
    ) then
      raise sqlstate 'PT409' using message = 'curator_note_invalid_transition';
    end if;
  end if;
  begin
    v_requested_by := (p_payload ->> 'requested_by')::uuid;
  exception when others then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end;

  insert into content.curator_notes (exhibition_id) values (v_exhibition_id)
  on conflict (exhibition_id) do nothing;
  perform 1 from content.curator_notes where exhibition_id = v_exhibition_id for update;

  select coalesce(max(version_number), 0) + 1 into v_version_number
  from content.curator_note_versions where exhibition_id = v_exhibition_id;

  insert into content.curator_note_versions (
    exhibition_id, version_number, status,
    hook_ko, hook_en, insights_ko, insights_en, tip_ko, tip_en,
    producer, model, prompt_version, source_fields, source_hash, source_version_id,
    request_kind, requested_by, generated_at, rejection_code
  ) values (
    v_exhibition_id, v_version_number, v_status::content.curator_note_status,
    coalesce(p_payload ->> 'hook_ko', ''), coalesce(p_payload ->> 'hook_en', ''),
    coalesce(p_payload -> 'insights_ko', '[]'::jsonb), coalesce(p_payload -> 'insights_en', '[]'::jsonb),
    coalesce(p_payload ->> 'tip_ko', ''), coalesce(p_payload ->> 'tip_en', ''),
    coalesce(p_payload ->> 'producer', 'claude'), p_payload ->> 'model', p_payload ->> 'prompt_version',
    v_source_fields, v_packet.packet_hash, v_packet.source_version_id,
    p_payload ->> 'request_kind', v_requested_by,
    coalesce((p_payload ->> 'generated_at')::timestamptz, clock_timestamp()),
    case when v_status = 'unusable' then p_payload ->> 'rejection_code' end
  )
  returning id into v_version_id;

  update content.curator_notes
  set last_source_hash = v_packet.packet_hash, updated_at = clock_timestamp()
  where exhibition_id = v_exhibition_id;

  perform content_private.record_curator_note_audit(
    v_requested_by, 'curator_note_drafted', v_exhibition_id, v_version_id,
    jsonb_build_object(
      'status', v_status, 'request_kind', p_payload ->> 'request_kind',
      'model', p_payload ->> 'model', 'prompt_version', p_payload ->> 'prompt_version',
      'rejection_code', case when v_status = 'unusable' then p_payload ->> 'rejection_code' end
    )
  );
  return content_private.curator_note_version_json(v_version_id);
end;
$$;

create or replace function content_private.list_curator_note_queue_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform content_private.require_curator_note_staff();
  return (
    select coalesce(
      jsonb_agg(
        jsonb_build_object(
          'version_id', version.id,
          'exhibition_id', version.exhibition_id,
          'name_ko', coalesce(catalog.name_ko, ''),
          'name_en', coalesce(catalog.name_en, ''),
          'is_featured', coalesce(catalog.is_featured, false),
          'closing_date', catalog.closing_date,
          'version_number', version.version_number,
          'prompt_version', version.prompt_version,
          'generated_at', version.generated_at,
          'request_kind', version.request_kind,
          'unsupported_count', coalesce(array_length(version.unsupported_refs, 1), 0),
          'edited', version.edited,
          'revision', version.updated_at
        )
        order by coalesce(catalog.is_featured, false) desc, version.generated_at asc, version.id
      ),
      '[]'::jsonb
    )
    from content.curator_note_versions as version
    left join public.exhibition_catalog_v2 as catalog on catalog.id = version.exhibition_id
    where version.status = 'draft'
  );
end;
$$;

create or replace function content_private.get_curator_note_impl(p_exhibition_id text)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_note content.curator_notes%rowtype;
  v_packet record;
begin
  perform content_private.require_curator_note_staff();
  select * into v_note from content.curator_notes where exhibition_id = p_exhibition_id;
  select * into v_packet from content_private.curator_note_packet(p_exhibition_id);
  if v_note.exhibition_id is null and v_packet.packet_hash is null then
    raise exception using errcode = 'P0002', message = 'curator_note_not_found';
  end if;
  return jsonb_build_object(
    'exhibition_id', p_exhibition_id,
    'auto_draft_blocked', coalesce(v_note.auto_draft_blocked, false),
    'approved_version_id', v_note.approved_version_id,
    'qualifies', content_private.curator_note_qualifies(p_exhibition_id),
    'packet', v_packet.packet,
    'packet_hash', v_packet.packet_hash,
    'versions', coalesce((
      select jsonb_agg(content_private.curator_note_version_json(version.id) order by version.version_number desc)
      from content.curator_note_versions as version
      where version.exhibition_id = p_exhibition_id
    ), '[]'::jsonb)
  );
end;
$$;

create or replace function content_private.staff_curator_note_version_for_update(
  p_version_id uuid,
  p_expected_revision timestamptz
)
returns content.curator_note_versions
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_version content.curator_note_versions%rowtype;
begin
  perform content_private.require_curator_note_staff();
  select * into v_version from content.curator_note_versions where id = p_version_id for update;
  if v_version.id is null then
    raise exception using errcode = 'P0002', message = 'curator_note_not_found';
  end if;
  if p_expected_revision is null or v_version.updated_at <> p_expected_revision then
    raise sqlstate 'PT409' using message = 'curator_note_stale';
  end if;
  return v_version;
end;
$$;

create or replace function content_private.save_curator_note_edit_impl(
  p_version_id uuid,
  p_fields jsonb,
  p_expected_revision timestamptz
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_version content.curator_note_versions%rowtype
    := content_private.staff_curator_note_version_for_update(p_version_id, p_expected_revision);
  v_actor uuid := auth.uid();
  v_hook_ko text := coalesce(p_fields ->> 'hook_ko', v_version.hook_ko);
  v_hook_en text := coalesce(p_fields ->> 'hook_en', v_version.hook_en);
  v_tip_ko text := coalesce(p_fields ->> 'tip_ko', v_version.tip_ko);
  v_tip_en text := coalesce(p_fields ->> 'tip_en', v_version.tip_en);
  v_insights_ko jsonb := coalesce(p_fields -> 'insights_ko', v_version.insights_ko);
  v_insights_en jsonb := coalesce(p_fields -> 'insights_en', v_version.insights_en);
  v_changed text[];
begin
  if v_version.status <> 'draft' then
    raise sqlstate 'PT409' using message = 'curator_note_invalid_transition';
  end if;
  if jsonb_typeof(p_fields) <> 'object'
     or p_fields = '{}'::jsonb
     or (p_fields - array['hook_ko', 'hook_en', 'insights_ko', 'insights_en', 'tip_ko', 'tip_en']) <> '{}'::jsonb
     or char_length(v_hook_ko) > 120 or char_length(v_hook_en) > 120
     or char_length(v_tip_ko) > 240 or char_length(v_tip_en) > 240
     or not content_private.curator_note_insights_valid(v_insights_ko, v_version.source_fields, false)
     or not content_private.curator_note_insights_valid(v_insights_en, v_version.source_fields, true) then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end if;
  select array_agg(key order by key) into v_changed from jsonb_object_keys(p_fields) as key;
  update content.curator_note_versions
  set hook_ko = v_hook_ko, hook_en = v_hook_en, tip_ko = v_tip_ko, tip_en = v_tip_en,
      insights_ko = v_insights_ko, insights_en = v_insights_en,
      edited = true, updated_at = clock_timestamp()
  where id = p_version_id;
  perform content_private.record_curator_note_audit(
    v_actor, 'curator_note_edited', v_version.exhibition_id, p_version_id,
    jsonb_build_object('fields', to_jsonb(v_changed), 'revision', p_expected_revision)
  );
  return content_private.curator_note_version_json(p_version_id);
end;
$$;

create or replace function content_private.mark_curator_note_sentence_impl(
  p_version_id uuid,
  p_ref text,
  p_unsupported boolean,
  p_expected_revision timestamptz
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_version content.curator_note_versions%rowtype
    := content_private.staff_curator_note_version_for_update(p_version_id, p_expected_revision);
  v_actor uuid := auth.uid();
  v_refs text[];
begin
  if v_version.status <> 'draft' then
    raise sqlstate 'PT409' using message = 'curator_note_invalid_transition';
  end if;
  if p_ref is null or p_ref !~ '^(ko|en):(hook|insight|tip):[0-9]{1,2}$' or p_unsupported is null then
    raise exception using errcode = '22023', message = 'curator_note_invalid_ref';
  end if;
  if p_unsupported then
    v_refs := (select array(select distinct unnest(v_version.unsupported_refs || p_ref) order by 1));
  else
    v_refs := array_remove(v_version.unsupported_refs, p_ref);
  end if;
  update content.curator_note_versions
  set unsupported_refs = v_refs, updated_at = clock_timestamp()
  where id = p_version_id;
  perform content_private.record_curator_note_audit(
    v_actor, 'curator_note_sentence_marked', v_version.exhibition_id, p_version_id,
    jsonb_build_object('ref', p_ref, 'unsupported', p_unsupported, 'revision', p_expected_revision)
  );
  return content_private.curator_note_version_json(p_version_id);
end;
$$;

-- The name a published note carries: the editor profile's bilingual name when the reviewer holds an active
-- editor membership, otherwise the account's display name for both languages (R8).
create or replace function content_private.curator_note_reviewer_name(p_user uuid)
returns table (name_ko text, name_en text)
language sql
stable
security definer
set search_path = ''
as $$
  select
    coalesce(
      (select nullif(btrim(editor.name_ko), '') from content.editor_memberships as membership
       join public.editors as editor on editor.id = membership.editor_id
       where membership.user_id = p_user and membership.active),
      nullif(btrim((select profile.display_name from public.profiles as profile where profile.id = p_user)), ''),
      'gallr'
    ),
    coalesce(
      (select coalesce(nullif(btrim(editor.name_en), ''), nullif(btrim(editor.name_ko), ''))
       from content.editor_memberships as membership
       join public.editors as editor on editor.id = membership.editor_id
       where membership.user_id = p_user and membership.active),
      nullif(btrim((select profile.display_name from public.profiles as profile where profile.id = p_user)), ''),
      'gallr'
    );
$$;

create or replace function content_private.approve_curator_note_impl(
  p_version_id uuid,
  p_expected_revision timestamptz
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_version content.curator_note_versions%rowtype
    := content_private.staff_curator_note_version_for_update(p_version_id, p_expected_revision);
  v_actor uuid := auth.uid();
  v_now timestamptz := clock_timestamp();
  v_name record;
  v_previous uuid;
begin
  if v_version.status <> 'draft' then
    raise sqlstate 'PT409' using message = 'curator_note_invalid_transition';
  end if;
  if coalesce(array_length(v_version.unsupported_refs, 1), 0) > 0 then
    raise sqlstate 'PT409' using message = 'curator_note_unsupported';
  end if;
  if btrim(v_version.hook_ko) = '' or btrim(v_version.tip_ko) = ''
     or jsonb_array_length(v_version.insights_ko) not between 2 and 4 then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end if;
  select * into v_name from content_private.curator_note_reviewer_name(v_actor);
  select approved_version_id into v_previous from content.curator_notes
  where exhibition_id = v_version.exhibition_id for update;
  if v_previous is not null then
    update content.curator_note_versions
    set status = 'superseded', updated_at = v_now
    where id = v_previous and status = 'approved';
  end if;
  update content.curator_note_versions
  set status = 'approved', reviewed_by = v_actor, reviewer_name_ko = v_name.name_ko, reviewer_name_en = v_name.name_en,
      decided_at = v_now, approved_at = v_now, updated_at = v_now
  where id = p_version_id;
  update content.curator_notes
  set approved_version_id = p_version_id, auto_draft_blocked = false, updated_at = v_now
  where exhibition_id = v_version.exhibition_id;
  perform content_private.record_curator_note_audit(
    v_actor, 'curator_note_approved', v_version.exhibition_id, p_version_id,
    jsonb_build_object('superseded_version_id', v_previous, 'edited', v_version.edited, 'revision', p_expected_revision)
  );
  return content_private.curator_note_version_json(p_version_id);
end;
$$;

create or replace function content_private.decline_curator_note_impl(
  p_version_id uuid,
  p_reason text,
  p_note text,
  p_expected_revision timestamptz
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_version content.curator_note_versions%rowtype
    := content_private.staff_curator_note_version_for_update(p_version_id, p_expected_revision);
  v_actor uuid := auth.uid();
  v_now timestamptz := clock_timestamp();
  v_note text := nullif(btrim(coalesce(p_note, '')), '');
begin
  if v_version.status <> 'draft' then
    raise sqlstate 'PT409' using message = 'curator_note_invalid_transition';
  end if;
  if p_reason is null or p_reason not in ('not_insightful', 'wrong_facts', 'tone', 'exhibition_not_suitable', 'other') then
    raise exception using errcode = '22023', message = 'curator_note_invalid_reason';
  end if;
  if char_length(coalesce(v_note, '')) > 500 then
    raise exception using errcode = '22023', message = 'curator_note_invalid_note';
  end if;
  update content.curator_note_versions
  set status = 'declined', reviewed_by = v_actor, decided_at = v_now,
      decline_reason = p_reason, decline_note = v_note, updated_at = v_now
  where id = p_version_id;
  update content.curator_notes
  set auto_draft_blocked = true, updated_at = v_now
  where exhibition_id = v_version.exhibition_id;
  perform content_private.record_curator_note_audit(
    v_actor, 'curator_note_declined', v_version.exhibition_id, p_version_id,
    jsonb_build_object('reason', p_reason, 'revision', p_expected_revision)
  );
  return content_private.curator_note_version_json(p_version_id);
end;
$$;

create or replace function content_private.request_curator_note_draft_impl(p_exhibition_id text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_now timestamptz := clock_timestamp();
  v_packet record;
  v_replaced uuid;
begin
  perform content_private.require_curator_note_staff();
  select * into v_packet from content_private.curator_note_packet(p_exhibition_id);
  if v_packet.packet_hash is null then
    raise exception using errcode = 'P0002', message = 'curator_note_not_found';
  end if;
  insert into content.curator_notes (exhibition_id) values (p_exhibition_id)
  on conflict (exhibition_id) do nothing;
  update content.curator_note_versions
  set status = 'replaced', updated_at = v_now
  where exhibition_id = p_exhibition_id and status = 'draft'
  returning id into v_replaced;
  update content.curator_notes
  set auto_draft_blocked = false, updated_at = v_now
  where exhibition_id = p_exhibition_id;
  perform content_private.record_curator_note_audit(
    v_actor, 'curator_note_regenerated', p_exhibition_id, v_replaced,
    jsonb_build_object('replaced_version_id', v_replaced)
  );
  return jsonb_build_object(
    'exhibition_id', p_exhibition_id,
    'qualifies', content_private.curator_note_qualifies(p_exhibition_id),
    'packet', v_packet.packet,
    'packet_hash', v_packet.packet_hash,
    'source_version_id', v_packet.source_version_id
  );
end;
$$;

create or replace function content_private.record_curator_note_export_impl(
  p_version_id uuid,
  p_language text,
  p_slide_count integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_version content.curator_note_versions%rowtype;
  v_actor uuid := auth.uid();
begin
  perform content_private.require_curator_note_staff();
  select * into v_version from content.curator_note_versions where id = p_version_id;
  if v_version.id is null then
    raise exception using errcode = 'P0002', message = 'curator_note_not_found';
  end if;
  if v_version.status <> 'approved' then
    raise sqlstate 'PT409' using message = 'curator_note_invalid_transition';
  end if;
  if p_language not in ('ko', 'en') or p_slide_count is null or p_slide_count not between 4 and 6 then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end if;
  perform content_private.record_curator_note_audit(
    v_actor, 'curator_note_exported', v_version.exhibition_id, p_version_id,
    jsonb_build_object('language', p_language, 'slide_count', p_slide_count)
  );
  return content_private.curator_note_version_json(p_version_id);
end;
$$;

-- Daily scheduled drafting: posts to the Edge Function with the vault-held token (R1, R4).
create or replace function content_private.schedule_curator_note_drafts()
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_url text;
  v_token text;
  v_request_id bigint;
begin
  select secret.decrypted_secret into v_url
  from vault.decrypted_secrets as secret where secret.name = 'gallr_curator_notes_function_url';
  select secret.decrypted_secret into v_token
  from vault.decrypted_secrets as secret where secret.name = 'gallr_curator_notes_schedule_token';
  v_url := btrim(v_url);
  v_token := btrim(v_token);
  if v_url is null or v_token is null then
    raise exception using errcode = '55000', message = 'curator_notes_schedule_not_configured';
  end if;
  if v_url !~ '^https://[a-z0-9]+\.supabase\.co/functions/v1/draft-curator-notes$' then
    raise exception using errcode = '22023', message = 'curator_notes_function_url_is_invalid';
  end if;
  if length(v_token) < 32 or v_token ~ '[[:space:][:cntrl:]]' then
    raise exception using errcode = '22023', message = 'curator_notes_schedule_token_is_invalid';
  end if;
  select net.http_post(
    url := v_url,
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || v_token
    ),
    body := jsonb_build_object('mode', 'scheduled', 'limit', 20),
    timeout_milliseconds := 60000
  )
  into v_request_id;
  return v_request_id;
end;
$$;

-- Public wrappers. Staff functions are granted to authenticated and check the publisher tier inside;
-- service functions are granted to the service role only.
create or replace function public.service_list_curator_note_candidates(p_limit integer)
returns jsonb language sql stable security invoker set search_path = ''
as $$ select content_private.list_curator_note_candidates_impl(p_limit); $$;
create or replace function public.service_store_curator_note_draft(p_payload jsonb)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.store_curator_note_draft_impl(p_payload); $$;
create or replace function public.list_curator_note_queue()
returns jsonb language sql stable security invoker set search_path = ''
as $$ select content_private.list_curator_note_queue_impl(); $$;
create or replace function public.get_curator_note(p_exhibition_id text)
returns jsonb language sql stable security invoker set search_path = ''
as $$ select content_private.get_curator_note_impl(p_exhibition_id); $$;
create or replace function public.save_curator_note_edit(p_version_id uuid, p_fields jsonb, p_expected_revision timestamptz)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.save_curator_note_edit_impl(p_version_id, p_fields, p_expected_revision); $$;
create or replace function public.mark_curator_note_sentence(p_version_id uuid, p_ref text, p_unsupported boolean, p_expected_revision timestamptz)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.mark_curator_note_sentence_impl(p_version_id, p_ref, p_unsupported, p_expected_revision); $$;
create or replace function public.approve_curator_note(p_version_id uuid, p_expected_revision timestamptz)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.approve_curator_note_impl(p_version_id, p_expected_revision); $$;
create or replace function public.decline_curator_note(p_version_id uuid, p_reason text, p_note text, p_expected_revision timestamptz)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.decline_curator_note_impl(p_version_id, p_reason, p_note, p_expected_revision); $$;
create or replace function public.request_curator_note_draft(p_exhibition_id text)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.request_curator_note_draft_impl(p_exhibition_id); $$;
create or replace function public.record_curator_note_export(p_version_id uuid, p_language text, p_slide_count integer)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.record_curator_note_export_impl(p_version_id, p_language, p_slide_count); $$;

revoke all on function content_private.require_curator_note_staff() from public, anon, authenticated, service_role;
revoke all on function content_private.record_curator_note_audit(uuid, text, text, uuid, jsonb) from public, anon, authenticated, service_role;
revoke all on function content_private.curator_note_packet(text) from public, anon, authenticated, service_role;
revoke all on function content_private.curator_note_qualifies(text) from public, anon, authenticated, service_role;
revoke all on function content_private.curator_note_version_json(uuid) from public, anon, authenticated, service_role;
revoke all on function content_private.curator_note_insights_valid(jsonb, text[], boolean) from public, anon, authenticated, service_role;
revoke all on function content_private.list_curator_note_candidates_impl(integer) from public, anon, authenticated;
revoke all on function content_private.store_curator_note_draft_impl(jsonb) from public, anon, authenticated;
revoke all on function content_private.list_curator_note_queue_impl() from public, anon, service_role;
revoke all on function content_private.get_curator_note_impl(text) from public, anon, service_role;
revoke all on function content_private.staff_curator_note_version_for_update(uuid, timestamptz) from public, anon, authenticated, service_role;
revoke all on function content_private.save_curator_note_edit_impl(uuid, jsonb, timestamptz) from public, anon, service_role;
revoke all on function content_private.mark_curator_note_sentence_impl(uuid, text, boolean, timestamptz) from public, anon, service_role;
revoke all on function content_private.curator_note_reviewer_name(uuid) from public, anon, authenticated, service_role;
revoke all on function content_private.approve_curator_note_impl(uuid, timestamptz) from public, anon, service_role;
revoke all on function content_private.decline_curator_note_impl(uuid, text, text, timestamptz) from public, anon, service_role;
revoke all on function content_private.request_curator_note_draft_impl(text) from public, anon, service_role;
revoke all on function content_private.record_curator_note_export_impl(uuid, text, integer) from public, anon, service_role;
revoke all on function content_private.schedule_curator_note_drafts() from public, anon, authenticated, service_role;

revoke all on function public.service_list_curator_note_candidates(integer) from public, anon, authenticated;
revoke all on function public.service_store_curator_note_draft(jsonb) from public, anon, authenticated;
grant execute on function public.service_list_curator_note_candidates(integer) to service_role;
grant execute on function public.service_store_curator_note_draft(jsonb) to service_role;
grant execute on function content_private.list_curator_note_candidates_impl(integer) to service_role;
grant execute on function content_private.store_curator_note_draft_impl(jsonb) to service_role;

revoke all on function public.list_curator_note_queue() from public, anon, service_role;
revoke all on function public.get_curator_note(text) from public, anon, service_role;
revoke all on function public.save_curator_note_edit(uuid, jsonb, timestamptz) from public, anon, service_role;
revoke all on function public.mark_curator_note_sentence(uuid, text, boolean, timestamptz) from public, anon, service_role;
revoke all on function public.approve_curator_note(uuid, timestamptz) from public, anon, service_role;
revoke all on function public.decline_curator_note(uuid, text, text, timestamptz) from public, anon, service_role;
revoke all on function public.request_curator_note_draft(text) from public, anon, service_role;
revoke all on function public.record_curator_note_export(uuid, text, integer) from public, anon, service_role;
grant execute on function public.list_curator_note_queue() to authenticated;
grant execute on function public.get_curator_note(text) to authenticated;
grant execute on function public.save_curator_note_edit(uuid, jsonb, timestamptz) to authenticated;
grant execute on function public.mark_curator_note_sentence(uuid, text, boolean, timestamptz) to authenticated;
grant execute on function public.approve_curator_note(uuid, timestamptz) to authenticated;
grant execute on function public.decline_curator_note(uuid, text, text, timestamptz) to authenticated;
grant execute on function public.request_curator_note_draft(text) to authenticated;
grant execute on function public.record_curator_note_export(uuid, text, integer) to authenticated;
grant execute on function content_private.list_curator_note_queue_impl() to authenticated;
grant execute on function content_private.get_curator_note_impl(text) to authenticated;
grant execute on function content_private.save_curator_note_edit_impl(uuid, jsonb, timestamptz) to authenticated;
grant execute on function content_private.mark_curator_note_sentence_impl(uuid, text, boolean, timestamptz) to authenticated;
grant execute on function content_private.approve_curator_note_impl(uuid, timestamptz) to authenticated;
grant execute on function content_private.decline_curator_note_impl(uuid, text, text, timestamptz) to authenticated;
grant execute on function content_private.request_curator_note_draft_impl(text) to authenticated;
grant execute on function content_private.record_curator_note_export_impl(uuid, text, integer) to authenticated;

comment on function public.service_store_curator_note_draft(jsonb) is
  'Service-only: stores a drafted (or unusable) curator note version with provenance; refuses a draft whose source packet changed.';
comment on function public.approve_curator_note(uuid, timestamptz) is
  'Publisher tier: publishes a draft under the reviewer''s name; refused while any sentence is marked unsupported.';

select cron.schedule('curator_notes_daily', '0 18 * * *', 'select content_private.schedule_curator_note_drafts();')
where not exists (select 1 from cron.job where jobname = 'curator_notes_daily');

commit;
