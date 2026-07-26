begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(43);

select has_schema('content', 'content schema exists');
select has_schema('content_private', 'content_private schema exists');

select is(
  (
    select count(*)::integer
    from information_schema.tables
    where table_schema = 'content'
      and table_name in (
        'staff_members',
        'venues',
        'exhibitions',
        'exhibition_versions',
        'media_assets',
        'exhibition_version_media',
        'curation_placements',
        'audit_log',
        'outbox_events',
        'exhibition_submissions',
        'submission_media'
      )
  ),
  11,
  'all CMS foundation tables exist'
);

select is(
  (
    select count(*)::integer
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname in (
        'staff_members',
        'venues',
        'exhibitions',
        'exhibition_versions',
        'media_assets',
        'exhibition_version_media',
        'curation_placements',
        'audit_log',
        'outbox_events',
        'exhibition_submissions',
        'submission_media'
      )
      and relation.relrowsecurity
  ),
  11,
  'RLS is enabled on every CMS foundation table'
);

select ok(
  not has_schema_privilege('anon', 'content', 'USAGE'),
  'anon cannot access the private content schema'
);
select ok(
  has_schema_privilege('authenticated', 'content', 'USAGE'),
  'authenticated can resolve explicitly granted content objects'
);
select ok(
  not has_table_privilege('anon', 'content.venues', 'SELECT'),
  'anon has no venue table grant'
);
select ok(
  has_table_privilege('authenticated', 'content.venues', 'SELECT'),
  'authenticated has the venue grant controlled by RLS'
);
select ok(
  not has_table_privilege('authenticated', 'content.exhibitions', 'UPDATE'),
  'authenticated cannot update publication pointers directly'
);
select ok(
  not has_table_privilege('authenticated', 'content.venues', 'UPDATE'),
  'authenticated venue changes must use a future command function'
);
select ok(
  not has_table_privilege('authenticated', 'content.exhibition_versions', 'UPDATE'),
  'authenticated draft changes must use the revision-safe save command'
);
select ok(
  not has_table_privilege('authenticated', 'content.curation_placements', 'UPDATE'),
  'authenticated curation changes must use a future command function'
);
select ok(
  not has_column_privilege(
    'authenticated',
    'content.exhibition_version_media',
    'version_id',
    'UPDATE'
  ),
  'authenticated users cannot move media attachments between drafts'
);
select ok(
  not has_column_privilege(
    'authenticated',
    'content.exhibition_version_media',
    'sort_order',
    'UPDATE'
  ),
  'Phase 2 requires RPCs for authenticated media reordering'
);
select ok(
  has_function_privilege(
    'authenticated',
    'content_private.has_staff_role(content.staff_role)',
    'EXECUTE'
  ),
  'authenticated can execute only the safe caller-scoped role helper'
);
select ok(
  to_regprocedure('public.handle_new_user()') is null,
  'the privileged signup trigger function is not exposed in public'
);

select is(
  (select public from storage.buckets where id = 'exhibition-media'),
  false,
  'the editorial media bucket is private'
);
select is(
  (select file_size_limit from storage.buckets where id = 'exhibition-media'),
  10485760::bigint,
  'the editorial media bucket enforces a 10 MiB limit'
);
select is(
  (select allowed_mime_types from storage.buckets where id = 'exhibition-media'),
  array['image/jpeg', 'image/png', 'image/webp']::text[],
  'the editorial media bucket allows only supported image MIME types'
);

create function content_private.default_privilege_probe()
returns boolean
language sql
as $$ select true $$;

select ok(
  not has_function_privilege(
    'anon',
    'content_private.default_privilege_probe()',
    'EXECUTE'
  ),
  'future private helpers do not default to anon execute'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'content_private.default_privilege_probe()',
    'EXECUTE'
  ),
  'future private helpers do not default to authenticated execute'
);

drop function content_private.default_privilege_probe();

insert into auth.users (id, email, raw_user_meta_data)
values
  (
    '00000000-0000-0000-0000-000000000101'::uuid,
    'cms-normal@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000102'::uuid,
    'cms-contributor@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000103'::uuid,
    'cms-admin@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000104'::uuid,
    'cms-publisher@example.invalid',
    '{}'::jsonb
  );

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000101","role":"authenticated"}',
  true
);

select is(
  content_private.has_staff_role('contributor'::content.staff_role),
  false,
  'a normal authenticated user is not editorial staff'
);

update public.profiles
set is_admin = true
where id = '00000000-0000-0000-0000-000000000101'::uuid;

select is(
  (
    select is_admin
    from public.profiles
    where id = '00000000-0000-0000-0000-000000000101'::uuid
  ),
  false,
  'a profile owner cannot promote themselves through is_admin'
);

reset role;

insert into content.staff_members (user_id, role, active)
values (
  '00000000-0000-0000-0000-000000000103'::uuid,
  'admin'::content.staff_role,
  true
);

select is(
  (
    select is_admin
    from public.profiles
    where id = '00000000-0000-0000-0000-000000000103'::uuid
  ),
  true,
  'active admin membership is mirrored to the legacy profile column'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000103","role":"authenticated"}',
  true
);

select is(
  content_private.has_staff_role('admin'::content.staff_role),
  true,
  'the role helper recognizes an active admin'
);

update public.profiles
set display_name = 'CMS Admin', is_admin = false
where id = '00000000-0000-0000-0000-000000000103'::uuid;

select is(
  (
    select is_admin
    from public.profiles
    where id = '00000000-0000-0000-0000-000000000103'::uuid
  ),
  true,
  'legacy profile upserts cannot clear the derived admin mirror'
);

reset role;
delete from content.staff_members
where user_id = '00000000-0000-0000-0000-000000000103'::uuid;

select is(
  (
    select is_admin
    from public.profiles
    where id = '00000000-0000-0000-0000-000000000103'::uuid
  ),
  false,
  'removing staff membership clears the compatibility mirror'
);

insert into content.staff_members (user_id, role, active)
values
  (
    '00000000-0000-0000-0000-000000000102'::uuid,
    'contributor'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000104'::uuid,
    'publisher'::content.staff_role,
    true
  );

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000102","role":"authenticated"}',
  true
);

select is(
  content_private.has_staff_role('contributor'::content.staff_role),
  true,
  'a contributor satisfies the contributor role'
);
select is(
  content_private.has_staff_role('publisher'::content.staff_role),
  false,
  'a contributor does not satisfy the publisher role'
);

select throws_ok(
  $$
    insert into content.venues (
      id,
      slug,
      name_ko,
      created_by
    ) values (
      '10000000-0000-0000-0000-000000000001'::uuid,
      'cms-test-venue',
      '테스트 전시장',
      '00000000-0000-0000-0000-000000000102'::uuid
    )
  $$,
  '42501',
  null,
  'Phase 2 denies direct contributor venue inserts'
);

select is(
  (select count(*) from content.venues),
  0::bigint,
  'a contributor can still read the empty editorial venue set'
);

reset role;

select throws_ok(
  $$
    insert into content.venues (
      slug,
      name_ko,
      latitude,
      created_by
    ) values (
      'invalid-coordinate-pair',
      '좌표 오류',
      37.5,
      '00000000-0000-0000-0000-000000000102'::uuid
    )
  $$,
  '23514',
  null,
  'venue coordinates must be supplied as a pair'
);

select throws_ok(
  $$
    insert into content.media_assets (
      bucket_id,
      object_path,
      width,
      uploaded_by
    ) values (
      'exhibition-media',
      'drafts/incomplete-dimensions.jpg',
      1600,
      '00000000-0000-0000-0000-000000000102'::uuid
    )
  $$,
  '23514',
  null,
  'media dimensions must be supplied as a positive pair'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000102","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    insert into content.exhibitions (id, created_by)
    values (
      '00000000-0000-0000-0000-000000000201',
      '00000000-0000-0000-0000-000000000102'::uuid
    )
  $$,
  '42501',
  null,
  'Phase 2 denies direct contributor exhibition identity inserts'
);

select throws_ok(
  $$
    insert into content.exhibition_versions (
      exhibition_id,
      version_number,
      status,
      created_by
    ) values (
      '00000000-0000-0000-0000-000000000201',
      1,
      'draft'::content.exhibition_version_status,
      '00000000-0000-0000-0000-000000000102'::uuid
    )
  $$,
  '42501',
  null,
  'Phase 2 denies direct contributor draft version inserts'
);

select throws_ok(
  $$
    insert into content.exhibition_versions (
      exhibition_id,
      version_number,
      status,
      published_at,
      created_by
    ) values (
      '00000000-0000-0000-0000-000000000201',
      2,
      'published'::content.exhibition_version_status,
      now(),
      '00000000-0000-0000-0000-000000000102'::uuid
    )
  $$,
  '42501',
  null,
  'a contributor cannot bypass the future publish command'
);

insert into public.thoughts (
  user_id,
  exhibition_id,
  content,
  is_approved
) values (
  '00000000-0000-0000-0000-000000000102'::uuid,
  '00000000-0000-0000-0000-000000000201',
  'Pending editorial thought',
  true
);

select is(
  (
    select is_approved
    from public.thoughts
    where user_id = '00000000-0000-0000-0000-000000000102'::uuid
      and exhibition_id = '00000000-0000-0000-0000-000000000201'
  ),
  false,
  'a non-admin author cannot self-approve a thought'
);

reset role;

insert into content.audit_log (actor_user_id, action, entity_type, entity_id)
values
  (
    '00000000-0000-0000-0000-000000000102'::uuid,
    'draft.created',
    'exhibition',
    '00000000-0000-0000-0000-000000000201'
  ),
  (
    '00000000-0000-0000-0000-000000000103'::uuid,
    'staff.changed',
    'staff_member',
    '00000000-0000-0000-0000-000000000104'
  );

insert into content.exhibition_submissions (payload)
values ('{"name_ko":"비공개 제출"}'::jsonb);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000102","role":"authenticated"}',
  true
);

select is(
  (
    select count(*)
    from content.audit_log
    where entity_id in (
      '00000000-0000-0000-0000-000000000201',
      '00000000-0000-0000-0000-000000000104'
    )
  ),
  1::bigint,
  'a contributor can read only their own audit entries'
);
select is(
  (select count(*) from content.exhibition_submissions),
  0::bigint,
  'a contributor cannot read private submission data'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000104","role":"authenticated"}',
  true
);

select is(
  (
    select count(*)
    from content.audit_log
    where entity_id in (
      '00000000-0000-0000-0000-000000000201',
      '00000000-0000-0000-0000-000000000104'
    )
  ),
  2::bigint,
  'a publisher can read every fixture audit entry'
);
select is(
  (select count(*) from content.exhibition_submissions),
  1::bigint,
  'a publisher can read private submission data'
);
select throws_ok(
  $$
    insert into content.curation_placements (
      surface,
      exhibition_id,
      created_by
    ) values (
      'app_featured'::content.curation_surface,
      '00000000-0000-0000-0000-000000000201',
      '00000000-0000-0000-0000-000000000104'::uuid
    )
  $$,
  '42501',
  null,
  'a publisher cannot curate an exhibition without a published version'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000101","role":"authenticated"}',
  true
);

select is(
  (select count(*) from content.venues),
  0::bigint,
  'RLS hides editorial venues from a non-staff user'
);

select * from finish();
rollback;
