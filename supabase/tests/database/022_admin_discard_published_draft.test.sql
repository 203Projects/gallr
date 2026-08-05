begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(12);

select has_function(
  'public',
  'admin_discard_exhibition_draft',
  array['text', 'uuid', 'integer', 'uuid'],
  'the public discard-draft command exists'
);
select ok(
  has_function_privilege(
    'authenticated',
    'public.admin_discard_exhibition_draft(text, uuid, integer, uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.admin_discard_exhibition_draft(text, uuid, integer, uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'public.admin_discard_exhibition_draft(text, uuid, integer, uuid)',
    'EXECUTE'
  ),
  'only authenticated can execute the public discard command'
);
select ok(
  not (
    select procedure.prosecdef
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'admin_discard_exhibition_draft'
  ),
  'the public discard command is SECURITY INVOKER'
);

insert into auth.users (id, email, raw_user_meta_data)
values
  (
    '00000000-0000-0000-0000-000000000701'::uuid,
    'discard-publisher@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000702'::uuid,
    'discard-contributor@example.invalid',
    '{}'::jsonb
  );

insert into content.staff_members (user_id, role, active)
values
  (
    '00000000-0000-0000-0000-000000000701'::uuid,
    'publisher'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000702'::uuid,
    'contributor'::content.staff_role,
    true
  );

insert into content.exhibitions (id, created_by, updated_by)
values (
  'discard-published-test',
  '00000000-0000-0000-0000-000000000701'::uuid,
  '00000000-0000-0000-0000-000000000701'::uuid
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
  published_at,
  published_by,
  created_by,
  updated_by
)
values
  (
    '70000000-0000-0000-0000-000000000001'::uuid,
    'discard-published-test',
    1,
    4,
    'published'::content.exhibition_version_status,
    '마지막 공개 상태',
    'Last Published State',
    '테스트 전시장',
    'Test Venue',
    '서울',
    'Seoul',
    '용산구',
    'Yongsan-gu',
    '서울 용산구 한남대로 28',
    '28 Hannam-daero, Yongsan-gu, Seoul',
    '2026-08-01'::date,
    '2026-08-31'::date,
    37.5344,
    127.0005,
    now(),
    '00000000-0000-0000-0000-000000000701'::uuid,
    '00000000-0000-0000-0000-000000000701'::uuid,
    '00000000-0000-0000-0000-000000000701'::uuid
  ),
  (
    '70000000-0000-0000-0000-000000000002'::uuid,
    'discard-published-test',
    2,
    7,
    'draft'::content.exhibition_version_status,
    '실수로 바꾼 초안',
    'Accidental Draft',
    '',
    '',
    '',
    '',
    '',
    '',
    '',
    '',
    '2026-08-01'::date,
    '2026-08-31'::date,
    null,
    null,
    null,
    null,
    '00000000-0000-0000-0000-000000000701'::uuid,
    '00000000-0000-0000-0000-000000000701'::uuid
  );

update content.exhibitions
set published_version_id = '70000000-0000-0000-0000-000000000001'::uuid
where id = 'discard-published-test';

create temporary table discard_test_state (
  key text primary key,
  payload jsonb
) on commit drop;
grant select, insert, update, delete on discard_test_state to authenticated;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000702","role":"authenticated"}',
  true
);
select throws_ok(
  $$
    select public.admin_discard_exhibition_draft(
      'discard-published-test',
      '70000000-0000-0000-0000-000000000002'::uuid,
      7,
      '70000000-0000-0000-0000-000000000010'::uuid
    )
  $$,
  '42501',
  'insufficient_staff_role',
  'a contributor cannot discard a published exhibition draft'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000701","role":"authenticated"}',
  true
);
select throws_ok(
  $$
    select public.admin_discard_exhibition_draft(
      'discard-published-test',
      '70000000-0000-0000-0000-000000000002'::uuid,
      6,
      '70000000-0000-0000-0000-000000000011'::uuid
    )
  $$,
  '40001',
  'revision_conflict',
  'the discard command rejects a stale draft revision'
);

insert into pg_temp.discard_test_state (key, payload)
values (
  'discarded',
  public.admin_discard_exhibition_draft(
    'discard-published-test',
    '70000000-0000-0000-0000-000000000002'::uuid,
    7,
    '70000000-0000-0000-0000-000000000012'::uuid
  )
);

select is(
  (select payload ->> 'status' from pg_temp.discard_test_state where key = 'discarded'),
  'published',
  'discard returns the last published state'
);
select is(
  (select payload ->> 'working_version_id' from pg_temp.discard_test_state where key = 'discarded'),
  '70000000-0000-0000-0000-000000000001',
  'discard points the editor back to the published version'
);
select is(
  (select payload ->> 'has_unpublished_changes' from pg_temp.discard_test_state where key = 'discarded'),
  'false',
  'discard clears the unpublished-change marker'
);
select is(
  (
    select count(*)
    from content.exhibition_versions
    where exhibition_id = 'discard-published-test'
  ),
  1::bigint,
  'discard removes only the unpublished working version'
);
select is(
  (
    select version.name_ko
    from content.exhibitions as exhibition
    join content.exhibition_versions as version
      on version.id = exhibition.published_version_id
    where exhibition.id = 'discard-published-test'
  ),
  '마지막 공개 상태',
  'the published pointer remains on the last published snapshot'
);
select is(
  (
    select count(*)
    from content.audit_log
    where entity_type = 'exhibition'
      and entity_id = 'discard-published-test'
      and action = 'exhibition.draft_discarded'
  ),
  1::bigint,
  'discard writes one audit record'
);
select is(
  public.admin_discard_exhibition_draft(
    'discard-published-test',
    '70000000-0000-0000-0000-000000000002'::uuid,
    7,
    '70000000-0000-0000-0000-000000000012'::uuid
  ),
  (select payload from pg_temp.discard_test_state where key = 'discarded'),
  'an identical request replay returns the stored result'
);

select * from finish();
rollback;
