-- Spec 101 (US5): every term and artist credit keeps the sentence that supports it, who applied it,
-- and whether the text it was checked against has changed since. Staff-only: readers never see it.

begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(68);

-- == schema and privileges ==
select has_column('content', 'exhibition_version_terms', 'grounding', 'terms carry a grounding sentence');
select has_column('content', 'exhibition_version_artists', 'grounding', 'artist credits carry a grounding line');
select has_column('content', 'exhibition_versions', 'metadata_source_hash', 'versions record the reviewed text hash');
select has_column('content', 'exhibition_versions', 'metadata_reviewed_at', 'versions record when terms were confirmed');
select has_function(
  'public', 'admin_confirm_exhibition_metadata', array['text', 'uuid', 'integer'],
  'Admin confirms terms and credits through one RPC'
);
select has_function(
  'public', 'admin_list_stale_exhibition_metadata', array[]::text[],
  'Admin lists stale exhibitions through one RPC'
);
select has_function(
  'content_private', 'exhibition_metadata_is_stale', array['uuid'],
  'staleness is one private function'
);

select ok(
  has_function_privilege('authenticated', 'public.admin_confirm_exhibition_metadata(text,uuid,integer)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.admin_list_stale_exhibition_metadata()', 'EXECUTE'),
  'signed-in callers reach the staff entry points (the role is checked inside)'
);
select ok(
  not has_function_privilege('anon', 'public.admin_confirm_exhibition_metadata(text,uuid,integer)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.admin_list_stale_exhibition_metadata()', 'EXECUTE'),
  'anonymous callers cannot confirm or list'
);
select ok(
  not has_function_privilege('anon', 'content_private.exhibition_metadata_is_stale(uuid)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'content_private.exhibition_metadata_is_stale(uuid)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'content_private.exhibition_metadata_source_hash(uuid)', 'EXECUTE'),
  'the private helpers are not API entry points'
);
select ok(
  not has_table_privilege('anon', 'content.exhibition_version_terms', 'SELECT')
  and not has_table_privilege('authenticated', 'content.exhibition_version_terms', 'SELECT')
  and not has_table_privilege('anon', 'content.exhibition_version_artists', 'SELECT')
  and not has_table_privilege('authenticated', 'content.exhibition_version_artists', 'SELECT'),
  'grounding tables stay private to every reader role'
);
select ok(
  not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and column_name in ('grounding', 'metadata_source_hash', 'metadata_reviewed_at')
  ),
  'no public table or view exposes grounding or review stamps'
);
select ok(
  (select proconfig @> array['search_path=""'] from pg_proc
   where oid = 'content_private.admin_confirm_exhibition_metadata_impl(text,uuid,integer)'::regprocedure)
  and (select prosecdef from pg_proc
   where oid = 'content_private.admin_confirm_exhibition_metadata_impl(text,uuid,integer)'::regprocedure),
  'the confirm implementation is security definer with an empty search_path'
);

-- == fixtures ==
create temp table provenance_state (key text primary key, value text not null);
grant all on provenance_state to authenticated, anon;

create function pg_temp.act_as(p_user uuid) returns void language sql as $$
  select set_config(
    'request.jwt.claims',
    json_build_object('sub', p_user, 'role', 'authenticated')::text,
    true
  );
$$;
create function pg_temp.state(p_key text) returns text language sql as $$
  select value from provenance_state where key = p_key;
$$;
create function pg_temp.remember(p_key text, p_value text) returns text language sql as $$
  insert into provenance_state (key, value) values (p_key, p_value)
  on conflict (key) do update set value = excluded.value
  returning value;
$$;
grant execute on function pg_temp.act_as(uuid), pg_temp.state(text), pg_temp.remember(text, text) to authenticated, anon;

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
  ('70000000-0000-4000-8000-000000000001', 'prov-reader@example.invalid', now(), '{}'),
  ('70000000-0000-4000-8000-000000000002', 'prov-contributor@example.invalid', now(), '{}'),
  ('70000000-0000-4000-8000-000000000003', 'prov-owner@example.invalid', now(), '{}'),
  ('70000000-0000-4000-8000-000000000004', 'prov-tagger@example.invalid', now(), '{}'),
  ('70000000-0000-4000-8000-000000000005', 'prov-admin@example.invalid', now(), '{}');
insert into content.staff_members (user_id, role, active) values
  ('70000000-0000-4000-8000-000000000002', 'contributor', true),
  ('70000000-0000-4000-8000-000000000004', 'contributor', true),
  ('70000000-0000-4000-8000-000000000005', 'admin', true);

insert into content.artists (id, name_ko, name_en, created_by, updated_by) values
  ('70000000-0000-4000-8000-0000000000a1', '근거작가', 'Grounded Artist',
   '70000000-0000-4000-8000-000000000004', '70000000-0000-4000-8000-000000000004');

insert into content.exhibitions (id, created_by, updated_by) values
  ('provenance-fixture', '70000000-0000-4000-8000-000000000004', '70000000-0000-4000-8000-000000000004'),
  ('provenance-untagged', '70000000-0000-4000-8000-000000000004', '70000000-0000-4000-8000-000000000004');

insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours,
  description_ko, description_en, created_by, updated_by
) values
  ('70000000-0000-4000-8000-000000000101', 'provenance-fixture', 1, 1, 'draft',
   '근거 있는 전시', 'Grounded Show', '장소', 'Venue', '서울', 'Seoul',
   '종로구', 'Jongno-gu', '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-12-31', '10:00-18:00',
   '고요한 회화 작업을 소개합니다.', 'Quiet paintings.',
   '70000000-0000-4000-8000-000000000004', '70000000-0000-4000-8000-000000000004'),
  ('70000000-0000-4000-8000-000000000102', 'provenance-untagged', 1, 1, 'draft',
   '태그 없는 전시', 'Untagged Show', '장소', 'Venue', '서울', 'Seoul',
   '종로구', 'Jongno-gu', '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-12-31', '10:00-18:00',
   '설명', 'Description',
   '70000000-0000-4000-8000-000000000004', '70000000-0000-4000-8000-000000000004');

insert into content.exhibition_version_artists (version_id, sort_order, artist_id, name_ko, name_en, created_by)
values ('70000000-0000-4000-8000-000000000101', 0, '70000000-0000-4000-8000-0000000000a1',
        '근거작가', 'Grounded Artist', '70000000-0000-4000-8000-000000000004');
insert into content.exhibition_version_terms (version_id, term_id, sort_order, created_by) values
  ('70000000-0000-4000-8000-000000000101', 'medium:painting', 0, '70000000-0000-4000-8000-000000000004'),
  ('70000000-0000-4000-8000-000000000101', 'mood:quiet-meditative', 1, '70000000-0000-4000-8000-000000000004');

set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000005');
select public.admin_publish_exhibition(
  'provenance-fixture', '70000000-0000-4000-8000-000000000101', 1, '70000000-0000-4000-8000-000000000901'
);
select public.admin_publish_exhibition(
  'provenance-untagged', '70000000-0000-4000-8000-000000000102', 1, '70000000-0000-4000-8000-000000000902'
);
reset role;

-- == hash and staleness ==
select is(
  content_private.exhibition_metadata_source_hash('70000000-0000-4000-8000-000000000101'),
  encode(extensions.digest(convert_to(
    '고요한 회화 작업을 소개합니다.' || chr(31) || 'Quiet paintings.' || chr(31) || '2026-09-01' || chr(31) || '2026-12-31',
    'UTF8'), 'sha256'), 'hex'),
  'the source hash is SHA-256 of both descriptions and both dates joined by a unit separator'
);
select ok(
  content_private.exhibition_metadata_is_stale('70000000-0000-4000-8000-000000000101'),
  'a tagged version that was never confirmed is stale'
);
select ok(
  not content_private.exhibition_metadata_is_stale('70000000-0000-4000-8000-000000000102'),
  'a version without terms or credits is never stale'
);

-- == unauthorized callers ==
set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000001');
select throws_ok(
  $$ select public.admin_confirm_exhibition_metadata('provenance-fixture', '70000000-0000-4000-8000-000000000101', 2) $$,
  '42501', 'active_staff_membership_required',
  'a signed-in reader cannot confirm terms'
);
select throws_ok(
  $$ select public.admin_list_stale_exhibition_metadata() $$,
  '42501', 'active_staff_membership_required',
  'a signed-in reader cannot list stale exhibitions'
);
reset role;
set local role anon;
select throws_ok(
  $$ select public.admin_confirm_exhibition_metadata('provenance-fixture', '70000000-0000-4000-8000-000000000101', 2) $$,
  '42501', null,
  'an anonymous caller cannot confirm terms'
);
reset role;

-- == the stale list ==
set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000002');
select is(
  (
    select item - 'name_ko' - 'name_en'
    from jsonb_array_elements(public.admin_list_stale_exhibition_metadata()) as item
    where item ->> 'exhibition_id' = 'provenance-fixture'
  ),
  '{"exhibition_id": "provenance-fixture", "metadata_stale": true, "hours_stale": false}'::jsonb,
  'the stale list names the never-confirmed exhibition'
);
select is(
  (
    select count(*)::integer
    from jsonb_array_elements(public.admin_list_stale_exhibition_metadata()) as item
    where item ->> 'exhibition_id' = 'provenance-untagged'
  ),
  0,
  'an untagged exhibition is not listed'
);

-- == grounding through the draft patch ==
select pg_temp.remember('published_checksum', (
  select content_checksum_sha256 from public.exhibition_catalog_v2 where id = 'provenance-fixture'
));
with saved as (
  select public.admin_save_exhibition_draft(
    'provenance-fixture', '70000000-0000-4000-8000-000000000101', 2,
    jsonb_build_object(
      'artists', jsonb_build_array(jsonb_build_object(
        'id', '70000000-0000-4000-8000-0000000000a1',
        'grounding', '참여 작가: 근거작가'
      )),
      'art_term_ids', jsonb_build_array('medium:painting', 'mood:quiet-meditative', 'theme:memory'),
      'art_term_grounding', jsonb_build_object(
        'medium:painting', '고요한 회화 작업을 소개합니다.',
        'theme:memory', '  기억에 관한 전시  '
      )
    )
  ) as payload
)
select pg_temp.remember('draft_id', payload ->> 'working_version_id')
  , pg_temp.remember('draft_revision', payload ->> 'revision')
  , pg_temp.remember('saved_payload', payload::text)
from saved;
reset role;

select is(
  (select grounding from content.exhibition_version_artists
   where version_id = pg_temp.state('draft_id')::uuid and sort_order = 0),
  '참여 작가: 근거작가',
  'an artist credit stores its grounding line'
);
select is(
  (select grounding from content.exhibition_version_terms
   where version_id = pg_temp.state('draft_id')::uuid and term_id = 'medium:painting'),
  '고요한 회화 작업을 소개합니다.',
  'a term stores its grounding sentence'
);
select is(
  (select grounding from content.exhibition_version_terms
   where version_id = pg_temp.state('draft_id')::uuid and term_id = 'theme:memory'),
  '기억에 관한 전시',
  'grounding is trimmed'
);
select is(
  (select grounding from content.exhibition_version_terms
   where version_id = pg_temp.state('draft_id')::uuid and term_id = 'mood:quiet-meditative'),
  null,
  'a term without grounding stores null'
);
select is(
  (select created_by::text from content.exhibition_version_terms
   where version_id = pg_temp.state('draft_id')::uuid and term_id = 'theme:memory'),
  '70000000-0000-4000-8000-000000000002',
  'created_by of a newly applied term is the staff member who applied it'
);
select is(
  (select created_by::text from content.exhibition_version_terms
   where version_id = pg_temp.state('draft_id')::uuid and term_id = 'medium:painting'),
  '70000000-0000-4000-8000-000000000004',
  'created_by of a kept term stays the staff member who first applied it'
);
select is(
  (pg_temp.state('saved_payload')::jsonb -> 'artists' -> 0 ->> 'grounding'),
  '참여 작가: 근거작가',
  'the Admin response carries each credit''s grounding'
);
select is(
  (pg_temp.state('saved_payload')::jsonb -> 'art_term_grounding'),
  '{"medium:painting": "고요한 회화 작업을 소개합니다.", "mood:quiet-meditative": null, "theme:memory": "기억에 관한 전시"}'::jsonb,
  'the Admin response carries a term id to grounding object'
);
select is(
  (pg_temp.state('saved_payload')::jsonb -> 'art_terms' -> 0 ->> 'id'),
  'medium:painting',
  'the existing term list is unchanged beside the grounding object'
);
select ok(
  (pg_temp.state('saved_payload')::jsonb ? 'metadata_stale')
  and (pg_temp.state('saved_payload')::jsonb ? 'metadata_reviewed_at'),
  'the Admin response carries the stale flag and the review time'
);

-- Omission keeps grounding; an explicit null clears it.
set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000002');
select pg_temp.remember('draft_revision', (
  public.admin_save_exhibition_draft(
    'provenance-fixture', pg_temp.state('draft_id')::uuid, pg_temp.state('draft_revision')::integer,
    jsonb_build_object(
      'artists', jsonb_build_array(jsonb_build_object('id', '70000000-0000-4000-8000-0000000000a1')),
      'art_term_ids', jsonb_build_array('medium:painting', 'mood:quiet-meditative', 'theme:memory')
    )
  ) ->> 'revision'
));
reset role;
select is(
  (select grounding from content.exhibition_version_artists
   where version_id = pg_temp.state('draft_id')::uuid and sort_order = 0)
  || ' / ' ||
  (select grounding from content.exhibition_version_terms
   where version_id = pg_temp.state('draft_id')::uuid and term_id = 'medium:painting'),
  '참여 작가: 근거작가 / 고요한 회화 작업을 소개합니다.',
  'a save that omits grounding keeps the stored grounding'
);

set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000002');
select pg_temp.remember('draft_revision', (
  public.admin_save_exhibition_draft(
    'provenance-fixture', pg_temp.state('draft_id')::uuid, pg_temp.state('draft_revision')::integer,
    '{"art_term_grounding": {"theme:memory": null, "mood:quiet-meditative": "고요한"}}'::jsonb
  ) ->> 'revision'
));
reset role;
select is(
  (select jsonb_object_agg(term_id, grounding) from content.exhibition_version_terms
   where version_id = pg_temp.state('draft_id')::uuid),
  '{"medium:painting": "고요한 회화 작업을 소개합니다.", "mood:quiet-meditative": "고요한", "theme:memory": null}'::jsonb,
  'art_term_grounding alone edits grounding on the draft''s terms; null clears'
);

-- Invalid grounding fails closed.
set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000002');
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, %s, %L::jsonb)',
    'provenance-fixture', pg_temp.state('draft_id'), pg_temp.state('draft_revision'),
    '{"art_term_ids": ["medium:painting"], "art_term_grounding": {"theme:memory": "x"}}'
  ),
  '22023', 'art_metadata_term_grounding_invalid',
  'grounding for a term that is not in art_term_ids is refused'
);
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, %s, %L::jsonb)',
    'provenance-fixture', pg_temp.state('draft_id'), pg_temp.state('draft_revision'),
    '{"art_term_grounding": {"style:abstract": "x"}}'
  ),
  '22023', 'art_metadata_term_grounding_invalid',
  'grounding for a term that is not on the draft is refused'
);
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, %s, %L::jsonb)',
    'provenance-fixture', pg_temp.state('draft_id'), pg_temp.state('draft_revision'),
    jsonb_build_object('art_term_grounding', jsonb_build_object('medium:painting', repeat('가', 401)))::text
  ),
  '22023', 'art_metadata_term_grounding_invalid',
  'term grounding over 400 characters is refused'
);
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, %s, %L::jsonb)',
    'provenance-fixture', pg_temp.state('draft_id'), pg_temp.state('draft_revision'),
    '{"art_term_grounding": ["medium:painting"]}'
  ),
  '22023', 'art_metadata_term_grounding_invalid',
  'art_term_grounding must be an object'
);
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, %s, %L::jsonb)',
    'provenance-fixture', pg_temp.state('draft_id'), pg_temp.state('draft_revision'),
    jsonb_build_object('artists', jsonb_build_array(jsonb_build_object(
      'id', '70000000-0000-4000-8000-0000000000a1', 'grounding', repeat('a', 401)
    )))::text
  ),
  '22023', 'art_metadata_artist_grounding_invalid',
  'artist grounding over 400 characters is refused'
);
reset role;

-- == readers never see grounding ==
set local role anon;
select is(
  (select array_agg(key order by key) from public.exhibition_catalog_v2 as catalog,
     jsonb_object_keys(catalog.artists -> 0) as key
   where catalog.id = 'provenance-fixture'),
  array['id', 'name_en', 'name_ko'],
  'the public catalogue artist entry has no grounding'
);
select is(
  (select array_agg(key order by key) from public.exhibition_catalog_v2 as catalog,
     jsonb_object_keys(catalog.art_terms -> 0) as key
   where catalog.id = 'provenance-fixture'),
  array['category', 'id', 'name_en', 'name_ko'],
  'the public catalogue term entry has no grounding'
);
reset role;
select is(
  (select content_checksum_sha256 from public.exhibition_catalog_v2 where id = 'provenance-fixture'),
  pg_temp.state('published_checksum'),
  'grounding on the draft leaves the published catalogue row unchanged'
);

-- == confirm ==
set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000002');
select throws_ok(
  format(
    'select public.admin_confirm_exhibition_metadata(%L, %L::uuid, %s)',
    'provenance-fixture', pg_temp.state('draft_id'), pg_temp.state('draft_revision')::integer - 1
  ),
  'PT409', 'exhibition_metadata_stale_revision',
  'a stale revision is refused'
);
select throws_ok(
  $$ select public.admin_confirm_exhibition_metadata('provenance-fixture', '70000000-0000-4000-8000-000000000101', 2) $$,
  'PT409', 'exhibition_metadata_stale_revision',
  'confirming against the published version while a draft is open is refused'
);
select throws_ok(
  $$ select public.admin_confirm_exhibition_metadata('provenance-missing', '70000000-0000-4000-8000-000000000101', 2) $$,
  'P0002', 'exhibition_not_found',
  'an unknown exhibition is refused'
);
select pg_temp.remember('confirmed', public.admin_confirm_exhibition_metadata(
  'provenance-fixture', pg_temp.state('draft_id')::uuid, pg_temp.state('draft_revision')::integer
)::text);
reset role;

select is(
  (pg_temp.state('confirmed')::jsonb ->> 'metadata_stale'),
  'false',
  'the confirm response reports the draft as fresh'
);
select is(
  (pg_temp.state('confirmed')::jsonb ->> 'revision'),
  pg_temp.state('draft_revision'),
  'confirming is a review stamp and leaves the draft revision unchanged'
);
select ok(
  not content_private.exhibition_metadata_is_stale(pg_temp.state('draft_id')::uuid)
  and not content_private.exhibition_metadata_is_stale('70000000-0000-4000-8000-000000000101'),
  'confirm stamps both the open draft and the published version'
);
select is(
  (select metadata_source_hash from content.exhibition_versions where id = pg_temp.state('draft_id')::uuid),
  content_private.exhibition_metadata_source_hash(pg_temp.state('draft_id')::uuid),
  'each version stores the hash of its own text'
);
select ok(
  (select metadata_reviewed_at is not null from content.exhibition_versions
   where id = '70000000-0000-4000-8000-000000000101'),
  'the review time is recorded'
);
select is(
  (select count(*)::integer from content.audit_log
   where action = 'exhibition.metadata_confirmed' and entity_id = 'provenance-fixture'
     and actor_user_id = '70000000-0000-4000-8000-000000000002'),
  1,
  'confirming writes one audit row attributed to the staff member'
);
select is(
  (select content_checksum_sha256 from public.exhibition_catalog_v2 where id = 'provenance-fixture'),
  pg_temp.state('published_checksum'),
  'confirming changes nothing readers see'
);
set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000002');
select is(
  (select count(*)::integer
   from jsonb_array_elements(public.admin_list_stale_exhibition_metadata()) as item
   where item ->> 'exhibition_id' = 'provenance-fixture'),
  0,
  'a confirmed exhibition leaves the stale list'
);

-- == a description change makes the draft stale, publishing carries it ==
select pg_temp.remember('draft_revision', (
  public.admin_save_exhibition_draft(
    'provenance-fixture', pg_temp.state('draft_id')::uuid, pg_temp.state('draft_revision')::integer,
    '{"description_ko": "다른 설명으로 바뀌었습니다."}'::jsonb
  ) ->> 'revision'
));
reset role;
select ok(
  content_private.exhibition_metadata_is_stale(pg_temp.state('draft_id')::uuid)
  and not content_private.exhibition_metadata_is_stale('70000000-0000-4000-8000-000000000101'),
  'editing the draft description makes the draft stale and leaves the published version fresh'
);
set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000002');
select is(
  (select item ->> 'metadata_stale'
   from jsonb_array_elements(public.admin_list_stale_exhibition_metadata()) as item
   where item ->> 'exhibition_id' = 'provenance-fixture'),
  'true',
  'a stale draft puts the exhibition back on the list'
);
select pg_temp.act_as('70000000-0000-4000-8000-000000000005');
select public.admin_publish_exhibition(
  'provenance-fixture', pg_temp.state('draft_id')::uuid, pg_temp.state('draft_revision')::integer,
  '70000000-0000-4000-8000-000000000903'
);
reset role;
select ok(
  content_private.exhibition_metadata_is_stale(
    (select published_version_id from content.exhibitions where id = 'provenance-fixture')
  ),
  'a published description change is stale until confirmed'
);
set local role anon;
select is(
  (select description_ko from public.exhibition_catalog_v2 where id = 'provenance-fixture'),
  '다른 설명으로 바뀌었습니다.',
  'a stale exhibition stays visible to readers'
);
reset role;

-- == the next version inherits grounding, appliers and the review stamp ==
select pg_temp.remember('published_revision', (
  select revision::text from content.exhibition_versions where id = pg_temp.state('draft_id')::uuid
));
set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000002');
with confirmed as (
  select public.admin_confirm_exhibition_metadata(
    'provenance-fixture', pg_temp.state('draft_id')::uuid, pg_temp.state('published_revision')::integer
  ) as payload
)
select pg_temp.remember('published_revision', payload ->> 'revision') from confirmed;
with saved as (
  select public.admin_save_exhibition_draft(
    'provenance-fixture', pg_temp.state('draft_id')::uuid, pg_temp.state('published_revision')::integer,
    '{}'::jsonb
  ) as payload
)
select pg_temp.remember('second_draft_id', payload ->> 'working_version_id')
  , pg_temp.remember('second_draft_revision', payload ->> 'revision')
from saved;
reset role;
select is(
  (select jsonb_object_agg(term_id, jsonb_build_array(grounding, created_by))
   from content.exhibition_version_terms where version_id = pg_temp.state('second_draft_id')::uuid),
  jsonb_build_object(
    'medium:painting', jsonb_build_array('고요한 회화 작업을 소개합니다.', '70000000-0000-4000-8000-000000000004'),
    'mood:quiet-meditative', jsonb_build_array('고요한', '70000000-0000-4000-8000-000000000004'),
    'theme:memory', jsonb_build_array(null, '70000000-0000-4000-8000-000000000002')
  ),
  'a cloned version copies grounding and keeps who applied each term'
);
select is(
  (select grounding from content.exhibition_version_artists
   where version_id = pg_temp.state('second_draft_id')::uuid and sort_order = 0),
  '참여 작가: 근거작가',
  'a cloned version copies credit grounding'
);
select ok(
  not content_private.exhibition_metadata_is_stale(pg_temp.state('second_draft_id')::uuid),
  'a cloned version inherits the review stamp of the confirmed version it came from'
);

-- A date change is stale too.
set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000002');
select public.admin_save_exhibition_draft(
  'provenance-fixture', pg_temp.state('second_draft_id')::uuid, pg_temp.state('second_draft_revision')::integer,
  '{"closing_date": "2027-01-31"}'::jsonb
);
reset role;
select ok(
  content_private.exhibition_metadata_is_stale(pg_temp.state('second_draft_id')::uuid),
  'a closing date change makes the version stale'
);

-- == owners may send grounding; it stays private ==
insert into content.venues (
  id, slug, name_ko, name_en, address_ko, address_en, city_ko, city_en, region_ko, region_en, latitude, longitude
) values (
  '70000000-0000-4000-8000-000000000201', 'provenance-owner-gallery', '근거 갤러리', 'Grounded Gallery',
  '서울특별시 종로구 삼청로 70', '70 Samcheong-ro', '서울', 'Seoul', '종로구', 'Jongno-gu', 37.58, 126.98
);
insert into content.galleries (id, canonical_venue_id, name_ko, name_en, status, created_by, updated_by)
values ('70000000-0000-4000-8000-000000000211', '70000000-0000-4000-8000-000000000201',
        '근거 갤러리', 'Grounded Gallery', 'active',
        '70000000-0000-4000-8000-000000000003', '70000000-0000-4000-8000-000000000003');
insert into content.gallery_memberships (
  gallery_id, user_id, status, claim_website_url, reviewed_at, created_by, updated_by
) values (
  '70000000-0000-4000-8000-000000000211', '70000000-0000-4000-8000-000000000003', 'active',
  'https://grounded.example.invalid', now(),
  '70000000-0000-4000-8000-000000000003', '70000000-0000-4000-8000-000000000003'
);

set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000003');
with created as (
  select public.owner_create_exhibition_draft('70000000-0000-4000-8000-000000000904') as payload
)
select pg_temp.remember('owner_exhibition_id', payload ->> 'id')
  , pg_temp.remember('owner_version_id', payload ->> 'working_version_id')
from created;
select lives_ok(
  format(
    'select public.owner_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    pg_temp.state('owner_exhibition_id'), pg_temp.state('owner_version_id'),
    '{"artists": [{"id": null, "name_ko": "새 작가", "name_en": "New Artist", "grounding": "출품: 새 작가"}],
      "art_term_ids": ["style:experimental"],
      "art_term_grounding": {"style:experimental": "실험적인 작업"}}'
  ),
  'an owner may send grounding with their suggestions'
);
select ok(
  not (
    (select listed.payload -> 'artists' -> 0 from public.owner_list_exhibitions() as listed(payload)
     where listed.payload ->> 'id' = pg_temp.state('owner_exhibition_id')) ? 'grounding'
  ),
  'the owner workspace response does not echo grounding'
);
reset role;
select is(
  (select grounding from content.exhibition_version_artists
   where version_id = pg_temp.state('owner_version_id')::uuid)
  || ' / ' ||
  (select grounding from content.exhibition_version_terms
   where version_id = pg_temp.state('owner_version_id')::uuid),
  '출품: 새 작가 / 실험적인 작업',
  'owner grounding is stored for staff review'
);

-- An owner's private, unsubmitted draft stays invisible to staff, as in admin_get_exhibition.
select ok(
  content_private.exhibition_metadata_is_stale(pg_temp.state('owner_version_id')::uuid),
  'the owner draft has unconfirmed terms'
);
set local role authenticated;
select pg_temp.act_as('70000000-0000-4000-8000-000000000002');
select is(
  (select count(*)::integer
   from jsonb_array_elements(public.admin_list_stale_exhibition_metadata()) as item
   where item ->> 'exhibition_id' = pg_temp.state('owner_exhibition_id')),
  0,
  'the stale list does not reveal an owner''s private draft'
);
select throws_ok(
  format(
    'select public.admin_confirm_exhibition_metadata(%L, %L::uuid, 2)',
    pg_temp.state('owner_exhibition_id'), pg_temp.state('owner_version_id')
  ),
  'P0002', 'exhibition_not_found',
  'staff cannot confirm terms on an owner''s private draft'
);
reset role;
select is(
  (select metadata_reviewed_at from content.exhibition_versions
   where id = pg_temp.state('owner_version_id')::uuid),
  null,
  'the refused confirm stamped nothing'
);

select is(
  public.admin_reconcile_exhibition_catalog_v2() ->> 'in_sync',
  'true',
  'canonical reconciliation stays in sync'
);

select * from finish();
rollback;
