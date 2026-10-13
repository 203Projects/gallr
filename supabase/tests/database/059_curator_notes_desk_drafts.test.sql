-- Migration 20261013100000: the store function derives provenance from the live packet, binds the
-- published version, requires a publisher-tier requester, refuses drafts over a decline or an approved
-- note unless the requester overrides, and lifts a decline on override. The daily API schedule is gone.
-- The two candidate queries below are the exact text supabase/functions/draft-curator-notes/desk.ts
-- prints (desk_test.ts asserts this file contains them), so the printed SQL is the SQL that runs.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(31);

insert into auth.users (id, email, email_confirmed_at, is_anonymous, raw_user_meta_data) values
  ('00000000-0000-4000-8000-00000000da01', 'desk-publisher@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000da02', 'desk-former-staff@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000da03', 'desk-visitor@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000da04', 'desk-contributor@example.invalid', now(), false, '{}');
insert into content.staff_members (user_id, role, active) values
  ('00000000-0000-4000-8000-00000000da01', 'publisher', true),
  ('00000000-0000-4000-8000-00000000da02', 'publisher', false),
  ('00000000-0000-4000-8000-00000000da04', 'contributor', true);

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256, cover_image_url
)
select
  row.id, row.id, row.id, '갤러리', 'Gallery', '서울', 'Seoul', '용산구', 'Yongsan-gu',
  seoul.today - 10, seoul.today + 30, false, 37.53, 127.00, repeat('데스크 설명. ', 20), 'An English description.',
  '', '', false, now(), false, repeat('0', 64), 'https://cdn.example.invalid/desk.jpg'
from (select (now() at time zone 'Asia/Seoul')::date as today) as seoul,
  (values ('cd-one'), ('cd-two'), ('cd-bound')) as row(id);

-- cd-bound has a published version in the canonical lineage, so its packet carries source_version_id.
insert into content.exhibitions (id, created_by, updated_by)
values ('cd-bound', '00000000-0000-4000-8000-00000000da01', '00000000-0000-4000-8000-00000000da01');
insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status, name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en, opening_date, closing_date, latitude, longitude,
  description_ko, description_en, hours, country_code, published_at, created_by, updated_by
)
select
  '59000000-0000-4000-8000-000000000301', 'cd-bound', 1, 1, 'published', 'cd-bound', 'cd-bound', '갤러리', 'Gallery',
  '서울', 'Seoul', '용산구', 'Yongsan-gu', '서울특별시 용산구 이태원로 1', '1 Itaewon-ro, Yongsan-gu, Seoul',
  seoul.today - 10, seoul.today + 30, 37.53, 127.00, repeat('데스크 설명. ', 20), 'An English description.',
  'Tue-Sun 10:00-18:00', 'KR', now(), '00000000-0000-4000-8000-00000000da01', '00000000-0000-4000-8000-00000000da01'
from (select (now() at time zone 'Asia/Seoul')::date as today) as seoul;
update content.exhibitions set published_version_id = '59000000-0000-4000-8000-000000000301' where id = 'cd-bound';
update public.exhibition_catalog_v2 set cover_image_url = 'https://cdn.example.invalid/desk.jpg' where id = 'cd-bound';

create function pg_temp.packet_hash(p_exhibition text) returns text
language sql security definer as $$
  select packet_hash from content_private.curator_note_packet(p_exhibition);
$$;
create function pg_temp.version_id(p_exhibition text, p_number integer) returns uuid
language sql security definer as $$
  select id from content.curator_note_versions where exhibition_id = p_exhibition and version_number = p_number;
$$;
create function pg_temp.revision(p_exhibition text, p_number integer) returns timestamptz
language sql security definer as $$
  select updated_at from content.curator_note_versions where exhibition_id = p_exhibition and version_number = p_number;
$$;
-- A desk payload: the grounding key and the caller's source_fields list are parameters so the tests can
-- show that only the live packet decides what counts as a source.
create function pg_temp.desk_payload(p_exhibition text, p_grounding text, p_source_fields jsonb, p_requested_by text) returns jsonb
language sql security definer as $$
  select jsonb_build_object(
    'exhibition_id', p_exhibition, 'status', 'draft',
    'hook_ko', '데스크에서 쓴 훅', 'hook_en', '',
    'insights_ko', jsonb_build_array(
      jsonb_build_object('text', '첫 번째 통찰.', 'grounding', jsonb_build_array(p_grounding)),
      jsonb_build_object('text', '두 번째 통찰.', 'grounding', jsonb_build_array('description_ko'))
    ),
    'insights_en', '[]'::jsonb, 'tip_ko', '입구부터 보라.', 'tip_en', '',
    'producer', 'claude-desk', 'model', 'claude-desk-test', 'prompt_version', '2026-10-13.1',
    'source_fields', p_source_fields, 'source_hash', pg_temp.packet_hash(p_exhibition),
    'request_kind', 'single', 'requested_by', p_requested_by, 'generated_at', now()::text
  );
$$;
grant execute on function pg_temp.packet_hash(text), pg_temp.version_id(text, integer), pg_temp.revision(text, integer),
  pg_temp.desk_payload(text, text, jsonb, text) to authenticated;

select is(content_private.curator_note_desk_contract(), 1, 'the target states the desk contract version');

-- Provenance comes from the live packet, not the payload.
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-one', 'wikipedia', '["wikipedia", "description_ko"]', '00000000-0000-4000-8000-00000000da01'))$$,
  '22023', 'curator_note_invalid_fields', 'a grounding key the live packet lacks is refused even when the payload lists it'
);
select is(
  (select stored ->> 'status' from content_private.store_curator_note_draft_impl(
     pg_temp.desk_payload('cd-one', 'name_ko', '["wikipedia"]', '00000000-0000-4000-8000-00000000da01')) as stored),
  'draft', 'a desk draft is stored'
);
select ok(
  (select 'description_ko' = any (source_fields) and not ('wikipedia' = any (source_fields))
   from content.curator_note_versions where exhibition_id = 'cd-one' and version_number = 1),
  'the stored source fields are the live packet keys, not the supplied list'
);
select is(
  (select producer || '/' || request_kind || '/' || requested_by::text
   from content.curator_note_versions where exhibition_id = 'cd-one' and version_number = 1),
  'claude-desk/single/00000000-0000-4000-8000-00000000da01', 'the version records the desk producer and the requesting staff member'
);
select is(
  (select actor_user_id::text || '/' || (metadata ->> 'producer') from content.audit_log
   where entity_type = 'curator_note' and entity_id = 'cd-one' and action = 'curator_note_drafted'),
  '00000000-0000-4000-8000-00000000da01/claude-desk', 'the audit row names the requester and the producer'
);

-- The published version binds the catalogue.
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(
      pg_temp.desk_payload('cd-bound', 'name_ko', '[]', '00000000-0000-4000-8000-00000000da01')
      || jsonb_build_object('source_version_id', '11111111-2222-4333-8444-555555555555'))$$,
  'PT409', 'curator_note_source_changed', 'a draft bound to another published version is refused'
);
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-bound', 'name_ko', '[]', '00000000-0000-4000-8000-00000000da01'))$$,
  'PT409', 'curator_note_source_changed', 'a draft that names no published version is refused when the exhibition has one'
);
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(
      pg_temp.desk_payload('cd-two', 'name_ko', '[]', '00000000-0000-4000-8000-00000000da01')
      || jsonb_build_object('source_version_id', 'not-a-uuid'))$$,
  '22023', 'curator_note_invalid_fields', 'a malformed version id is refused'
);
select is(
  (select stored ->> 'status' from content_private.store_curator_note_draft_impl(
     pg_temp.desk_payload('cd-bound', 'name_ko', '[]', '00000000-0000-4000-8000-00000000da01')
     || jsonb_build_object('source_version_id', '59000000-0000-4000-8000-000000000301')) as stored),
  'draft', 'a draft bound to the live published version is stored'
);
select is(
  (select source_version_id::text from content.curator_note_versions where exhibition_id = 'cd-bound' and version_number = 1),
  '59000000-0000-4000-8000-000000000301', 'the stored version carries the live published version'
);

-- The requester must be an active publisher or admin; a desk draft always has one.
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-two', 'name_ko', '[]', '00000000-0000-4000-8000-00000000da03'))$$,
  '42501', 'curator_note_requester_not_staff', 'a visitor cannot be the requester'
);
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-two', 'name_ko', '[]', '00000000-0000-4000-8000-00000000da02'))$$,
  '42501', 'curator_note_requester_not_staff', 'a former staff member cannot be the requester'
);
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-two', 'name_ko', '[]', '00000000-0000-4000-8000-00000000da04'))$$,
  '42501', 'curator_note_requester_not_staff', 'a contributor cannot be the requester'
);
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-two', 'name_ko', '[]', 'not-a-uuid'))$$,
  '22023', 'curator_note_invalid_fields', 'a malformed requester id is refused'
);
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-two', 'name_ko', '[]', null))$$,
  '22023', 'curator_note_invalid_fields', 'a desk draft without a requester is refused'
);
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-two', 'name_ko', '[]', null) || '{"producer": "hand"}'::jsonb)$$,
  '23514', null, 'an unknown producer is refused by the table'
);

-- An approved note that covers the current packet needs an explicit override.
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000da01', true);
select is(
  (select approved ->> 'status' from public.approve_curator_note(pg_temp.version_id('cd-one', 1), pg_temp.revision('cd-one', 1)) as approved),
  'approved', 'the desk draft can be approved'
);
reset role;
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-one', 'name_ko', '[]', '00000000-0000-4000-8000-00000000da01'))$$,
  'PT409', 'curator_note_already_approved', 'a draft over an approved note with the same packet is refused without an override'
);
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-one', 'name_ko', '[]', null) || '{"producer": "claude", "override": true}'::jsonb)$$,
  '22023', 'curator_note_invalid_fields', 'an override needs a requester'
);
select is(
  (select stored ->> 'version_number' from content_private.store_curator_note_draft_impl(
     pg_temp.desk_payload('cd-one', 'name_ko', '[]', '00000000-0000-4000-8000-00000000da01') || '{"override": true}'::jsonb) as stored),
  '2', 'an overriding staff-requested draft over an approved note is stored'
);

-- A decline blocks drafts; an override lifts the block and says so.
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000da01', true);
select is(
  (select declined ->> 'status' from public.decline_curator_note(pg_temp.version_id('cd-one', 2), 'tone', null, pg_temp.revision('cd-one', 2)) as declined),
  'declined', 'the second draft is declined'
);
reset role;
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-one', 'name_ko', '[]', null) || '{"producer": "claude"}'::jsonb)$$,
  'PT409', 'curator_note_blocked', 'an automatic draft after a decline is refused'
);
select throws_ok(
  $$select content_private.store_curator_note_draft_impl(pg_temp.desk_payload('cd-one', 'name_ko', '[]', '00000000-0000-4000-8000-00000000da01'))$$,
  'PT409', 'curator_note_blocked', 'a staff-requested draft after a decline is refused without an override'
);
select is(
  (select stored ->> 'version_number' from content_private.store_curator_note_draft_impl(
     pg_temp.desk_payload('cd-one', 'name_ko', '[]', '00000000-0000-4000-8000-00000000da01') || '{"override": true}'::jsonb) as stored),
  '3', 'an overriding draft after a decline is stored'
);
select ok(
  not (select auto_draft_blocked from content.curator_notes where exhibition_id = 'cd-one'),
  'the override lifts the block'
);
select is(
  (select actor_user_id::text from content.audit_log
   where entity_type = 'curator_note' and entity_id = 'cd-one' and action = 'curator_note_unblocked'),
  '00000000-0000-4000-8000-00000000da01', 'lifting the block is audited with the actor'
);

-- The desk's named-candidates query, exactly as desk.ts prints it.
select is(
  (select string_agg(
     (candidate ->> 'exhibition_id') || ':' || (candidate ->> 'contract') || ':' || (candidate ->> 'found') || ':' || (candidate ->> 'qualifies')
       || ':' || (candidate ->> 'has_open_draft') || ':' || (candidate ->> 'auto_draft_blocked')
       || ':' || (candidate ->> 'approved_same_hash') || ':' || coalesce(length(candidate ->> 'packet_hash')::text, '-'),
     ' ' order by (candidate ->> 'exhibition_id'))
   from jsonb_array_elements((
with wanted as (
  select wanted.id, wanted.position, packet.packet, packet.packet_hash, packet.source_version_id
  from unnest(array['cd-one', 'cd-missing']::text[]) with ordinality as wanted(id, position)
  left join lateral content_private.curator_note_packet(wanted.id) as packet on true
)
select coalesce(jsonb_agg(jsonb_build_object(
  'exhibition_id', wanted.id,
  'contract', content_private.curator_note_desk_contract(),
  'found', wanted.packet_hash is not null,
  'qualifies', content_private.curator_note_qualifies(wanted.id),
  'has_open_draft', exists (
    select 1 from content.curator_note_versions as open_draft
    where open_draft.exhibition_id = wanted.id and open_draft.status = 'draft'
  ),
  'auto_draft_blocked', coalesce(note.auto_draft_blocked, false),
  'approved_same_hash', exists (
    select 1 from content.curator_note_versions as approved
    where approved.id = note.approved_version_id and approved.source_hash = wanted.packet_hash
  ),
  'packet', wanted.packet,
  'packet_hash', wanted.packet_hash,
  'source_version_id', wanted.source_version_id
) order by wanted.position), '[]'::jsonb) as candidates
from wanted
left join content.curator_notes as note on note.exhibition_id = wanted.id
   )) as candidate),
  'cd-missing:1:false:false:false:false:false:- cd-one:1:true:true:true:false:true:64',
  'the named-candidates query reports the contract and the found, qualifying, open-draft, blocked and approved states'
);

-- The desk's queue query, exactly as desk.ts prints it: cd-two and cd-bound are waiting (cd-one has an open draft).
select is(
  (select string_agg((candidate ->> 'exhibition_id') || ':' || (candidate ->> 'found') || ':' || (candidate ->> 'qualifies')
     || ':' || coalesce(candidate ->> 'source_version_id', '-'), ' ' order by (candidate ->> 'exhibition_id'))
   from jsonb_array_elements((
with wanted as (
  select queued.candidate ->> 'exhibition_id' as id, queued.position,
    queued.candidate -> 'packet' as packet, queued.candidate ->> 'packet_hash' as packet_hash,
    (queued.candidate ->> 'source_version_id')::uuid as source_version_id
  from jsonb_array_elements(content_private.list_curator_note_candidates_impl(50))
    with ordinality as queued(candidate, position)
)
select coalesce(jsonb_agg(jsonb_build_object(
  'exhibition_id', wanted.id,
  'contract', content_private.curator_note_desk_contract(),
  'found', wanted.packet_hash is not null,
  'qualifies', content_private.curator_note_qualifies(wanted.id),
  'has_open_draft', exists (
    select 1 from content.curator_note_versions as open_draft
    where open_draft.exhibition_id = wanted.id and open_draft.status = 'draft'
  ),
  'auto_draft_blocked', coalesce(note.auto_draft_blocked, false),
  'approved_same_hash', exists (
    select 1 from content.curator_note_versions as approved
    where approved.id = note.approved_version_id and approved.source_hash = wanted.packet_hash
  ),
  'packet', wanted.packet,
  'packet_hash', wanted.packet_hash,
  'source_version_id', wanted.source_version_id
) order by wanted.position), '[]'::jsonb) as candidates
from wanted
left join content.curator_notes as note on note.exhibition_id = wanted.id
   )) as candidate),
  'cd-two:true:true:-',
  'the queue query reports the waiting exhibition (cd-one and cd-bound hold open drafts)'
);

select has_function('content_private', 'schedule_curator_note_drafts', array[]::text[], 'the API schedule function remains for a hand re-enable');
select ok(not exists (select 1 from cron.job where jobname = 'curator_notes_daily'), 'the daily API drafting job is no longer scheduled');

select * from finish();
