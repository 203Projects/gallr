begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(60);

select has_type(
  'content',
  'art_taxonomy_category',
  'controlled art metadata has a fixed category type'
);

select has_table('content', 'artists', 'canonical artist identities exist');
select has_table(
  'content',
  'art_taxonomy_terms',
  'controlled art taxonomy terms exist'
);
select has_table(
  'content',
  'exhibition_version_artists',
  'artist credits are attached to exhibition versions'
);
select has_table(
  'content',
  'exhibition_version_terms',
  'controlled terms are attached to exhibition versions'
);
select has_trigger(
  'content',
  'art_taxonomy_terms',
  'art_taxonomy_terms_guard_semantics',
  'controlled taxonomy semantics have an immutable-row trigger'
);

select has_column(
  'public',
  'exhibition_catalog_v2',
  'artists',
  'the canonical public catalogue embeds artist metadata'
);
select has_column(
  'public',
  'exhibition_catalog_v2',
  'art_terms',
  'the canonical public catalogue embeds controlled terms'
);

select has_function(
  'public',
  'admin_search_artists',
  array['text', 'integer'],
  'Admin has a bounded artist lookup RPC'
);
select has_function(
  'public',
  'admin_create_artist',
  array['text', 'text', 'uuid'],
  'Admin has an idempotent artist creation RPC'
);
select has_function(
  'public',
  'owner_search_artists',
  array['text', 'integer'],
  'Gallery has an owner-scoped artist lookup RPC'
);
select has_function(
  'public',
  'owner_list_art_terms',
  array[]::text[],
  'Gallery can read the controlled taxonomy through an owner RPC'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_class as relation
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname in (
        'artists',
        'art_taxonomy_terms',
        'exhibition_version_artists',
        'exhibition_version_terms'
      )
      and relation.relrowsecurity
  ),
  4,
  'RLS is enabled on every private art metadata table'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_type as type
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = type.typnamespace
    where namespace.nspname = 'content'
      and type.typname = 'art_taxonomy_category'
      and enum_range(null::content.art_taxonomy_category)::text[] = array[
        'medium', 'style', 'theme', 'mood'
      ]::text[]
  ),
  1,
  'taxonomy categories have the reviewed stable order'
);

select is(
  (
    select count(*)::integer
    from content.art_taxonomy_terms
  ),
  28,
  'the initial controlled vocabulary contains exactly twenty-eight terms'
);

select ok(
  not has_table_privilege('anon', 'content.artists', 'SELECT, INSERT, UPDATE, DELETE')
  and not has_table_privilege(
    'authenticated',
    'content.artists',
    'SELECT, INSERT, UPDATE, DELETE'
  )
  and not has_table_privilege(
    'authenticated',
    'content.exhibition_version_artists',
    'SELECT, INSERT, UPDATE, DELETE'
  ),
  'artist identities and version links have no direct reader-role privileges'
);

select ok(
  not has_table_privilege(
    'anon',
    'content.art_taxonomy_terms',
    'SELECT, INSERT, UPDATE, DELETE'
  )
  and not has_table_privilege(
    'authenticated',
    'content.art_taxonomy_terms',
    'SELECT, INSERT, UPDATE, DELETE'
  )
  and not has_table_privilege(
    'authenticated',
    'content.exhibition_version_terms',
    'SELECT, INSERT, UPDATE, DELETE'
  ),
  'taxonomy and version links have no direct reader-role privileges'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.admin_search_artists(text,integer)',
    'EXECUTE'
  )
  and has_function_privilege(
    'authenticated',
    'public.admin_create_artist(text,text,uuid)',
    'EXECUTE'
  )
  and has_function_privilege(
    'authenticated',
    'public.owner_search_artists(text,integer)',
    'EXECUTE'
  )
  and has_function_privilege(
    'authenticated',
    'public.owner_list_art_terms()',
    'EXECUTE'
  ),
  'authenticated callers receive only explicit art metadata RPC entry points'
);

select ok(
  not has_function_privilege(
    'anon',
    'public.admin_search_artists(text,integer)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.admin_create_artist(text,text,uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.owner_search_artists(text,integer)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.owner_list_art_terms()',
    'EXECUTE'
  ),
  'anonymous callers cannot enter art metadata workflow RPCs'
);

select ok(
  has_table_privilege('anon', 'public.exhibition_catalog_v2', 'SELECT')
  and not has_table_privilege(
    'anon',
    'public.exhibition_catalog_v2',
    'INSERT, UPDATE, DELETE'
  ),
  'the expanded public catalogue remains read-only for anonymous clients'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
  (
    '39000000-0000-0000-0000-000000000001',
    'art-normal@example.invalid',
    now(),
    '{}'::jsonb
  ),
  (
    '39000000-0000-0000-0000-000000000002',
    'art-contributor@example.invalid',
    now(),
    '{}'::jsonb
  ),
  (
    '39000000-0000-0000-0000-000000000003',
    'art-owner@example.invalid',
    now(),
    '{}'::jsonb
  ),
  (
    '39000000-0000-0000-0000-000000000004',
    'art-other-owner@example.invalid',
    now(),
    '{}'::jsonb
  );

insert into content.staff_members (user_id, role, active)
values (
  '39000000-0000-0000-0000-000000000002',
  'publisher'::content.staff_role,
  true
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"39000000-0000-0000-0000-000000000001","role":"authenticated"}',
  true
);
select throws_ok(
  $$select * from public.admin_search_artists('artist', 20)$$,
  '42501',
  'active_staff_membership_required',
  'a normal authenticated account cannot search private artist identities'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"39000000-0000-0000-0000-000000000002","role":"authenticated"}',
  true
);

create temp table art_metadata_test_state (
  key text primary key,
  value text not null
);

with created as (
  select public.admin_create_artist(
    '김작가',
    'Kim Artist',
    '39000000-0000-0000-0000-000000000101'
  ) as payload
)
insert into art_metadata_test_state (key, value)
select 'artist_id', payload ->> 'id' from created;

select ok(
  (select value::uuid from art_metadata_test_state where key = 'artist_id')
    is not null,
  'staff creates a stable canonical artist identity'
);

select is(
  (
    select public.admin_create_artist(
      '김작가',
      'Kim Artist',
      '39000000-0000-0000-0000-000000000101'
    ) ->> 'id'
  ),
  (select value from art_metadata_test_state where key = 'artist_id'),
  'artist creation replays the stored response for one request identity'
);

select throws_ok(
  $$select public.admin_create_artist(
    '다른 작가',
    'Different Artist',
    '39000000-0000-0000-0000-000000000101'
  )$$,
  '22023',
  'idempotency_key_reused_with_different_request',
  'artist creation refuses request identity reuse with different names'
);

select is(
  (
    select count(*)::integer
    from public.admin_search_artists('kim', 20)
  ),
  1,
  'staff artist search is bilingual and returns the created identity'
);

select throws_ok(
  $$select * from public.admin_search_artists('kim', 21)$$,
  '22023',
  'artist_search_limit_invalid',
  'artist search rejects an excessive result limit'
);

select is(
  jsonb_array_length(
    public.admin_get_exhibition_lookups() -> 'art_terms'
  ),
  28,
  'the established Admin lookup RPC includes all controlled art terms'
);

reset role;

insert into content.venues (
  id,
  slug,
  name_ko,
  name_en,
  address_ko,
  address_en,
  city_ko,
  city_en,
  region_ko,
  region_en,
  latitude,
  longitude
)
values
  (
    '39000000-0000-0000-0000-000000000201',
    'art-owner-gallery',
    '아트 오너 갤러리',
    'Art Owner Gallery',
    '서울특별시 종로구 삼청로 10',
    '10 Samcheong-ro, Jongno-gu, Seoul',
    '서울',
    'Seoul',
    '종로구',
    'Jongno-gu',
    37.582,
    126.981
  ),
  (
    '39000000-0000-0000-0000-000000000202',
    'art-other-gallery',
    '다른 갤러리',
    'Other Gallery',
    '서울특별시 종로구 삼청로 12',
    '12 Samcheong-ro, Jongno-gu, Seoul',
    '서울',
    'Seoul',
    '종로구',
    'Jongno-gu',
    37.583,
    126.982
  );

insert into content.galleries (
  id,
  canonical_venue_id,
  name_ko,
  name_en,
  status,
  created_by,
  updated_by
)
values
  (
    '39000000-0000-0000-0000-000000000211',
    '39000000-0000-0000-0000-000000000201',
    '아트 오너 갤러리',
    'Art Owner Gallery',
    'active',
    '39000000-0000-0000-0000-000000000003',
    '39000000-0000-0000-0000-000000000003'
  ),
  (
    '39000000-0000-0000-0000-000000000212',
    '39000000-0000-0000-0000-000000000202',
    '다른 갤러리',
    'Other Gallery',
    'active',
    '39000000-0000-0000-0000-000000000004',
    '39000000-0000-0000-0000-000000000004'
  );

insert into content.gallery_memberships (
  gallery_id,
  user_id,
  status,
  claim_website_url,
  reviewed_at,
  created_by,
  updated_by
)
values
  (
    '39000000-0000-0000-0000-000000000211',
    '39000000-0000-0000-0000-000000000003',
    'active',
    'https://art-owner.example.invalid',
    now(),
    '39000000-0000-0000-0000-000000000003',
    '39000000-0000-0000-0000-000000000003'
  ),
  (
    '39000000-0000-0000-0000-000000000212',
    '39000000-0000-0000-0000-000000000004',
    'active',
    'https://other-owner.example.invalid',
    now(),
    '39000000-0000-0000-0000-000000000004',
    '39000000-0000-0000-0000-000000000004'
  );

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"39000000-0000-0000-0000-000000000003","role":"authenticated"}',
  true
);
select is(
  (select count(*)::integer from public.owner_list_art_terms()),
  28,
  'an active gallery owner can read the controlled vocabulary'
);
select is(
  (select count(*)::integer from public.owner_search_artists('작가', 20)),
  1,
  'an active gallery owner can search canonical artists'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"39000000-0000-0000-0000-000000000001","role":"authenticated"}',
  true
);
select throws_ok(
  $$select * from public.owner_search_artists('작가', 20)$$,
  '42501',
  'active_gallery_membership_required',
  'a normal authenticated account cannot enter the owner artist lookup'
);

reset role;

insert into content.exhibitions (
  id,
  created_by,
  updated_by
)
values (
  'art-metadata-published-fixture',
  '39000000-0000-0000-0000-000000000002',
  '39000000-0000-0000-0000-000000000002'
);

insert into content.exhibition_versions (
  id,
  exhibition_id,
  version_number,
  revision,
  status,
  name_ko,
  name_en,
  venue_name_ko,
  venue_name_en,
  city_ko,
  city_en,
  region_ko,
  region_en,
  address_ko,
  address_en,
  opening_date,
  closing_date,
  latitude,
  longitude,
  description_ko,
  description_en,
  hours,
  country_code,
  published_at,
  created_by,
  updated_by
)
values (
  '39000000-0000-0000-0000-000000000301',
  'art-metadata-published-fixture',
  1,
  1,
  'published',
  '구조화된 전시',
  'Structured Exhibition',
  '아트 오너 갤러리',
  'Art Owner Gallery',
  '서울',
  'Seoul',
  '종로구',
  'Jongno-gu',
  '서울특별시 종로구 삼청로 10',
  '10 Samcheong-ro, Jongno-gu, Seoul',
  '2026-09-01',
  '2026-12-31',
  37.582,
  126.981,
  '구조화된 추천 근거를 검증합니다.',
  'Tests structured recommendation evidence.',
  'Tue-Sun 10:00-18:00',
  'KR',
  now(),
  '39000000-0000-0000-0000-000000000002',
  '39000000-0000-0000-0000-000000000002'
);

insert into content.exhibition_version_artists (
  version_id,
  sort_order,
  artist_id,
  name_ko,
  name_en,
  created_by
)
values (
  '39000000-0000-0000-0000-000000000301',
  0,
  (select value::uuid from art_metadata_test_state where key = 'artist_id'),
  '김작가',
  'Kim Artist',
  '39000000-0000-0000-0000-000000000002'
);

insert into content.exhibition_version_terms (
  version_id,
  term_id,
  sort_order,
  created_by
)
values
  (
    '39000000-0000-0000-0000-000000000301',
    'medium:painting',
    0,
    '39000000-0000-0000-0000-000000000002'
  ),
  (
    '39000000-0000-0000-0000-000000000301',
    'mood:quiet-meditative',
    1,
    '39000000-0000-0000-0000-000000000002'
  );

update content.exhibitions
set published_version_id = '39000000-0000-0000-0000-000000000301'
where id = 'art-metadata-published-fixture';

select is(
  jsonb_array_length(
    (select artists from public.exhibition_catalog_v2
      where id = 'art-metadata-published-fixture')
  ),
  1,
  'publication projects ordered resolved artists into canonical-v2'
);
select is(
  jsonb_array_length(
    (select art_terms from public.exhibition_catalog_v2
      where id = 'art-metadata-published-fixture')
  ),
  2,
  'publication projects controlled terms into canonical-v2'
);

set local role anon;
select is(
  (
    select artists -> 0 ->> 'name_en'
    from public.exhibition_catalog_v2
    where id = 'art-metadata-published-fixture'
  ),
  'Kim Artist',
  'anonymous readers receive the evidence labels without private table access'
);
reset role;

select throws_ok(
  $$update content.art_taxonomy_terms
    set id = 'medium:painting-renamed'
    where id = 'medium:painting'$$,
  '23514',
  'art_taxonomy_term_semantics_immutable',
  'a controlled taxonomy identifier cannot be reinterpreted'
);
select throws_ok(
  $$update content.art_taxonomy_terms
    set category = 'style'
    where id = 'medium:painting'$$,
  '23514',
  'art_taxonomy_term_semantics_immutable',
  'a controlled taxonomy category cannot be reinterpreted'
);
select throws_ok(
  $$update content.art_taxonomy_terms
    set name_ko = '변경된 회화'
    where id = 'medium:painting'$$,
  '23514',
  'art_taxonomy_term_semantics_immutable',
  'a controlled taxonomy Korean label is immutable'
);
select throws_ok(
  $$update content.art_taxonomy_terms
    set name_en = 'Changed painting'
    where id = 'medium:painting'$$,
  '23514',
  'art_taxonomy_term_semantics_immutable',
  'a controlled taxonomy English label is immutable'
);
select lives_ok(
  $$update content.art_taxonomy_terms
    set active = false
    where id = 'medium:painting'$$,
  'taxonomy retirement remains an explicit mutable editorial operation'
);
update content.art_taxonomy_terms
set active = true
where id = 'medium:painting';
select is(
  (
    select art_terms -> 0 ->> 'name_en'
    from public.exhibition_catalog_v2
    where id = 'art-metadata-published-fixture'
  ),
  'Painting',
  'rejected taxonomy mutations and retirement do not rewrite published labels'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"39000000-0000-0000-0000-000000000002","role":"authenticated"}',
  true
);

with saved as (
  select public.admin_save_exhibition_draft(
    'art-metadata-published-fixture',
    '39000000-0000-0000-0000-000000000301',
    1,
    '{}'::jsonb
  ) as payload
)
insert into art_metadata_test_state (key, value)
select 'admin_draft_id', payload ->> 'working_version_id' from saved
union all
select 'admin_draft_revision', payload ->> 'revision' from saved;

reset role;

select isnt(
  (select value from art_metadata_test_state where key = 'admin_draft_id'),
  '39000000-0000-0000-0000-000000000301',
  'saving a published version creates a distinct working version'
);
select is(
  (
    select count(*)::integer
    from content.exhibition_version_artists
    where version_id = (
      select value::uuid from art_metadata_test_state
      where key = 'admin_draft_id'
    )
  ),
  1,
  'an omitted artists patch preserves credits while cloning a version'
);
select is(
  (
    select count(*)::integer
    from content.exhibition_version_terms
    where version_id = (
      select value::uuid from art_metadata_test_state
      where key = 'admin_draft_id'
    )
  ),
  2,
  'an omitted term patch preserves controlled evidence while cloning a version'
);
select is(
  (
    select public.admin_get_exhibition('art-metadata-published-fixture')
      -> 'artists' -> 0 ->> 'id'
  ),
  (select value from art_metadata_test_state where key = 'artist_id'),
  'Admin responses expose the stable artist identity'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"39000000-0000-0000-0000-000000000002","role":"authenticated"}',
  true
);
with cleared as (
  select public.admin_save_exhibition_draft(
    'art-metadata-published-fixture',
    (select value::uuid from art_metadata_test_state where key = 'admin_draft_id'),
    (select value::integer from art_metadata_test_state
      where key = 'admin_draft_revision'),
    '{"artists":[],"art_term_ids":[]}'::jsonb
  ) as payload
)
update art_metadata_test_state
set value = (select payload ->> 'revision' from cleared)
where key = 'admin_draft_revision';

reset role;

select is(
  (
    select count(*)::integer
    from content.exhibition_version_artists
    where version_id = (
      select value::uuid from art_metadata_test_state
      where key = 'admin_draft_id'
    )
  ),
  0,
  'an explicit empty artists patch clears credits'
);
select is(
  (
    select count(*)::integer
    from content.exhibition_version_terms
    where version_id = (
      select value::uuid from art_metadata_test_state
      where key = 'admin_draft_id'
    )
  ),
  0,
  'an explicit empty term patch clears controlled evidence'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"39000000-0000-0000-0000-000000000002","role":"authenticated"}',
  true
);
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, %s, %L::jsonb)',
    'art-metadata-published-fixture',
    (select value from art_metadata_test_state where key = 'admin_draft_id'),
    (select value from art_metadata_test_state where key = 'admin_draft_revision'),
    '{"artists":[{"id":"39000000-0000-0000-0000-000000009999"}]}'
  ),
  '22023',
  'art_metadata_artist_unknown',
  'unknown artist identities fail closed'
);
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, %s, %L::jsonb)',
    'art-metadata-published-fixture',
    (select value from art_metadata_test_state where key = 'admin_draft_id'),
    (select value from art_metadata_test_state where key = 'admin_draft_revision'),
    jsonb_build_object(
      'artists', jsonb_build_array(
        jsonb_build_object('id', (select value from art_metadata_test_state where key = 'artist_id')),
        jsonb_build_object('id', (select value from art_metadata_test_state where key = 'artist_id'))
      )
    )::text
  ),
  '22023',
  'art_metadata_duplicate_artist',
  'duplicate artist identities fail closed'
);
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, %s, %L::jsonb)',
    'art-metadata-published-fixture',
    (select value from art_metadata_test_state where key = 'admin_draft_id'),
    (select value from art_metadata_test_state where key = 'admin_draft_revision'),
    '{"art_term_ids":["medium:painting","medium:painting"]}'
  ),
  '22023',
  'art_metadata_duplicate_term',
  'duplicate controlled terms fail closed'
);
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, %s, %L::jsonb)',
    'art-metadata-published-fixture',
    (select value from art_metadata_test_state where key = 'admin_draft_id'),
    (select value from art_metadata_test_state where key = 'admin_draft_revision'),
    '{"art_term_ids":["medium:painting","medium:sculpture","medium:photography","medium:installation","medium:video","medium:digital","medium:performance","medium:drawing","medium:printmaking","medium:craft","style:abstract","style:figurative","style:minimalist","style:conceptual","style:documentary","style:experimental","theme:identity"]}'
  ),
  '22023',
  'art_metadata_terms_invalid',
  'more than sixteen controlled terms fail closed'
);
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, %s, %L::jsonb)',
    'art-metadata-published-fixture',
    (select value from art_metadata_test_state where key = 'admin_draft_id'),
    (select value from art_metadata_test_state where key = 'admin_draft_revision'),
    '{"art_term_ids":["medium:painting","medium:sculpture","medium:photography","medium:installation","medium:video","medium:digital","medium:performance"]}'
  ),
  '22023',
  'art_metadata_category_limit_exceeded',
  'more than six terms in one category fail closed'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"39000000-0000-0000-0000-000000000003","role":"authenticated"}',
  true
);

with created as (
  select public.owner_create_exhibition_draft(
    '39000000-0000-0000-0000-000000000401'
  ) as payload
)
insert into art_metadata_test_state (key, value)
select 'owner_exhibition_id', payload ->> 'id' from created
union all
select 'owner_version_id', payload ->> 'working_version_id' from created;

with saved as (
  select public.owner_save_exhibition_draft(
    (select value from art_metadata_test_state where key = 'owner_exhibition_id'),
    (select value::uuid from art_metadata_test_state where key = 'owner_version_id'),
    1,
    '{
      "name_ko":"오너 전시",
      "name_en":"Owner Exhibition",
      "venue_name_ko":"아트 오너 갤러리",
      "venue_name_en":"Art Owner Gallery",
      "city_ko":"서울",
      "city_en":"Seoul",
      "region_ko":"종로구",
      "region_en":"Jongno-gu",
      "address_ko":"서울특별시 종로구 삼청로 10",
      "address_en":"10 Samcheong-ro, Jongno-gu, Seoul",
      "artists":[{"id":null,"name_ko":"신진 작가","name_en":"Emerging Artist"}],
      "art_term_ids":["style:experimental"]
    }'::jsonb
  ) as payload
)
insert into art_metadata_test_state (key, value)
select 'owner_revision', payload ->> 'revision' from saved;

select is(
  (
    select listed.payload -> 'artists' -> 0 -> 'id'
    from public.owner_list_exhibitions() as listed(payload)
    limit 1
  ),
  'null'::jsonb,
  'Gallery responses retain an unresolved owner suggestion explicitly'
);

reset role;
select is(
  (
    select count(*)::integer
    from content.exhibition_version_artists
    where version_id = (
      select value::uuid from art_metadata_test_state where key = 'owner_version_id'
    ) and artist_id is null
  ),
  1,
  'an unresolved owner suggestion is private versioned metadata'
);
select is(
  (
    select count(*)::integer
    from content.exhibition_version_terms
    where version_id = (
      select value::uuid from art_metadata_test_state where key = 'owner_version_id'
    ) and term_id = 'style:experimental'
  ),
  1,
  'an owner can attach a controlled term to their draft'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"39000000-0000-0000-0000-000000000004","role":"authenticated"}',
  true
);
select throws_ok(
  format(
    'select public.owner_save_exhibition_draft(%L, %L::uuid, 2, %L::jsonb)',
    (select value from art_metadata_test_state where key = 'owner_exhibition_id'),
    (select value from art_metadata_test_state where key = 'owner_version_id'),
    '{"artists":[]}'
  ),
  '42501',
  'owner_exhibition_access_denied',
  'another gallery owner cannot replace draft art metadata'
);

reset role;

insert into content.media_assets (
  id,
  status,
  bucket_id,
  object_path,
  delivery_bucket_id,
  delivery_object_path,
  public_url,
  mime_type,
  byte_size,
  width,
  height,
  checksum_sha256,
  published_at
)
values (
  '39000000-0000-0000-0000-000000000501',
  'published'::content.media_asset_status,
  'exhibition-media',
  'tests/art-owner-cover.jpg',
  'exhibition-images',
  'cms/39000000-0000-0000-0000-000000000501/original.jpg',
  'https://cdn.example.invalid/art-owner-cover.jpg',
  'image/jpeg',
  1024,
  1200,
  800,
  repeat('a', 64),
  now()
);
insert into content.exhibition_version_media (
  version_id,
  media_id,
  role,
  sort_order
)
values (
  (select value::uuid from art_metadata_test_state where key = 'owner_version_id'),
  '39000000-0000-0000-0000-000000000501',
  'cover'::content.media_role,
  0
);

insert into content.exhibition_submissions (
  id,
  status,
  submitter_email,
  payload,
  source,
  owner_exhibition_id,
  submitted_at
)
values (
  '39000000-0000-0000-0000-000000000402',
  'submitted',
  'art-owner@example.invalid',
  jsonb_build_object(
    'name_ko', '오너 전시',
    'name_en', 'Owner Exhibition',
    'venue_name_ko', '아트 오너 갤러리',
    'venue_name_en', 'Art Owner Gallery',
    'opening_date', '2026-09-01',
    'closing_date', '2026-12-31',
    'address_ko', '서울특별시 종로구 삼청로 10',
    'address_en', '10 Samcheong-ro, Jongno-gu, Seoul',
    'hours', 'Tue-Sun 10:00-18:00',
    'description_ko', '',
    'description_en', '',
    'reception_date', '',
    'reception_end', '',
    'version_id', (
      select value from art_metadata_test_state where key = 'owner_version_id'
    ),
    'revision', 2
  ),
  'owner_workspace',
  (select value from art_metadata_test_state where key = 'owner_exhibition_id'),
  now()
);

select is(
  (
    select payload -> 'artists' -> 0 ->> 'name_en'
    from content.exhibition_submissions
    where id = '39000000-0000-0000-0000-000000000402'
  ),
  'Emerging Artist',
  'an owner submission snapshots artist suggestions for staff review'
);
select is(
  (
    select payload -> 'art_terms' -> 0 ->> 'id'
    from content.exhibition_submissions
    where id = '39000000-0000-0000-0000-000000000402'
  ),
  'style:experimental',
  'an owner submission snapshots controlled terms for staff review'
);

select throws_ok(
  format(
    'update content.exhibition_versions set status = %L, published_at = now() where id = %L::uuid',
    'published',
    (select value from art_metadata_test_state where key = 'owner_version_id')
  ),
  '23514',
  'unresolved_artist_credits',
  'publication rejects unresolved owner artist suggestions'
);

select is(
  (
    select count(*)::integer
    from information_schema.columns
    where table_schema = 'public'
      and table_name = 'exhibitions'
      and column_name in ('artists', 'art_terms')
  ),
  0,
  'the active legacy exhibitions reader remains structurally unchanged'
);
select ok(
  has_table_privilege('anon', 'public.exhibitions', 'SELECT'),
  'the active legacy app retains anonymous catalogue read access'
);
select is(
  public.admin_reconcile_exhibition_catalog_v2() ->> 'in_sync',
  'true',
  'canonical reconciliation includes structured metadata and remains in sync'
);

select * from finish();

rollback;
