-- Migration 20261013110000: a draft whose exhibition text changed since it was written is marked stale in the
-- queue and on the version, and cannot be approved until a new draft is written from the current text.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(11);

insert into auth.users (id, email, email_confirmed_at, is_anonymous, raw_user_meta_data) values
  ('00000000-0000-4000-8000-00000000db01', 'stale-publisher@example.invalid', now(), false, '{}');
insert into content.staff_members (user_id, role, active) values ('00000000-0000-4000-8000-00000000db01', 'publisher', true);

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256, cover_image_url
)
select
  row.id, row.id, row.id, '갤러리', 'Gallery', '서울', 'Seoul', '용산구', 'Yongsan-gu',
  seoul.today - 10, seoul.today + 30, false, 37.53, 127.00, repeat('원래 설명. ', 20), 'An English description.',
  '', '', false, now(), false, repeat('0', 64), 'https://cdn.example.invalid/stale.jpg'
from (select (now() at time zone 'Asia/Seoul')::date as today) as seoul,
  (values ('cs-edited'), ('cs-gone')) as row(id);

create function pg_temp.draft(p_exhibition text) returns jsonb
language sql security definer as $$
  select content_private.store_curator_note_draft_impl(jsonb_build_object(
    'exhibition_id', p_exhibition, 'status', 'draft',
    'hook_ko', '훅', 'hook_en', '',
    'insights_ko', jsonb_build_array(
      jsonb_build_object('text', '첫 번째 통찰.', 'grounding', jsonb_build_array('description_ko')),
      jsonb_build_object('text', '두 번째 통찰.', 'grounding', jsonb_build_array('description_ko'))
    ),
    'insights_en', '[]'::jsonb, 'tip_ko', '입구부터 보라.', 'tip_en', '',
    'producer', 'claude', 'model', 'claude-test', 'prompt_version', '2026-10-13.1',
    'source_fields', '[]'::jsonb,
    'source_hash', (select packet_hash from content_private.curator_note_packet(p_exhibition)),
    'request_kind', 'scheduled', 'requested_by', null, 'generated_at', now()::text
  ));
$$;
create function pg_temp.version_id(p_exhibition text) returns uuid
language sql security definer as $$
  select id from content.curator_note_versions where exhibition_id = p_exhibition and version_number = 1;
$$;
create function pg_temp.revision(p_exhibition text) returns timestamptz
language sql security definer as $$
  select updated_at from content.curator_note_versions where exhibition_id = p_exhibition and version_number = 1;
$$;
grant execute on function pg_temp.draft(text), pg_temp.version_id(text), pg_temp.revision(text) to authenticated;

select is(pg_temp.draft('cs-edited') ->> 'stale', 'false', 'a fresh draft is not stale');
select is(pg_temp.draft('cs-gone') ->> 'status', 'draft', 'a second draft is stored');

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000db01', true);
select is(
  (select string_agg((item ->> 'exhibition_id') || ':' || (item ->> 'stale'), ' ' order by item ->> 'exhibition_id')
   from jsonb_array_elements(public.list_curator_note_queue()) as item),
  'cs-edited:false cs-gone:false', 'the queue marks nothing stale while the packets are unchanged'
);
reset role;

-- The exhibition text changes after the draft was written; the other exhibition leaves the catalogue.
update public.exhibition_catalog_v2 set description_ko = repeat('바뀐 설명. ', 20) where id = 'cs-edited';
delete from public.exhibition_catalog_v2 where id = 'cs-gone';

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000db01', true);
select is(
  (select string_agg((item ->> 'exhibition_id') || ':' || (item ->> 'stale'), ' ' order by item ->> 'exhibition_id')
   from jsonb_array_elements(public.list_curator_note_queue()) as item),
  'cs-edited:true cs-gone:true', 'the queue marks a draft stale when its text changed or its exhibition left the catalogue'
);
select is(
  (select version ->> 'stale' from jsonb_array_elements(public.get_curator_note('cs-edited') -> 'versions') as version),
  'true', 'the version carries the stale flag'
);
select throws_ok(
  $$select public.approve_curator_note(pg_temp.version_id('cs-edited'), pg_temp.revision('cs-edited'))$$,
  'PT409', 'curator_note_source_changed', 'a stale draft cannot be approved'
);
select throws_ok(
  $$select public.approve_curator_note(pg_temp.version_id('cs-gone'), pg_temp.revision('cs-gone'))$$,
  'PT409', 'curator_note_source_changed', 'a draft whose exhibition left the catalogue cannot be approved'
);
reset role;
select is(
  (select status::text from content.curator_note_versions where exhibition_id = 'cs-edited'),
  'draft', 'the refused draft stays a draft'
);

-- Editing the draft is still allowed, and once the text is restored the draft is current again.
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000db01', true);
select is(
  (select edited ->> 'stale' from public.save_curator_note_edit(
     pg_temp.version_id('cs-edited'), '{"hook_ko": "고친 훅"}'::jsonb, pg_temp.revision('cs-edited')) as edited),
  'true', 'a stale draft can still be edited and stays stale'
);
reset role;
update public.exhibition_catalog_v2 set description_ko = repeat('원래 설명. ', 20) where id = 'cs-edited';
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000db01', true);
select is(
  (select approved ->> 'status' from public.approve_curator_note(pg_temp.version_id('cs-edited'), pg_temp.revision('cs-edited')) as approved),
  'approved', 'a draft whose packet matches the current text again can be approved'
);
reset role;
select is(
  (select count(*)::text from public.curator_notes_published where exhibition_id = 'cs-edited'),
  '1', 'the approved note is published'
);

select * from finish();
