begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(66);

-- Public surface and privilege boundary.
select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'admin_current_staff',
        'admin_list_exhibitions',
        'admin_get_exhibition',
        'admin_create_exhibition_draft',
        'admin_save_exhibition_draft',
        'admin_publish_exhibition',
        'admin_archive_exhibition',
        'admin_restore_exhibition'
      )
      and not procedure.prosecdef
  ),
  9,
  'all public admin RPC overloads are SECURITY INVOKER'
);

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'admin_current_staff',
        'admin_list_exhibitions',
        'admin_get_exhibition',
        'admin_create_exhibition_draft',
        'admin_save_exhibition_draft',
        'admin_publish_exhibition',
        'admin_archive_exhibition',
        'admin_restore_exhibition'
      )
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  9,
  'all public admin RPCs pin an empty search_path'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_current_staff()'),
        ('public.admin_list_exhibitions(text,text)'),
        ('public.admin_list_exhibitions(text,text,text,boolean,text)'),
        ('public.admin_get_exhibition(text)'),
        ('public.admin_create_exhibition_draft()'),
        ('public.admin_save_exhibition_draft(text,uuid,integer,jsonb)'),
        ('public.admin_publish_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_archive_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_restore_exhibition(text,uuid,integer,uuid)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  9,
  'authenticated has execute on every public admin RPC'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_current_staff()'),
        ('public.admin_list_exhibitions(text,text)'),
        ('public.admin_list_exhibitions(text,text,text,boolean,text)'),
        ('public.admin_get_exhibition(text)'),
        ('public.admin_create_exhibition_draft()'),
        ('public.admin_save_exhibition_draft(text,uuid,integer,jsonb)'),
        ('public.admin_publish_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_archive_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_restore_exhibition(text,uuid,integer,uuid)')
    ) as signature(value)
    where has_function_privilege('anon', signature.value, 'EXECUTE')
  ),
  0,
  'anon has execute on no public admin RPC'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_current_staff()'),
        ('public.admin_list_exhibitions(text,text)'),
        ('public.admin_list_exhibitions(text,text,text,boolean,text)'),
        ('public.admin_get_exhibition(text)'),
        ('public.admin_create_exhibition_draft()'),
        ('public.admin_save_exhibition_draft(text,uuid,integer,jsonb)'),
        ('public.admin_publish_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_archive_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_restore_exhibition(text,uuid,integer,uuid)')
    ) as signature(value)
    where has_function_privilege('service_role', signature.value, 'EXECUTE')
  ),
  0,
  'service_role receives no implicit execute grant on admin RPCs'
);

select ok(
  not has_table_privilege('authenticated', 'content.exhibitions', 'INSERT'),
  'authenticated cannot directly insert exhibition identities'
);
select ok(
  not has_table_privilege('authenticated', 'content.exhibition_versions', 'INSERT'),
  'authenticated cannot directly insert exhibition versions'
);
select ok(
  not has_table_privilege('authenticated', 'content.media_assets', 'INSERT'),
  'authenticated cannot directly register media assets in Phase 2'
);
select ok(
  not has_table_privilege('authenticated', 'content.exhibition_version_media', 'DELETE'),
  'authenticated cannot directly detach canonical media'
);
select ok(
  not has_column_privilege(
    'authenticated',
    'content.exhibition_version_media',
    'sort_order',
    'UPDATE'
  ),
  'authenticated cannot directly reorder canonical media'
);
select ok(
  not has_table_privilege('authenticated', 'content.curation_placements', 'INSERT'),
  'authenticated cannot directly mutate live curation'
);

select is(
  (
    select count(*)::integer
    from pg_indexes
    where schemaname = 'content'
      and indexname in (
        'exhibition_versions_one_draft_idx',
        'exhibition_versions_one_published_idx'
      )
      and indexdef ilike 'CREATE UNIQUE INDEX%'
  ),
  2,
  'one-draft and one-published partial unique indexes exist'
);

-- Test identities and roles. auth.users inserts also create profiles through
-- the hardened signup trigger.
insert into auth.users (id, email, raw_user_meta_data)
values
  (
    '00000000-0000-0000-0000-000000000201'::uuid,
    'api-normal@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000202'::uuid,
    'api-contributor@example.invalid',
    '{"full_name":"API Contributor"}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000203'::uuid,
    'api-publisher@example.invalid',
    '{"full_name":"API Publisher"}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000204'::uuid,
    'api-admin@example.invalid',
    '{"full_name":"API Admin"}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000205'::uuid,
    'api-inactive@example.invalid',
    '{}'::jsonb
  );

insert into content.staff_members (user_id, role, active)
values
  (
    '00000000-0000-0000-0000-000000000202'::uuid,
    'contributor'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000203'::uuid,
    'publisher'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000204'::uuid,
    'admin'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000205'::uuid,
    'publisher'::content.staff_role,
    false
  );

create temporary table api_test_state (
  key text primary key,
  payload jsonb,
  text_value text
) on commit drop;
grant select, insert, update, delete on api_test_state to authenticated;

create function pg_temp.capture_revision_detail(
  p_exhibition_id text,
  p_version_id uuid,
  p_revision integer
)
returns text
language plpgsql
set search_path = ''
as $$
declare
  v_detail text;
begin
  perform public.admin_save_exhibition_draft(
    p_exhibition_id,
    p_version_id,
    p_revision,
    '{"name_ko":"stale"}'::jsonb
  );
  return null;
exception
  when sqlstate 'PT409' then
    get stacked diagnostics v_detail = pg_exception_detail;
    return v_detail;
end;
$$;
grant execute on function pg_temp.capture_revision_detail(text, uuid, integer)
  to authenticated;

create function pg_temp.raise_unrelated_admin_save_40001()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if current_setting('test.admin_save_raise_40001', true) = 'true' then
    raise exception using
      errcode = '40001',
      message = 'synthetic_serialization_failure',
      detail = 'preserve-me';
  end if;
  return new;
end;
$$;

create trigger zz_test_admin_save_unrelated_40001
before update on content.exhibition_versions
for each row
execute function pg_temp.raise_unrelated_admin_save_40001();

select ok(
  not has_function_privilege(
    'anon',
    'public.admin_list_exhibitions(text,text)',
    'EXECUTE'
  ),
  'anon cannot call the admin query API'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000201","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.admin_create_exhibition_draft() $$,
  '42501',
  'active_staff_membership_required',
  'a signed-in non-staff user cannot create drafts'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000205","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.admin_current_staff() $$,
  '42501',
  'active_staff_membership_required',
  'an inactive staff member is denied'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000202","role":"authenticated"}',
  true
);
select is(
  public.admin_current_staff() ->> 'role',
  'contributor',
  'current staff returns the contributor role'
);

insert into pg_temp.api_test_state (key, payload)
values ('draft', public.admin_create_exhibition_draft());

select is(
  (select payload ->> 'status' from pg_temp.api_test_state where key = 'draft'),
  'draft',
  'create returns a draft DTO'
);
select is(
  (select (payload ->> 'revision')::integer from pg_temp.api_test_state where key = 'draft'),
  1,
  'a new draft starts at revision one'
);
select is(
  (select (payload ->> 'version_number')::integer from pg_temp.api_test_state where key = 'draft'),
  1,
  'a new identity starts with version one'
);
select ok(
  (
    select payload ->> 'working_version_id' is not null
      and payload ->> 'published_version_id' is null
      and (payload ->> 'has_unpublished_changes')::boolean
    from pg_temp.api_test_state
    where key = 'draft'
  ),
  'create returns explicit working/published version metadata'
);
select is(
  public.admin_get_exhibition(
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'draft')
  ) ->> 'working_version_id',
  (
    select payload ->> 'working_version_id'
    from pg_temp.api_test_state
    where key = 'draft'
  ),
  'get returns the current working version DTO'
);
select is(
  (
    select count(*)
    from public.admin_list_exhibitions('', 'draft') as listed(payload)
    where listed.payload ->> 'id' = (
      select payload ->> 'id'
      from pg_temp.api_test_state
      where key = 'draft'
    )
  ),
  1::bigint,
  'lowercase draft filtering returns the new draft among existing drafts'
);
select is(
  (
    select count(*)
    from public.admin_list_exhibitions('', null) as listed(payload)
    where listed.payload ->> 'id' = (
      select payload ->> 'id'
      from pg_temp.api_test_state
      where key = 'draft'
    )
  ),
  1::bigint,
  'a null status includes the new draft when existing exhibitions are present'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000201","role":"authenticated"}',
  true
);
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.api_test_state where key = 'draft'),
    '{"name_ko":"unauthorized"}'
  ),
  '42501',
  'active_staff_membership_required',
  'the HTTP conflict boundary preserves save authorization failures'
);
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000202","role":"authenticated"}',
  true
);

insert into pg_temp.api_test_state (key, payload)
values ('other_draft', public.admin_create_exhibition_draft());

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'draft'),
    (
      select payload ->> 'working_version_id'
      from pg_temp.api_test_state
      where key = 'other_draft'
    ),
    '{"name_ko":"cross identity"}'
  ),
  'P0002',
  'working_version_not_found',
  'a same-revision version from another identity cannot be targeted'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.api_test_state where key = 'draft'),
    '{"cover_image_url":"https://attacker.invalid/image.jpg"}'
  ),
  '22023',
  'patch_contains_forbidden_field',
  'save rejects media fields owned by the later media API'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.api_test_state where key = 'draft'),
    '{"revision":99}'
  ),
  '22023',
  'patch_contains_forbidden_field',
  'save rejects server-owned provenance fields'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.api_test_state where key = 'draft'),
    '{"opening_date":"21-07-2026"}'
  ),
  '22023',
  'patch_date_has_invalid_format',
  'save rejects non-ISO dates'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.api_test_state where key = 'draft'),
    '{"reception_start_time":"25:99"}'
  ),
  '22023',
  'patch_time_has_invalid_format',
  'save rejects invalid local times'
);

update pg_temp.api_test_state
set payload = public.admin_save_exhibition_draft(
  payload ->> 'id',
  (payload ->> 'working_version_id')::uuid,
  (payload ->> 'revision')::integer,
  jsonb_build_object(
    'name_ko', '명확한 시간',
    'name_en', 'A Definite Time',
    'venue_name_ko', '테스트 전시장',
    'venue_name_en', 'Test Gallery',
    'city_ko', '서울',
    'city_en', 'Seoul',
    'region_ko', '용산구',
    'region_en', 'Yongsan-gu',
    'address_ko', '서울 용산구 테스트로 1',
    'address_en', '1 Test-ro, Yongsan-gu, Seoul',
    'opening_date', '2026-07-21',
    'closing_date', '2026-08-31',
    'description_ko', '테스트 설명',
    'description_en', 'Test description',
    'hours', '화–일 11:00–18:00',
    'contact', '02-000-0000',
    'reception_date', '2026-07-24',
    'reception_start_time', '18:00',
    'is_featured', true,
    'is_homepage_featured', false
  )
)
where key = 'draft';

select is(
  (select (payload ->> 'revision')::integer from pg_temp.api_test_state where key = 'draft'),
  2,
  'save atomically increments the revision'
);
select is(
  (select payload ->> 'name_ko' from pg_temp.api_test_state where key = 'draft'),
  '명확한 시간',
  'save returns the updated snake_case model'
);
select is(
  (select payload ->> 'reception_date' from pg_temp.api_test_state where key = 'draft'),
  '2026-07-24',
  'reception dates round-trip at Asia/Seoul midnight'
);
select is(
  (
    select count(*)
    from public.admin_list_exhibitions('명확한', 'draft')
  ),
  1::bigint,
  'search matches localized exhibition content'
);

select set_config('test.admin_save_raise_40001', 'true', true);
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 2, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.api_test_state where key = 'draft'),
    '{"name_ko":"serialization failure"}'
  ),
  '40001',
  'synthetic_serialization_failure',
  'the HTTP conflict boundary preserves unrelated serialization failures'
);
select set_config('test.admin_save_raise_40001', 'false', true);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.api_test_state where key = 'draft'),
    '{"name_ko":"stale"}'
  ),
  'PT409',
  'revision_conflict',
  'save exposes a stale expected revision through the PostgREST HTTP 409 SQLSTATE'
);
select is(
  pg_temp.capture_revision_detail(
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'draft'),
    (select (payload ->> 'working_version_id')::uuid from pg_temp.api_test_state where key = 'draft'),
    1
  ),
  '2',
  'revision conflict detail is the numeric current revision'
);

select throws_ok(
  format(
    'select public.admin_publish_exhibition(%L, %L::uuid, 2, %L::uuid)',
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.api_test_state where key = 'draft'),
    '50000000-0000-0000-0000-000000000001'
  ),
  '42501',
  'insufficient_staff_role',
  'a contributor cannot publish'
);

reset role;

-- Seed hidden scalars and a cover attachment outside the UI contract. Clone on
-- edit must preserve all of them without allowing the save patch to mutate
-- media metadata.
insert into content.venues (
  id,
  slug,
  name_ko,
  created_by
)
values (
  '20000000-0000-0000-0000-000000000001'::uuid,
  'api-hidden-venue',
  '숨은 전시장',
  '00000000-0000-0000-0000-000000000203'::uuid
);

update content.exhibition_versions
set
  venue_id = '20000000-0000-0000-0000-000000000001'::uuid,
  latitude = 37.534,
  longitude = 127.002,
  ticket_url = 'https://tickets.example.invalid/exhibition',
  legacy_cover_image_url = 'https://legacy.example.invalid/cover.jpg'
where id = (
  select (payload ->> 'working_version_id')::uuid
  from pg_temp.api_test_state
  where key = 'draft'
);

insert into content.media_assets (
  id,
  status,
  bucket_id,
  object_path,
  public_url,
  delivery_bucket_id,
  delivery_object_path,
  mime_type,
  byte_size,
  width,
  height,
  checksum_sha256,
  alt_ko,
  alt_en,
  credit,
  uploaded_by,
  published_at
)
values (
  '30000000-0000-0000-0000-000000000001'::uuid,
  'published'::content.media_asset_status,
  'exhibition-media',
  'tests/api-cover.jpg',
  'https://cdn.example.invalid/api-cover.jpg',
  'exhibition-images',
  'cms/30000000-0000-0000-0000-000000000001/original.jpg',
  'image/jpeg',
  1024,
  1200,
  800,
  repeat('a', 64),
  '테스트 표지',
  'Test cover',
  'Test Artist',
  '00000000-0000-0000-0000-000000000202'::uuid,
  now()
);

insert into content.exhibition_version_media (
  version_id,
  media_id,
  role,
  sort_order,
  created_by
)
select
  (payload ->> 'working_version_id')::uuid,
  '30000000-0000-0000-0000-000000000001'::uuid,
  'cover'::content.media_role,
  0,
  '00000000-0000-0000-0000-000000000202'::uuid
from pg_temp.api_test_state
where key = 'draft';

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000203","role":"authenticated"}',
  true
);
select is(
  public.admin_current_staff() ->> 'role',
  'publisher',
  'current staff returns the publisher role'
);

insert into pg_temp.api_test_state (key, payload)
select
  'published_v1',
  public.admin_publish_exhibition(
    payload ->> 'id',
    (payload ->> 'working_version_id')::uuid,
    (payload ->> 'revision')::integer,
    '50000000-0000-0000-0000-000000000002'::uuid
  )
from pg_temp.api_test_state
where key = 'draft';

select is(
  (select payload ->> 'status' from pg_temp.api_test_state where key = 'published_v1'),
  'published',
  'publisher can publish a complete draft'
);
select is(
  (select (payload ->> 'revision')::integer from pg_temp.api_test_state where key = 'published_v1'),
  3,
  'publish atomically advances the revision'
);
select ok(
  (
    select payload ->> 'working_version_id' = payload ->> 'published_version_id'
      and not (payload ->> 'has_unpublished_changes')::boolean
    from pg_temp.api_test_state
    where key = 'published_v1'
  ),
  'publish advances the identity pointer to the working version'
);

reset role;
select is(
  (
    select count(*)
    from content.exhibition_versions
    where exhibition_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'published_v1'
    )
      and status = 'published'::content.exhibition_version_status
  ),
  1::bigint,
  'exactly one published version exists after publish'
);
select is(
  (
    select enabled
    from content.curation_placements
    where exhibition_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'published_v1'
    )
      and surface = 'app_featured'::content.curation_surface
  ),
  true,
  'publish synchronizes app curation intent'
);
select is(
  (
    select enabled
    from content.curation_placements
    where exhibition_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'published_v1'
    )
      and surface = 'homepage'::content.curation_surface
  ),
  false,
  'publish synchronizes homepage curation intent'
);
select is(
  (
    select count(*)
    from content.outbox_events
    where aggregate_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'published_v1'
    )
      and event_type = 'exhibition.published'
      and status = 'pending'::content.outbox_status
      and deduplication_key is not null
  ),
  1::bigint,
  'publish enqueues one deduplicated pending outbox event'
);

-- Editing a published record must clone a new draft before applying the patch.
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000202","role":"authenticated"}',
  true
);
insert into pg_temp.api_test_state (key, payload)
select
  'draft_v2',
  public.admin_save_exhibition_draft(
    payload ->> 'id',
    (payload ->> 'working_version_id')::uuid,
    (payload ->> 'revision')::integer,
    '{"name_ko":"명확한 시간 — 개정"}'::jsonb
  )
from pg_temp.api_test_state
where key = 'published_v1';

select ok(
  (
    select draft.payload ->> 'working_version_id'
        <> published.payload ->> 'working_version_id'
      and draft.payload ->> 'published_version_id'
        = published.payload ->> 'working_version_id'
      and (draft.payload ->> 'has_unpublished_changes')::boolean
    from pg_temp.api_test_state as draft
    cross join pg_temp.api_test_state as published
    where draft.key = 'draft_v2'
      and published.key = 'published_v1'
  ),
  'editing published content creates a distinct unpublished working version'
);
select is(
  (select (payload ->> 'version_number')::integer from pg_temp.api_test_state where key = 'draft_v2'),
  2,
  'clone-on-edit advances the version number'
);
select is(
  (select (payload ->> 'revision')::integer from pg_temp.api_test_state where key = 'draft_v2'),
  4,
  'clone-on-edit applies the patch with one revision increment'
);

reset role;
select is(
  (
    select name_ko
    from content.exhibition_versions
    where id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.api_test_state
      where key = 'published_v1'
    )
  ),
  '명확한 시간',
  'the prior published version remains immutable after draft editing'
);
select ok(
  (
    select draft.venue_id = published.venue_id
      and draft.latitude = published.latitude
      and draft.longitude = published.longitude
      and draft.ticket_url = published.ticket_url
      and draft.legacy_cover_image_url = published.legacy_cover_image_url
    from content.exhibition_versions as draft
    join content.exhibition_versions as published
      on published.id = (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.api_test_state
        where key = 'published_v1'
      )
    where draft.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.api_test_state
      where key = 'draft_v2'
    )
  ),
  'clone-on-edit preserves hidden venue, coordinate, ticket, and cover fields'
);
select is(
  (
    select count(*)
    from content.exhibition_version_media
    where version_id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.api_test_state
      where key = 'draft_v2'
    )
      and media_id = '30000000-0000-0000-0000-000000000001'::uuid
      and role = 'cover'::content.media_role
      and sort_order = 0
  ),
  1::bigint,
  'clone-on-edit preserves media attachment role and order'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000203","role":"authenticated"}',
  true
);
insert into pg_temp.api_test_state (key, payload)
select
  'published_v2',
  public.admin_publish_exhibition(
    payload ->> 'id',
    (payload ->> 'working_version_id')::uuid,
    (payload ->> 'revision')::integer,
    '50000000-0000-0000-0000-000000000003'::uuid
  )
from pg_temp.api_test_state
where key = 'draft_v2';

reset role;
select is(
  (
    select status::text
    from content.exhibition_versions
    where id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.api_test_state
      where key = 'published_v1'
    )
  ),
  'superseded',
  'publishing the next draft supersedes the prior published version'
);
select is(
  (
    select count(*)
    from content.outbox_events
    where aggregate_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'published_v2'
    )
      and event_type = 'exhibition.published'
  ),
  2::bigint,
  'each distinct published version has one outbox event'
);

-- Archive and restore require publisher authority plus both concurrency keys.
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000202","role":"authenticated"}',
  true
);
select throws_ok(
  format(
    'select public.admin_archive_exhibition(%L, %L::uuid, %s, %L::uuid)',
    (select payload ->> 'id' from pg_temp.api_test_state where key = 'published_v2'),
    (select payload ->> 'working_version_id' from pg_temp.api_test_state where key = 'published_v2'),
    (select payload ->> 'revision' from pg_temp.api_test_state where key = 'published_v2'),
    '50000000-0000-0000-0000-000000000004'
  ),
  '42501',
  'insufficient_staff_role',
  'a contributor cannot archive'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000203","role":"authenticated"}',
  true
);
insert into pg_temp.api_test_state (key, payload)
select
  'archived',
  public.admin_archive_exhibition(
    payload ->> 'id',
    (payload ->> 'working_version_id')::uuid,
    (payload ->> 'revision')::integer,
    '50000000-0000-0000-0000-000000000005'::uuid
  )
from pg_temp.api_test_state
where key = 'published_v2';

select is(
  (select payload ->> 'status' from pg_temp.api_test_state where key = 'archived'),
  'archived',
  'publisher can archive with matching concurrency keys'
);

reset role;
select is(
  (
    select count(*)
    from content.curation_placements
    where exhibition_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'archived'
    )
      and enabled
  ),
  0::bigint,
  'archive disables every live curation placement'
);
select is(
  (
    select count(*)
    from content.audit_log
    where entity_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'archived'
    )
      and action = 'exhibition.archived'
  ),
  1::bigint,
  'archive writes an audit record'
);
select is(
  (
    select count(*)
    from content.outbox_events
    where aggregate_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'archived'
    )
      and event_type = 'exhibition.archived'
      and status = 'pending'::content.outbox_status
      and deduplication_key is not null
  ),
  1::bigint,
  'archive enqueues a deduplicated pending outbox event'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000203","role":"authenticated"}',
  true
);
insert into pg_temp.api_test_state (key, payload)
select
  'restored',
  public.admin_restore_exhibition(
    payload ->> 'id',
    (payload ->> 'working_version_id')::uuid,
    (payload ->> 'revision')::integer,
    '50000000-0000-0000-0000-000000000006'::uuid
  )
from pg_temp.api_test_state
where key = 'archived';

select is(
  (select payload ->> 'status' from pg_temp.api_test_state where key = 'restored'),
  'published',
  'publisher can restore with matching concurrency keys'
);

reset role;
select is(
  (
    select count(*)
    from content.curation_placements
    where exhibition_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'restored'
    )
      and enabled
  ),
  0::bigint,
  'restore does not silently re-enable stale curation'
);
select is(
  (
    select count(*)
    from content.audit_log
    where entity_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'restored'
    )
      and action = 'exhibition.restored'
  ),
  1::bigint,
  'restore writes an audit record'
);
select is(
  (
    select count(*)
    from content.outbox_events
    where aggregate_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'restored'
    )
      and event_type = 'exhibition.restored'
      and status = 'pending'::content.outbox_status
      and deduplication_key is not null
  ),
  1::bigint,
  'restore enqueues a deduplicated pending outbox event'
);

-- Directly pointing at a draft satisfies the composite FK but must fail the
-- deferred status invariant added by this migration.
select throws_ok(
  format(
    $violation$
      do $body$
      begin
        update content.exhibition_versions
        set status = 'draft'::content.exhibition_version_status,
            published_at = null,
            published_by = null
        where id = %L::uuid;

        set constraints content.versions_enforce_published_pointer_status immediate;
      end
      $body$
    $violation$,
    (select payload ->> 'working_version_id' from pg_temp.api_test_state where key = 'restored')
  ),
  '23514',
  'published_version_pointer_must_target_published_version',
  'deferred invariant rejects a pointer to a non-published version'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000204","role":"authenticated"}',
  true
);
select is(
  public.admin_current_staff() ->> 'role',
  'admin',
  'admin satisfies the staff API role matrix'
);
select lives_ok(
  $$ select * from public.admin_list_exhibitions('', null) $$,
  'admin can use the exhibition query API'
);

reset role;

select is(
  (
    select count(*)
    from content.audit_log
    where entity_id = (
      select payload ->> 'id' from pg_temp.api_test_state where key = 'restored'
    )
      and actor_user_id is null
  ),
  0::bigint,
  'all command audit actors come from auth.uid()'
);

select * from finish();
rollback;
