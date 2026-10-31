-- Spec 101 (US5): an admin finds artists that are the same person under two rows and merges them,
-- moving every credit to the survivor while keeping the labels readers already saw.

begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(44);

-- == schema and privileges ==
select has_function('content_private', 'normalize_artist_name', array['text'], 'artist names have one normaliser');
select has_function('public', 'admin_find_duplicate_artists', array[]::text[], 'Admin has a duplicate report');
select has_function('public', 'admin_merge_artists', array['uuid', 'uuid', 'text'], 'Admin has a merge command');
select ok(
  has_function_privilege('authenticated', 'public.admin_find_duplicate_artists()', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.admin_merge_artists(uuid,uuid,text)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.admin_find_duplicate_artists()', 'EXECUTE')
  and not has_function_privilege('anon', 'public.admin_merge_artists(uuid,uuid,text)', 'EXECUTE'),
  'only signed-in callers reach the report and the merge (the admin role is checked inside)'
);
select ok(
  (select provolatile = 'i' from pg_proc where oid = 'content_private.normalize_artist_name(text)'::regprocedure)
  and not has_function_privilege('anon', 'content_private.normalize_artist_name(text)', 'EXECUTE'),
  'the normaliser is immutable (index-safe) and private'
);

-- == normalisation ==
select is(content_private.normalize_artist_name('Ｋｉｍ　Ａｒｔｉｓｔ'), 'kimartist', 'full-width letters and spaces fold');
select is(content_private.normalize_artist_name('KIM-ARTIST'), 'kimartist', 'case and hyphens fold');
select is(content_private.normalize_artist_name(' Kim,  Artist. '), 'kimartist', 'punctuation and runs of spaces fold');
select is(content_private.normalize_artist_name('김 작가'), '김작가', 'Korean spacing folds');
select is(content_private.normalize_artist_name('이·우환'), '이우환', 'a middle dot folds');
select is(content_private.normalize_artist_name('“Lee” – Ufan'), 'leeufan', 'curly quotes and dashes fold');
select is(content_private.normalize_artist_name(' ·- '), null, 'a name of only spaces and punctuation normalises to null');

-- == fixtures (duplicates are inserted with the uniqueness guard set aside inside this test) ==
do $set_aside_uniqueness$
begin
  if exists (select 1 from pg_trigger where tgname = 'artists_guard_duplicate_name') then
    alter table content.artists disable trigger artists_guard_duplicate_name;
  end if;
end;
$set_aside_uniqueness$;
drop index if exists content.artists_active_name_ko_normalized_key;
drop index if exists content.artists_active_name_en_normalized_key;

create function pg_temp.act_as(p_user uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', p_user, 'role', 'authenticated')::text, true);
$$;
create function pg_temp.error_of(p_sql text) returns text language plpgsql as $$
declare
  v_state text;
  v_message text;
  v_detail text;
begin
  execute p_sql;
  return 'no error';
exception when others then
  get stacked diagnostics v_state = returned_sqlstate, v_message = message_text, v_detail = pg_exception_detail;
  return v_state || '|' || v_message || '|' || coalesce(v_detail, '');
end;
$$;
grant execute on function pg_temp.act_as(uuid), pg_temp.error_of(text) to authenticated, anon;

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
  ('72000000-0000-4000-8000-000000000001', 'merge-reader@example.invalid', now(), '{}'),
  ('72000000-0000-4000-8000-000000000002', 'merge-publisher@example.invalid', now(), '{}'),
  ('72000000-0000-4000-8000-000000000003', 'merge-admin@example.invalid', now(), '{}');
insert into content.staff_members (user_id, role, active) values
  ('72000000-0000-4000-8000-000000000002', 'publisher', true),
  ('72000000-0000-4000-8000-000000000003', 'admin', true);

insert into content.artists (id, name_ko, name_en, archived_at, created_at) values
  ('72000000-0000-4000-8000-0000000000a1', '김합본', 'Kim Merge', null, now() - interval '3 days'),
  ('72000000-0000-4000-8000-0000000000a2', '김 합본', 'KIM-MERGE', null, now() - interval '2 days'),
  ('72000000-0000-4000-8000-0000000000a3', '박고유', 'Park Unique', null, now()),
  ('72000000-0000-4000-8000-0000000000a4', '최보관', 'Choi Archived', now(), now()),
  ('72000000-0000-4000-8000-0000000000a5', '최 보관', 'Choi Active', null, now()),
  ('72000000-0000-4000-8000-0000000000a6', '정한글', 'Jung Hangul', null, now()),
  ('72000000-0000-4000-8000-0000000000a7', '정 한글', 'Chung Hangul', null, now());

insert into content.exhibitions (id, created_by, updated_by) values
  ('merge-both', '72000000-0000-4000-8000-000000000003', '72000000-0000-4000-8000-000000000003'),
  ('merge-moved', '72000000-0000-4000-8000-000000000003', '72000000-0000-4000-8000-000000000003');
insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status, published_at,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours, created_by, updated_by
)
select version_id, exhibition_id, 1, 1, 'published', now(),
  '합본 전시', 'Merge Show', '장소', 'Venue', '서울', 'Seoul',
  '종로구', 'Jongno-gu', '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-12-31', '10:00-18:00',
  '72000000-0000-4000-8000-000000000003', '72000000-0000-4000-8000-000000000003'
from (values
  ('72000000-0000-4000-8000-000000000101'::uuid, 'merge-both'),
  ('72000000-0000-4000-8000-000000000102'::uuid, 'merge-moved')
) as fixture(version_id, exhibition_id);

insert into content.exhibition_version_artists (version_id, sort_order, artist_id, name_ko, name_en, grounding) values
  ('72000000-0000-4000-8000-000000000101', 0, '72000000-0000-4000-8000-0000000000a1', '김합본', 'Kim Merge', null),
  ('72000000-0000-4000-8000-000000000101', 1, '72000000-0000-4000-8000-0000000000a2', '김 합본', 'KIM-MERGE', '출품: 김 합본'),
  ('72000000-0000-4000-8000-000000000102', 0, '72000000-0000-4000-8000-0000000000a2', '김 합본', 'KIM-MERGE', null),
  ('72000000-0000-4000-8000-000000000102', 1, '72000000-0000-4000-8000-0000000000a3', '박고유', 'Park Unique', null);

update content.exhibitions set published_version_id = '72000000-0000-4000-8000-000000000101' where id = 'merge-both';
update content.exhibitions set published_version_id = '72000000-0000-4000-8000-000000000102' where id = 'merge-moved';

-- An open draft of merge-moved; the clone trigger copies its credits.
insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours, created_by, updated_by
) values (
  '72000000-0000-4000-8000-000000000103', 'merge-moved', 2, 1, 'draft',
  '합본 전시', 'Merge Show', '장소', 'Venue', '서울', 'Seoul',
  '종로구', 'Jongno-gu', '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-12-31', '10:00-18:00',
  '72000000-0000-4000-8000-000000000003', '72000000-0000-4000-8000-000000000003'
);

-- == unauthorized callers ==
set local role authenticated;
select pg_temp.act_as('72000000-0000-4000-8000-000000000001');
select throws_ok(
  $$ select public.admin_find_duplicate_artists() $$,
  '42501', 'active_staff_membership_required', 'a signed-in reader cannot read the duplicate report'
);
select throws_ok(
  $$ select public.admin_merge_artists('72000000-0000-4000-8000-0000000000a1', '72000000-0000-4000-8000-0000000000a2', 'same') $$,
  '42501', 'active_staff_membership_required', 'a signed-in reader cannot merge'
);
select pg_temp.act_as('72000000-0000-4000-8000-000000000002');
select throws_ok(
  $$ select public.admin_find_duplicate_artists() $$,
  '42501', 'insufficient_staff_role', 'a publisher cannot read the duplicate report'
);
select throws_ok(
  $$ select public.admin_merge_artists('72000000-0000-4000-8000-0000000000a1', '72000000-0000-4000-8000-0000000000a2', 'same') $$,
  '42501', 'insufficient_staff_role', 'a publisher cannot merge'
);
reset role;
set local role anon;
select throws_ok(
  $$ select public.admin_merge_artists('72000000-0000-4000-8000-0000000000a1', '72000000-0000-4000-8000-0000000000a2', 'same') $$,
  '42501', null, 'an anonymous caller cannot merge'
);
reset role;

-- == the duplicate report ==
set local role authenticated;
select pg_temp.act_as('72000000-0000-4000-8000-000000000003');
select is(
  (select jsonb_agg(item -> 'key' order by item ->> 'key') from jsonb_array_elements(public.admin_find_duplicate_artists()) as item),
  '["ko:김합본", "ko:정한글"]'::jsonb,
  'active artists sharing a normalised Korean name group once; an English group with the same members is not repeated; archived artists are ignored'
);
select is(
  (
    select item -> 'artists'
    from jsonb_array_elements(public.admin_find_duplicate_artists()) as item
    where item ->> 'key' = 'ko:김합본'
  ),
  '[
    {"id": "72000000-0000-4000-8000-0000000000a2", "name_ko": "김 합본", "name_en": "KIM-MERGE", "credit_count": 2, "archived": false},
    {"id": "72000000-0000-4000-8000-0000000000a1", "name_ko": "김합본", "name_en": "Kim Merge", "credit_count": 1, "archived": false}
  ]'::jsonb,
  'each group lists its artists with how many exhibitions credit them, most credited first'
);

-- == refusals ==
select throws_ok(
  $$ select public.admin_merge_artists('72000000-0000-4000-8000-0000000000a1', '72000000-0000-4000-8000-0000000000a1', 'same') $$,
  '22023', 'artist_merge_invalid', 'an artist cannot be merged into itself'
);
select is(
  pg_temp.error_of($$ select public.admin_merge_artists('72000000-0000-4000-8000-0000000000a1', '72000000-0000-4000-8000-0000000000a1', 'same') $$),
  '22023|artist_merge_invalid|same_artist',
  'the refusal names the reason in the detail'
);
select is(
  pg_temp.error_of($$ select public.admin_merge_artists('72000000-0000-4000-8000-0000000000a1', '72000000-0000-4000-8000-0000000000a4', 'same') $$),
  '22023|artist_merge_invalid|merged_archived',
  'an archived artist cannot be merged'
);
select is(
  pg_temp.error_of($$ select public.admin_merge_artists('72000000-0000-4000-8000-0000000000a4', '72000000-0000-4000-8000-0000000000a5', 'same') $$),
  '22023|artist_merge_invalid|survivor_archived',
  'an archived artist cannot survive a merge'
);
select is(
  pg_temp.error_of($$ select public.admin_merge_artists('72000000-0000-4000-8000-0000000000ff', '72000000-0000-4000-8000-0000000000a2', 'same') $$),
  '22023|artist_merge_invalid|survivor_not_found',
  'a missing survivor is refused'
);
select is(
  pg_temp.error_of($$ select public.admin_merge_artists('72000000-0000-4000-8000-0000000000a1', '72000000-0000-4000-8000-0000000000ff', 'same') $$),
  '22023|artist_merge_invalid|merged_not_found',
  'a missing merged artist is refused'
);
select is(
  pg_temp.error_of($$ select public.admin_merge_artists('72000000-0000-4000-8000-0000000000a1', '72000000-0000-4000-8000-0000000000a2', '   ') $$),
  '22023|artist_merge_invalid|reason_invalid',
  'a blank reason is refused'
);
select is(
  pg_temp.error_of(format(
    'select public.admin_merge_artists(%L, %L, %L)',
    '72000000-0000-4000-8000-0000000000a1', '72000000-0000-4000-8000-0000000000a2', repeat('x', 401)
  )),
  '22023|artist_merge_invalid|reason_invalid',
  'a reason over 400 characters is refused'
);

-- == merge ==
select is(
  public.admin_merge_artists(
    '72000000-0000-4000-8000-0000000000a1', '72000000-0000-4000-8000-0000000000a2', '같은 작가의 두 표기'
  ),
  '{"survivor_id": "72000000-0000-4000-8000-0000000000a1", "merged_id": "72000000-0000-4000-8000-0000000000a2", "moved_credits": 2, "removed_duplicate_credits": 1}'::jsonb,
  'the merge reports moved and removed credits'
);
reset role;

select ok(
  (select archived_at is not null from content.artists where id = '72000000-0000-4000-8000-0000000000a2'),
  'the merged artist is archived'
);
select is(
  (select count(*)::integer from content.exhibition_version_artists where artist_id = '72000000-0000-4000-8000-0000000000a2'),
  0,
  'no credit points at the merged artist'
);
select is(
  (select jsonb_agg(jsonb_build_array(artist_id, name_ko, name_en) order by sort_order)
   from content.exhibition_version_artists where version_id = '72000000-0000-4000-8000-000000000102'),
  '[["72000000-0000-4000-8000-0000000000a1", "김 합본", "KIM-MERGE"], ["72000000-0000-4000-8000-0000000000a3", "박고유", "Park Unique"]]'::jsonb,
  'a published credit is re-pointed and keeps the label readers saw'
);
select is(
  (select artist_id::text from content.exhibition_version_artists
   where version_id = '72000000-0000-4000-8000-000000000103' and sort_order = 0),
  '72000000-0000-4000-8000-0000000000a1',
  'a draft credit is re-pointed'
);
select is(
  (select array_agg(artist_id::text order by sort_order)
   from content.exhibition_version_artists where version_id = '72000000-0000-4000-8000-000000000101'),
  array['72000000-0000-4000-8000-0000000000a1'],
  'a credit that would duplicate the survivor on one version is removed'
);
set local role anon;
select is(
  (select artists -> 0 ->> 'id' from public.exhibition_catalog_v2 where id = 'merge-moved'),
  '72000000-0000-4000-8000-0000000000a1',
  'the public catalogue points at the survivor'
);
select is(
  (select jsonb_array_length(artists) from public.exhibition_catalog_v2 where id = 'merge-both'),
  1,
  'the public catalogue drops the duplicate credit'
);
reset role;
select is(
  (select metadata - array['moved_credits', 'removed_duplicate_credits', 'moved', 'removed',
                            'bumped_drafts', 'survivor_labels', 'merged_labels']
   from content.audit_log
   where action = 'artist.merged' and entity_id = '72000000-0000-4000-8000-0000000000a2'),
  '{"survivor_id": "72000000-0000-4000-8000-0000000000a1", "merged_id": "72000000-0000-4000-8000-0000000000a2", "reason": "같은 작가의 두 표기"}'::jsonb,
  'the merge writes an audit row with survivor, merged and reason'
);
select is(
  (select (metadata -> 'removed' -> 0) - 'created_at' - 'created_by' from content.audit_log
   where action = 'artist.merged' and entity_id = '72000000-0000-4000-8000-0000000000a2'),
  '{"version_id": "72000000-0000-4000-8000-000000000101", "sort_order": 1, "name_ko": "김 합본", "name_en": "KIM-MERGE", "grounding": "출품: 김 합본"}'::jsonb,
  'the audit row keeps each removed credit with its position, labels and grounding'
);
select is(
  (select jsonb_agg(item ->> 'version_id' order by item ->> 'version_id')
   from content.audit_log, jsonb_array_elements(metadata -> 'moved') as item
   where action = 'artist.merged' and entity_id = '72000000-0000-4000-8000-0000000000a2'),
  '["72000000-0000-4000-8000-000000000102", "72000000-0000-4000-8000-000000000103"]'::jsonb,
  'the audit row lists every moved credit'
);
select is(
  (select metadata -> 'merged_labels' from content.audit_log
   where action = 'artist.merged' and entity_id = '72000000-0000-4000-8000-0000000000a2'),
  '{"name_ko": "김 합본", "name_en": "KIM-MERGE"}'::jsonb,
  'the audit row keeps the merged identity''s names'
);
select is(
  (select jsonb_object_agg(id, revision) from content.exhibition_versions
   where id in ('72000000-0000-4000-8000-000000000101', '72000000-0000-4000-8000-000000000102',
                '72000000-0000-4000-8000-000000000103')),
  '{"72000000-0000-4000-8000-000000000101": 1, "72000000-0000-4000-8000-000000000102": 1, "72000000-0000-4000-8000-000000000103": 2}'::jsonb,
  'an open draft the merge changed moves to a new revision; published versions keep theirs'
);
select is(
  (select actor_user_id::text from content.audit_log
   where action = 'artist.merged' and entity_id = '72000000-0000-4000-8000-0000000000a2'),
  '72000000-0000-4000-8000-000000000003',
  'the audit row names the admin'
);

set local role authenticated;
select pg_temp.act_as('72000000-0000-4000-8000-000000000003');
select is(
  (select jsonb_agg(item -> 'key' order by item ->> 'key') from jsonb_array_elements(public.admin_find_duplicate_artists()) as item),
  '["ko:정한글"]'::jsonb,
  'a merged group leaves the report'
);
select is(
  pg_temp.error_of($$ select public.admin_merge_artists('72000000-0000-4000-8000-0000000000a1', '72000000-0000-4000-8000-0000000000a2', 'again') $$),
  '22023|artist_merge_invalid|merged_archived',
  'a repeated merge is refused because the merged artist is already archived'
);
reset role;

select is(
  public.admin_reconcile_exhibition_catalog_v2() ->> 'in_sync',
  'true',
  'canonical reconciliation stays in sync after a merge'
);

select * from finish();
rollback;
