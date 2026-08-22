begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(53);

-- -------------------------------------------------------------------------
-- Lookup RPC boundary: the public wrapper is invoker-safe, while the private
-- implementation owns the privileged read and still enforces staff status.
-- -------------------------------------------------------------------------

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where (namespace.nspname, procedure.proname) in (
      ('public', 'admin_get_exhibition_lookups'),
      ('content_private', 'admin_get_exhibition_lookups_impl')
    )
  ),
  2,
  'both public and private exhibition lookup functions exist'
);

select ok(
  (
    select not procedure.prosecdef
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'admin_get_exhibition_lookups'
  ),
  'the public lookup RPC is SECURITY INVOKER'
);

select ok(
  (
    select procedure.prosecdef
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname = 'admin_get_exhibition_lookups_impl'
  ),
  'the private lookup implementation is SECURITY DEFINER'
);

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where (namespace.nspname, procedure.proname) in (
      ('public', 'admin_get_exhibition_lookups'),
      ('content_private', 'admin_get_exhibition_lookups_impl')
    )
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  2,
  'both lookup functions pin an empty search_path'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_get_exhibition_lookups()'),
        ('content_private.admin_get_exhibition_lookups_impl()')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  2,
  'authenticated can execute the public and delegated private lookup functions'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_get_exhibition_lookups()'),
        ('content_private.admin_get_exhibition_lookups_impl()')
    ) as signature(value)
    where has_function_privilege('anon', signature.value, 'EXECUTE')
  ),
  0,
  'anon can execute neither lookup function'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_get_exhibition_lookups()'),
        ('content_private.admin_get_exhibition_lookups_impl()')
    ) as signature(value)
    where has_function_privilege('service_role', signature.value, 'EXECUTE')
  ),
  0,
  'service role receives no implicit lookup execute grant'
);

-- Unique test principals and lookup records. auth.users inserts also create
-- profiles through the hardened signup trigger.
insert into auth.users (id, email, raw_user_meta_data)
values
  (
    '00000000-0000-0000-0000-000000000601'::uuid,
    'extended-fields-normal@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000602'::uuid,
    'extended-fields-contributor@example.invalid',
    '{"full_name":"Extended Fields Contributor"}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000603'::uuid,
    'extended-fields-publisher@example.invalid',
    '{"full_name":"Extended Fields Publisher"}'::jsonb
  );

insert into content.staff_members (user_id, role, active)
values
  (
    '00000000-0000-0000-0000-000000000602'::uuid,
    'contributor'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000603'::uuid,
    'publisher'::content.staff_role,
    true
  );

insert into content.venues (
  id,
  name_ko,
  name_en,
  city_ko,
  city_en,
  region_ko,
  region_en,
  address_ko,
  address_en,
  latitude,
  longitude,
  created_by,
  updated_by
)
values (
  '00000000-0000-0000-0000-000000000604'::uuid,
  '확장 필드 테스트 공간',
  'Extended Fields Test Venue',
  '서울',
  'Seoul',
  '용산구',
  'Yongsan-gu',
  '서울 용산구 테스트로 4',
  '4 Test-ro, Yongsan-gu, Seoul',
  37.5344,
  127.0005,
  '00000000-0000-0000-0000-000000000602'::uuid,
  '00000000-0000-0000-0000-000000000602'::uuid
);

insert into public.events (
  id,
  name_ko,
  name_en,
  location_label_ko,
  location_label_en,
  start_date,
  end_date,
  brand_color,
  is_active
)
values
  (
    'test-admin-fields-active-20260721',
    '확장 필드 활성 행사',
    'Extended Fields Active Event',
    '서울',
    'Seoul',
    '2026-07-01',
    '2026-08-31',
    '#1E293B',
    true
  ),
  (
    'test-admin-fields-inactive-20260721',
    '확장 필드 비활성 행사',
    'Extended Fields Inactive Event',
    '서울',
    'Seoul',
    '2026-05-01',
    '2026-06-30',
    '#475569',
    false
  );

insert into public.editors (
  id,
  name_ko,
  name_en,
  title_ko,
  title_en,
  bio_ko,
  bio_en,
  is_active,
  active_from,
  active_to
)
values
  (
    'test-admin-fields-active-editor-20260721',
    '확장 필드 활성 에디터',
    'Extended Fields Active Editor',
    '객원 에디터',
    'Guest Editor',
    '활성 에디터 테스트 소개',
    'Active editor test biography',
    true,
    '2026-07-01',
    null
  ),
  (
    'test-admin-fields-inactive-editor-20260721',
    '확장 필드 비활성 에디터',
    'Extended Fields Inactive Editor',
    '객원 에디터',
    'Guest Editor',
    '비활성 에디터 테스트 소개',
    'Inactive editor test biography',
    false,
    '2026-05-01',
    '2026-06-30'
  );

create temporary table extended_fields_test_state (
  key text primary key,
  payload jsonb not null
) on commit drop;
grant select, insert, update, delete on extended_fields_test_state
  to authenticated, service_role;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000601","role":"authenticated"}',
  true
);

select throws_ok(
  $$ select public.admin_get_exhibition_lookups() $$,
  '42501',
  'active_staff_membership_required',
  'a signed-in non-staff user cannot call the public lookup RPC'
);

select throws_ok(
  $$ select content_private.admin_get_exhibition_lookups_impl() $$,
  '42501',
  'active_staff_membership_required',
  'calling the private lookup implementation does not bypass staff checks'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000602","role":"authenticated"}',
  true
);

insert into pg_temp.extended_fields_test_state (key, payload)
values ('lookups', public.admin_get_exhibition_lookups());

select ok(
  jsonb_typeof(
    (select payload -> 'events' from pg_temp.extended_fields_test_state where key = 'lookups')
  ) = 'array'
  and jsonb_typeof(
    (select payload -> 'editors' from pg_temp.extended_fields_test_state where key = 'lookups')
  ) = 'array'
  and jsonb_typeof(
    (select payload -> 'venues' from pg_temp.extended_fields_test_state where key = 'lookups')
  ) = 'array',
  'the contributor receives venue, event, and editor arrays in one response'
);

select ok(
  exists (
    select 1
    from jsonb_array_elements(
      (select payload -> 'venues' from pg_temp.extended_fields_test_state where key = 'lookups')
    ) as item
    where item ->> 'id' = 'venue:00000000-0000-0000-0000-000000000604'
      and item ->> 'name_ko' = '확장 필드 테스트 공간'
      and item ->> 'address_ko' = '서울 용산구 테스트로 4'
      and item ->> 'latitude' = '37.5344'
      and item ->> 'longitude' = '127.0005'
  ),
  'the staff lookup exposes reusable venue and map defaults'
);

select ok(
  exists (
    select 1
    from jsonb_array_elements(
      (select payload -> 'events' from pg_temp.extended_fields_test_state where key = 'lookups')
    ) as item
    where item ->> 'id' = 'test-admin-fields-active-20260721'
      and (item ->> 'is_active')::boolean
  ),
  'the lookup contains the active event'
);

select ok(
  exists (
    select 1
    from jsonb_array_elements(
      (select payload -> 'events' from pg_temp.extended_fields_test_state where key = 'lookups')
    ) as item
    where item ->> 'id' = 'test-admin-fields-inactive-20260721'
      and not (item ->> 'is_active')::boolean
  ),
  'the staff lookup contains the inactive event'
);

select ok(
  exists (
    select 1
    from jsonb_array_elements(
      (select payload -> 'editors' from pg_temp.extended_fields_test_state where key = 'lookups')
    ) as item
    where item ->> 'id' = 'test-admin-fields-active-editor-20260721'
      and (item ->> 'is_active')::boolean
  ),
  'the lookup contains the active editor'
);

select ok(
  exists (
    select 1
    from jsonb_array_elements(
      (select payload -> 'editors' from pg_temp.extended_fields_test_state where key = 'lookups')
    ) as item
    where item ->> 'id' = 'test-admin-fields-inactive-editor-20260721'
      and not (item ->> 'is_active')::boolean
  ),
  'the staff lookup contains the inactive editor hidden by its public RLS policy'
);

insert into pg_temp.extended_fields_test_state (key, payload)
values ('draft', public.admin_create_exhibition_draft());

select ok(
  (
    select payload ->> 'status' = 'draft'
      and (payload ->> 'revision')::integer = 1
    from pg_temp.extended_fields_test_state
    where key = 'draft'
  ),
  'create returns a revision-one draft DTO'
);

select ok(
  (
    select payload @> '{
      "latitude":"",
      "longitude":"",
      "event_id":"",
      "editor_id":"",
      "ticket_url":""
    }'::jsonb
    from pg_temp.extended_fields_test_state
    where key = 'draft'
  ),
  'new draft DTOs expose every extended form field as an empty string'
);

-- Coordinate validation is deliberately performed before locking or writing.
select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"latitude":"37.5"}'
  ),
  '22023',
  'coordinate_pair_must_be_patched_together',
  'a coordinate patch must include latitude and longitude together'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"latitude":"","longitude":"126.9"}'
  ),
  '22023',
  'coordinate_pair_must_both_be_blank_or_complete',
  'a coordinate pair cannot mix a blank and populated value'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"latitude":37.5,"longitude":126.9}'
  ),
  '22023',
  'patch_field_has_invalid_type',
  'coordinate form fields reject JSON numbers instead of silently coercing them'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"latitude":"90.0001","longitude":"126.9"}'
  ),
  '22023',
  'latitude_out_of_range',
  'latitude outside the canonical range is rejected'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"latitude":"37.5","longitude":"-180.0001"}'
  ),
  '22023',
  'longitude_out_of_range',
  'longitude outside the canonical range is rejected'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"latitude":"NaN","longitude":"126.9"}'
  ),
  '22023',
  'latitude_out_of_range',
  'NaN cannot enter the canonical coordinate columns'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"event_id":"test-admin-fields-missing-event-20260721"}'
  ),
  '23503',
  'unknown_event_id',
  'an unknown event association is rejected explicitly'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"editor_id":"test-admin-fields-missing-editor-20260721"}'
  ),
  '23503',
  'unknown_editor_id',
  'an unknown editor association is rejected explicitly'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"event_id":" test-admin-fields-active-20260721"}'
  ),
  '22023',
  'association_id_is_not_trimmed',
  'a padded event ID is rejected rather than normalized ambiguously'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"editor_id":"test-admin-fields-active-editor-20260721 "}'
  ),
  '22023',
  'association_id_is_not_trimmed',
  'a padded editor ID is rejected rather than normalized ambiguously'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"ticket_url":"javascript:alert(1)"}'
  ),
  '22023',
  'ticket_url_is_invalid',
  'ticket URLs reject non-HTTP schemes'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"ticket_url":"https://tickets.example.invalid/a b"}'
  ),
  '22023',
  'ticket_url_is_invalid',
  'ticket URLs reject embedded whitespace'
);

select is(
  (
    select (payload ->> 'revision')::integer
    from pg_temp.extended_fields_test_state
    where key = 'draft'
  ),
  1,
  'all rejected patches leave the client revision unchanged'
);

-- One valid patch writes the complete form data atomically.
update pg_temp.extended_fields_test_state
set payload = public.admin_save_exhibition_draft(
  payload ->> 'id',
  (payload ->> 'working_version_id')::uuid,
  (payload ->> 'revision')::integer,
  jsonb_build_object(
    'name_ko', '확장 필드 전시',
    'name_en', 'Extended Fields Exhibition',
    'venue_name_ko', '확장 필드 전시장',
    'venue_name_en', 'Extended Fields Gallery',
    'city_ko', '서울',
    'city_en', 'Seoul',
    'region_ko', '종로구',
    'region_en', 'Jongno-gu',
    'address_ko', '서울 종로구 테스트로 6',
    'opening_date', '2026-07-21',
    'closing_date', '2026-08-31',
    'latitude', '37.5',
    'longitude', '126.9',
    'event_id', 'test-admin-fields-active-20260721',
    'editor_id', 'test-admin-fields-active-editor-20260721',
    'ticket_url', 'https://tickets.example.invalid/active'
  )
)
where key = 'draft';

select is(
  (
    select (payload ->> 'revision')::integer
    from pg_temp.extended_fields_test_state
    where key = 'draft'
  ),
  2,
  'the valid extended-field save advances the revision exactly once'
);

select ok(
  (
    select payload @> '{
      "latitude":"37.5",
      "longitude":"126.9",
      "event_id":"test-admin-fields-active-20260721",
      "editor_id":"test-admin-fields-active-editor-20260721",
      "ticket_url":"https://tickets.example.invalid/active"
    }'::jsonb
    from pg_temp.extended_fields_test_state
    where key = 'draft'
  ),
  'the save response round-trips coordinates, associations, and ticket URL'
);

reset role;

select ok(
  (
    select version.latitude = 37.5::double precision
      and version.longitude = 126.9::double precision
      and version.event_id = 'test-admin-fields-active-20260721'
      and version.editor_id = 'test-admin-fields-active-editor-20260721'
      and version.ticket_url = 'https://tickets.example.invalid/active'
      and version.revision = 2
    from content.exhibition_versions as version
    where version.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.extended_fields_test_state
      where key = 'draft'
    )
  ),
  'the canonical version stores the exact validated values and revision'
);

select is(
  (
    select pg_typeof(version.latitude)::text
    from content.exhibition_versions as version
    where version.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.extended_fields_test_state
      where key = 'draft'
    )
  ),
  'double precision',
  'coordinates are persisted in the canonical double-precision type'
);

select ok(
  (
    select pg_typeof(version.event_id)::text = 'text'
      and pg_typeof(version.editor_id)::text = 'text'
      and pg_typeof(version.ticket_url)::text = 'text'
    from content.exhibition_versions as version
    where version.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.extended_fields_test_state
      where key = 'draft'
    )
  ),
  'associations and ticket URL remain canonical text columns'
);

-- Partial command callers cannot carry a coordinate pair over to a different
-- address. Exercise the public RPC inside a savepoint so the remaining
-- extended-field lifecycle assertions keep their existing revision sequence.
savepoint address_change_regression;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000602","role":"authenticated"}',
  true
);

update pg_temp.extended_fields_test_state
set payload = public.admin_save_exhibition_draft(
  payload ->> 'id',
  (payload ->> 'working_version_id')::uuid,
  (payload ->> 'revision')::integer,
  '{"address_ko":"서울 종로구 변경로 7"}'::jsonb
)
where key = 'draft';

select ok(
  (
    select (payload ->> 'revision')::integer = 3
      and payload ->> 'address_ko' = '서울 종로구 변경로 7'
      and payload ->> 'latitude' = ''
      and payload ->> 'longitude' = ''
    from pg_temp.extended_fields_test_state
    where key = 'draft'
  ),
  'an address-only RPC patch clears the previously located draft coordinates'
);

reset role;

select ok(
  (
    select version.address_ko = '서울 종로구 변경로 7'
      and version.latitude is null
      and version.longitude is null
    from content.exhibition_versions as version
    where version.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.extended_fields_test_state
      where key = 'draft'
    )
  ),
  'canonical storage cannot retain the old coordinate pair after an address-only change'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000603","role":"authenticated"}',
  true
);

select throws_ok(
  format(
    'select public.admin_publish_exhibition(%L, %L::uuid, 3, %L::uuid)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '60000000-0000-0000-0000-000000000602'
  ),
  '23514',
  'map_coordinates_are_required_for_publication',
  'the address-only draft cannot publish until a coordinate pair is selected again'
);

reset role;
rollback to savepoint address_change_regression;

-- A stale request must not mutate any target field or revision.
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000602","role":"authenticated"}',
  true
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'draft'),
    (select payload ->> 'working_version_id' from pg_temp.extended_fields_test_state where key = 'draft'),
    '{"ticket_url":"https://tickets.example.invalid/stale"}'
  ),
  'PT409',
  'revision_conflict',
  'a stale revision cannot overwrite an extended field through the HTTP conflict boundary'
);

reset role;

select ok(
  (
    select version.revision = 2
      and version.ticket_url = 'https://tickets.example.invalid/active'
      and version.event_id = 'test-admin-fields-active-20260721'
      and version.latitude = 37.5::double precision
    from content.exhibition_versions as version
    where version.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.extended_fields_test_state
      where key = 'draft'
    )
  ),
  'the failed stale save leaves canonical state unchanged'
);

-- Empty form strings clear nullable canonical columns in one revision.
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000602","role":"authenticated"}',
  true
);

update pg_temp.extended_fields_test_state
set payload = public.admin_save_exhibition_draft(
  payload ->> 'id',
  (payload ->> 'working_version_id')::uuid,
  (payload ->> 'revision')::integer,
  jsonb_build_object(
    'latitude', '',
    'longitude', '',
    'event_id', '',
    'editor_id', '',
    'ticket_url', ''
  )
)
where key = 'draft';

select ok(
  (
    select (payload ->> 'revision')::integer = 3
      and payload @> '{
        "latitude":"",
        "longitude":"",
        "event_id":"",
        "editor_id":"",
        "ticket_url":""
      }'::jsonb
    from pg_temp.extended_fields_test_state
    where key = 'draft'
  ),
  'clearing every nullable extended field advances the DTO once and returns blanks'
);

reset role;

select ok(
  (
    select version.revision = 3
      and version.latitude is null
      and version.longitude is null
      and version.event_id is null
      and version.editor_id is null
      and version.ticket_url is null
    from content.exhibition_versions as version
    where version.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.extended_fields_test_state
      where key = 'draft'
    )
  ),
  'blank form values clear all nullable canonical columns atomically'
);

-- Historical inactive references remain valid assignments for staff editors.
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000602","role":"authenticated"}',
  true
);

update pg_temp.extended_fields_test_state
set payload = public.admin_save_exhibition_draft(
  payload ->> 'id',
  (payload ->> 'working_version_id')::uuid,
  (payload ->> 'revision')::integer,
  jsonb_build_object(
    'latitude', '36.25',
    'longitude', '127.75',
    'event_id', 'test-admin-fields-inactive-20260721',
    'editor_id', 'test-admin-fields-inactive-editor-20260721',
    'ticket_url', 'https://tickets.example.invalid/inactive'
  )
)
where key = 'draft';

select is(
  (
    select (payload ->> 'revision')::integer
    from pg_temp.extended_fields_test_state
    where key = 'draft'
  ),
  4,
  'assigning inactive references is accepted with one revision increment'
);

select ok(
  (
    select payload @> '{
      "latitude":"36.25",
      "longitude":"127.75",
      "event_id":"test-admin-fields-inactive-20260721",
      "editor_id":"test-admin-fields-inactive-editor-20260721",
      "ticket_url":"https://tickets.example.invalid/inactive"
    }'::jsonb
    from pg_temp.extended_fields_test_state
    where key = 'draft'
  ),
  'the DTO retains inactive reference IDs and their extended fields'
);

reset role;

select ok(
  (
    select version.event_id = event.id
      and not event.is_active
      and version.editor_id = editor.id
      and not editor.is_active
      and version.latitude = 36.25::double precision
      and version.longitude = 127.75::double precision
      and version.ticket_url = 'https://tickets.example.invalid/inactive'
    from content.exhibition_versions as version
    join public.events as event on event.id = version.event_id
    join public.editors as editor on editor.id = version.editor_id
    where version.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.extended_fields_test_state
      where key = 'draft'
    )
  ),
  'canonical state can retain deliberately selected inactive references'
);

-- Publish, then edit a different field. Clone-on-edit must carry every target
-- field forward without changing the published projection.
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000603","role":"authenticated"}',
  true
);

insert into pg_temp.extended_fields_test_state (key, payload)
select
  'published',
  public.admin_publish_exhibition(
    payload ->> 'id',
    (payload ->> 'working_version_id')::uuid,
    (payload ->> 'revision')::integer,
    '60000000-0000-0000-0000-000000000601'::uuid
  )
from pg_temp.extended_fields_test_state
where key = 'draft';

select ok(
  (
    select payload ->> 'status' = 'published'
      and (payload ->> 'revision')::integer = 5
      and payload ->> 'working_version_id' = payload ->> 'published_version_id'
    from pg_temp.extended_fields_test_state
    where key = 'published'
  ),
  'publishing the extended-field draft advances it to revision five'
);

select ok(
  (
    select payload @> '{
      "latitude":"36.25",
      "longitude":"127.75",
      "event_id":"test-admin-fields-inactive-20260721",
      "editor_id":"test-admin-fields-inactive-editor-20260721",
      "ticket_url":"https://tickets.example.invalid/inactive"
    }'::jsonb
    from pg_temp.extended_fields_test_state
    where key = 'published'
  ),
  'the published DTO retains all extended fields'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000602","role":"authenticated"}',
  true
);

insert into pg_temp.extended_fields_test_state (key, payload)
select
  'clone',
  public.admin_save_exhibition_draft(
    payload ->> 'id',
    (payload ->> 'working_version_id')::uuid,
    (payload ->> 'revision')::integer,
    '{"name_en":"Extended Fields Exhibition — Revised"}'::jsonb
  )
from pg_temp.extended_fields_test_state
where key = 'published';

select ok(
  (
    select clone.payload ->> 'working_version_id'
        <> published.payload ->> 'working_version_id'
      and clone.payload ->> 'published_version_id'
        = published.payload ->> 'working_version_id'
      and (clone.payload ->> 'version_number')::integer = 2
      and (clone.payload ->> 'revision')::integer = 6
      and (clone.payload ->> 'has_unpublished_changes')::boolean
    from pg_temp.extended_fields_test_state as clone
    cross join pg_temp.extended_fields_test_state as published
    where clone.key = 'clone'
      and published.key = 'published'
  ),
  'editing published content creates a distinct version-two draft with one increment'
);

select ok(
  (
    select clone.payload -> 'latitude' = published.payload -> 'latitude'
      and clone.payload -> 'longitude' = published.payload -> 'longitude'
      and clone.payload -> 'event_id' = published.payload -> 'event_id'
      and clone.payload -> 'editor_id' = published.payload -> 'editor_id'
      and clone.payload -> 'ticket_url' = published.payload -> 'ticket_url'
    from pg_temp.extended_fields_test_state as clone
    cross join pg_temp.extended_fields_test_state as published
    where clone.key = 'clone'
      and published.key = 'published'
  ),
  'clone-on-edit preserves every extended field in the returned DTO'
);

select ok(
  public.admin_get_exhibition(
    (select payload ->> 'id' from pg_temp.extended_fields_test_state where key = 'clone')
  ) @> (
    select jsonb_build_object(
      'working_version_id', payload ->> 'working_version_id',
      'latitude', payload ->> 'latitude',
      'longitude', payload ->> 'longitude',
      'event_id', payload ->> 'event_id',
      'editor_id', payload ->> 'editor_id',
      'ticket_url', payload ->> 'ticket_url'
    )
    from pg_temp.extended_fields_test_state
    where key = 'clone'
  ),
  'get returns the cloned draft and its preserved extended-field DTO'
);

reset role;

select ok(
  (
    select draft.latitude = published.latitude
      and draft.longitude = published.longitude
      and draft.event_id = published.event_id
      and draft.editor_id = published.editor_id
      and draft.ticket_url = published.ticket_url
    from content.exhibition_versions as draft
    join content.exhibition_versions as published
      on published.id = (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.extended_fields_test_state
        where key = 'published'
      )
    where draft.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.extended_fields_test_state
      where key = 'clone'
    )
  ),
  'the cloned canonical row exactly matches the published extended fields'
);

select ok(
  (
    select version.status = 'published'::content.exhibition_version_status
      and version.revision = 5
      and version.event_id = 'test-admin-fields-inactive-20260721'
      and version.editor_id = 'test-admin-fields-inactive-editor-20260721'
      and version.ticket_url = 'https://tickets.example.invalid/inactive'
    from content.exhibition_versions as version
    where version.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.extended_fields_test_state
      where key = 'published'
    )
  ),
  'clone-on-edit leaves the published version immutable'
);

set local role service_role;

select ok(
  (
    select preview.latitude = 36.25::double precision
      and preview.longitude = 127.75::double precision
      and preview.event_id = 'test-admin-fields-inactive-20260721'
      and preview.editor_id = 'test-admin-fields-inactive-editor-20260721'
      and preview.ticket_url = 'https://tickets.example.invalid/inactive'
    from public.exhibitions_v2_preview as preview
    where preview.id = (
      select payload ->> 'id'
      from pg_temp.extended_fields_test_state
      where key = 'published'
    )
  ),
  'the compatibility preview exposes the published extended fields during draft editing'
);

reset role;

select * from finish();
rollback;
