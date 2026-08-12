begin;

-- Covers the August Admin list, placement, location, and schedule requests.

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(13);

select is(
  (
    select column_default
    from information_schema.columns
    where table_schema = 'content'
      and table_name = 'exhibition_versions'
      and column_name = 'is_homepage_featured'
  ),
  'true',
  'new canonical exhibition versions default to homepage featured'
);

select has_column(
  'content',
  'exhibition_versions',
  'reception_end_time',
  'canonical exhibition versions store an optional reception end time'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.admin_list_exhibitions(text,text,text,boolean,text)',
    'EXECUTE'
  ),
  'authenticated users can call the extended list RPC'
);

select ok(
  not has_function_privilege(
    'anon',
    'public.admin_list_exhibitions(text,text,text,boolean,text)',
    'EXECUTE'
  ),
  'anonymous users cannot call the extended list RPC'
);

insert into auth.users (id, email, raw_user_meta_data)
values (
  '00000000-0000-0000-0000-000000002801'::uuid,
  'admin-workflow-requests@example.invalid',
  '{"full_name":"Workflow Test Editor"}'::jsonb
);

insert into content.staff_members (user_id, role, active)
values (
  '00000000-0000-0000-0000-000000002801'::uuid,
  'contributor'::content.staff_role,
  true
);

create temporary table workflow_request_state (
  payload jsonb not null
) on commit drop;
grant select, insert, update, delete on workflow_request_state
  to authenticated;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002801","role":"authenticated"}',
  true
);

insert into pg_temp.workflow_request_state (payload)
select public.admin_create_exhibition_draft();

select ok(
  (
    select (payload ->> 'is_homepage_featured')::boolean
      and payload ? 'created_at'
      and payload ? 'published_at'
    from pg_temp.workflow_request_state
  ),
  'new draft payloads are featured by default and include list timestamps'
);

update pg_temp.workflow_request_state
set payload = public.admin_save_exhibition_draft(
  payload ->> 'id',
  (payload ->> 'working_version_id')::uuid,
  (payload ->> 'revision')::integer,
  jsonb_build_object(
    'name_ko', '관리자 워크플로 전시',
    'venue_name_ko', '테스트 전시장',
    'city_ko', '서울',
    'city_en', 'Seoul',
    'region_ko', '용산구',
    'region_en', 'Yongsan-gu',
    'address_ko', '서울 용산구 한남대로 28',
    'latitude', '37.5344',
    'longitude', '127.0005',
    'opening_date', to_char(
      (current_timestamp at time zone 'Asia/Seoul')::date - 1,
      'YYYY-MM-DD'
    ),
    'closing_date', to_char(
      (current_timestamp at time zone 'Asia/Seoul')::date + 1,
      'YYYY-MM-DD'
    ),
    'reception_start_time', '18:00',
    'reception_end_time', '20:00'
  )
)
returning payload;

select is(
  (select payload ->> 'reception_end_time' from pg_temp.workflow_request_state),
  '20:00',
  'the revision-checked save returns reception end time'
);

select is(
  (
    select version.reception_end_time
    from content.exhibition_versions as version
    where version.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.workflow_request_state
    )
  ),
  '20:00',
  'the canonical version stores reception end time'
);

update pg_temp.workflow_request_state
set payload = public.admin_save_exhibition_draft(
  payload ->> 'id',
  (payload ->> 'working_version_id')::uuid,
  (payload ->> 'revision')::integer,
  '{"address_ko":"서울 용산구 한남대로 28 3층"}'::jsonb
)
returning payload;

select is(
  (
    select concat_ws('|', payload ->> 'latitude', payload ->> 'longitude')
    from pg_temp.workflow_request_state
  ),
  '37.5344|127.0005',
  'adding a floor detail preserves the confirmed coordinate pair'
);

update pg_temp.workflow_request_state
set payload = public.admin_save_exhibition_draft(
  payload ->> 'id',
  (payload ->> 'working_version_id')::uuid,
  (payload ->> 'revision')::integer,
  '{"address_ko":"서울 용산구 이태원로 55 3층"}'::jsonb
)
returning payload;

select is(
  (
    select concat_ws('|', payload ->> 'latitude', payload ->> 'longitude')
    from pg_temp.workflow_request_state
  ),
  '|',
  'changing the searchable street address clears the coordinate pair'
);

select is(
  (
    select count(*)::integer
    from public.admin_list_exhibitions(
      '',
      'draft',
      'running',
      true,
      'opening_asc'
    ) as exhibition
    where exhibition ->> 'id' = (
      select payload ->> 'id' from pg_temp.workflow_request_state
    )
  ),
  1,
  'temporal and homepage filters combine with publish state and sort'
);

select throws_ok(
  $$select * from public.admin_list_exhibitions('', null, null, false, 'unknown')$$,
  '22023',
  'invalid_exhibition_sort',
  'the extended list RPC rejects unsupported sort values'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, %s, %L::jsonb)',
    (select payload ->> 'id' from pg_temp.workflow_request_state),
    (select payload ->> 'working_version_id' from pg_temp.workflow_request_state),
    (select payload ->> 'revision' from pg_temp.workflow_request_state),
    '{"reception_end_time":"25:00"}'
  ),
  '22023',
  'patch_time_has_invalid_format',
  'reception end time rejects invalid clock values'
);

reset role;

select is(
  content_private.searchable_korean_address('서울 용산구 한남대로 28 4층 401호'),
  '서울 용산구 한남대로 28',
  'the database derives the searchable Korean road address'
);

select * from finish();
rollback;
