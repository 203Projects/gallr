begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(17);

select has_function(
  'public',
  'admin_delete_exhibition_draft',
  array['text', 'uuid', 'integer', 'uuid'],
  'the public permanent-draft deletion command exists'
);
select ok(
  has_function_privilege(
    'authenticated',
    'public.admin_delete_exhibition_draft(text, uuid, integer, uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.admin_delete_exhibition_draft(text, uuid, integer, uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'public.admin_delete_exhibition_draft(text, uuid, integer, uuid)',
    'EXECUTE'
  ),
  'only authenticated can execute the public deletion command'
);
select ok(
  not (
    select procedure.prosecdef
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'admin_delete_exhibition_draft'
  ),
  'the public deletion command is SECURITY INVOKER'
);
select ok(
  (
    select procedure.proconfig @> array['search_path=""']::text[]
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'admin_delete_exhibition_draft'
  ),
  'the public deletion command pins an empty search_path'
);

insert into auth.users (id, email, raw_user_meta_data)
values
  (
    '00000000-0000-0000-0000-000000000601'::uuid,
    'delete-publisher@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000602'::uuid,
    'delete-admin@example.invalid',
    '{}'::jsonb
  );

insert into content.staff_members (user_id, role, active)
values
  (
    '00000000-0000-0000-0000-000000000601'::uuid,
    'publisher'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000602'::uuid,
    'admin'::content.staff_role,
    true
  );

create temporary table delete_test_state (
  key text primary key,
  payload jsonb
) on commit drop;
grant select, insert, update, delete on delete_test_state to authenticated;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000601","role":"authenticated"}',
  true
);
insert into pg_temp.delete_test_state (key, payload)
values ('publisher_draft', public.admin_create_exhibition_draft());

select throws_ok(
  format(
    'select public.admin_delete_exhibition_draft(%L, %L::uuid, 1, %L::uuid)',
    (select payload ->> 'id' from pg_temp.delete_test_state where key = 'publisher_draft'),
    (select payload ->> 'working_version_id' from pg_temp.delete_test_state where key = 'publisher_draft'),
    '60000000-0000-0000-0000-000000000001'
  ),
  '42501',
  'insufficient_staff_role',
  'a publisher cannot permanently delete a draft'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000602","role":"authenticated"}',
  true
);
insert into pg_temp.delete_test_state (key, payload)
values ('admin_draft', public.admin_create_exhibition_draft());

select throws_ok(
  format(
    'select public.admin_delete_exhibition_draft(%L, %L::uuid, 1, %L::uuid)',
    (select payload ->> 'id' from pg_temp.delete_test_state where key = 'admin_draft'),
    '60000000-0000-0000-0000-000000000099',
    '60000000-0000-0000-0000-000000000006'
  ),
  'P0002',
  'working_version_not_found',
  'the command rejects a non-current working-version ID'
);

select throws_ok(
  format(
    'select public.admin_delete_exhibition_draft(%L, %L::uuid, 0, %L::uuid)',
    (select payload ->> 'id' from pg_temp.delete_test_state where key = 'admin_draft'),
    (select payload ->> 'working_version_id' from pg_temp.delete_test_state where key = 'admin_draft'),
    '60000000-0000-0000-0000-000000000002'
  ),
  '40001',
  'revision_conflict',
  'the command rejects a stale revision'
);

update pg_temp.delete_test_state
set payload = public.admin_delete_exhibition_draft(
  payload ->> 'id',
  (payload ->> 'working_version_id')::uuid,
  (payload ->> 'revision')::integer,
  '60000000-0000-0000-0000-000000000003'::uuid
)
where key = 'admin_draft';

select is(
  (select payload ->> 'status' from pg_temp.delete_test_state where key = 'admin_draft'),
  'deleted',
  'the command reports permanent deletion'
);
select is(
  (
    select count(*)
    from content.exhibitions
    where id = (
      select payload ->> 'id'
      from pg_temp.delete_test_state
      where key = 'admin_draft'
    )
  ),
  0::bigint,
  'the draft identity is deleted'
);
select is(
  (
    select count(*)
    from content.exhibition_versions
    where exhibition_id = (
      select payload ->> 'id'
      from pg_temp.delete_test_state
      where key = 'admin_draft'
    )
  ),
  0::bigint,
  'the draft version is deleted'
);
select is(
  (
    select count(*)
    from content.audit_log
    where entity_id = (
      select payload ->> 'id'
      from pg_temp.delete_test_state
      where key = 'admin_draft'
    )
      and action = 'exhibition.draft_deleted'
  ),
  1::bigint,
  'deletion appends an audit event'
);

select is(
  public.admin_delete_exhibition_draft(
    (select payload ->> 'id' from pg_temp.delete_test_state where key = 'admin_draft'),
    (select (payload ->> 'working_version_id')::uuid from pg_temp.delete_test_state where key = 'admin_draft'),
    (select (payload ->> 'revision')::integer from pg_temp.delete_test_state where key = 'admin_draft'),
    '60000000-0000-0000-0000-000000000003'::uuid
  ),
  (select payload from pg_temp.delete_test_state where key = 'admin_draft'),
  'replaying the request returns the stored response'
);
select is(
  (
    select count(*)
    from content.audit_log
    where entity_id = (
      select payload ->> 'id'
      from pg_temp.delete_test_state
      where key = 'admin_draft'
    )
      and action = 'exhibition.draft_deleted'
  ),
  1::bigint,
  'replay does not duplicate the audit event'
);

insert into pg_temp.delete_test_state (key, payload)
values ('media_draft', public.admin_create_exhibition_draft());
reset role;

insert into content.media_assets (
  id,
  bucket_id,
  object_path,
  uploaded_by
)
values (
  '60000000-0000-0000-0000-000000000101'::uuid,
  'exhibition-media',
  'tests/delete-draft.jpg',
  '00000000-0000-0000-0000-000000000602'::uuid
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
  '60000000-0000-0000-0000-000000000101'::uuid,
  'cover'::content.media_role,
  0,
  '00000000-0000-0000-0000-000000000602'::uuid
from pg_temp.delete_test_state
where key = 'media_draft';

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000602","role":"authenticated"}',
  true
);
select throws_ok(
  format(
    'select public.admin_delete_exhibition_draft(%L, %L::uuid, 1, %L::uuid)',
    (select payload ->> 'id' from pg_temp.delete_test_state where key = 'media_draft'),
    (select payload ->> 'working_version_id' from pg_temp.delete_test_state where key = 'media_draft'),
    '60000000-0000-0000-0000-000000000004'
  ),
  '23503',
  'draft_delete_requires_media_detach',
  'a draft with attached media cannot be deleted'
);
select is(
  (
    select count(*)
    from content.exhibitions
    where id = (
      select payload ->> 'id'
      from pg_temp.delete_test_state
      where key = 'media_draft'
    )
  ),
  1::bigint,
  'a rejected media deletion leaves the identity intact'
);

reset role;
insert into content.exhibitions (
  id,
  created_by,
  updated_by
)
values (
  'delete-published-test',
  '00000000-0000-0000-0000-000000000602'::uuid,
  '00000000-0000-0000-0000-000000000602'::uuid
);
insert into content.exhibition_versions (
  id,
  exhibition_id,
  version_number,
  revision,
  status,
  name_ko,
  venue_name_ko,
  city_ko,
  region_ko,
  address_ko,
  opening_date,
  closing_date,
  latitude,
  longitude,
  published_at,
  published_by,
  created_by,
  updated_by
)
values (
  '60000000-0000-0000-0000-000000000201'::uuid,
  'delete-published-test',
  1,
  1,
  'published'::content.exhibition_version_status,
  '삭제 불가 공개 전시',
  '테스트 전시장',
  '서울',
  '용산구',
  '서울 용산구 한남대로 28',
  '2026-07-28'::date,
  '2026-08-28'::date,
  37.5344,
  127.0005,
  now(),
  '00000000-0000-0000-0000-000000000602'::uuid,
  '00000000-0000-0000-0000-000000000602'::uuid,
  '00000000-0000-0000-0000-000000000602'::uuid
);
update content.exhibitions
set published_version_id = '60000000-0000-0000-0000-000000000201'::uuid
where id = 'delete-published-test';

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000602","role":"authenticated"}',
  true
);
select throws_ok(
  $$
    select public.admin_delete_exhibition_draft(
      'delete-published-test',
      '60000000-0000-0000-0000-000000000201'::uuid,
      1,
      '60000000-0000-0000-0000-000000000005'::uuid
    )
  $$,
  '22023',
  'only_never_published_drafts_can_be_deleted',
  'a published identity cannot be permanently deleted'
);
select is(
  (
    select count(*)
    from content.exhibitions
    where id = 'delete-published-test'
  ),
  1::bigint,
  'a rejected published deletion leaves the identity intact'
);

select * from finish();
rollback;
