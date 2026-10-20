-- Spec 098: the app version policy, read by anyone, written by staff only.

begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(19);

-- == privileges ==
select ok(
  has_function_privilege('anon', 'public.get_app_version_policy(text)', 'EXECUTE'),
  'anyone reads the policy'
);
select ok(
  has_function_privilege('authenticated', 'public.get_app_version_policy(text)', 'EXECUTE'),
  'signed-in readers read the policy'
);
select ok(
  not has_function_privilege('anon', 'public.admin_set_app_version_policy(text,text,text,text)', 'EXECUTE'),
  'anon cannot execute the write'
);
select ok(
  not has_table_privilege('anon', 'content.app_version_policy', 'SELECT')
  and not has_table_privilege('authenticated', 'content.app_version_policy', 'SELECT'),
  'the table itself is private'
);
select ok(
  (select proconfig @> array['search_path=""'] from pg_proc
   where oid = 'public.get_app_version_policy(text)'::regprocedure)
  and (select proconfig @> array['search_path=""'] from pg_proc
   where oid = 'public.admin_set_app_version_policy(text,text,text,text)'::regprocedure),
  'both functions pin an empty search_path'
);

-- == seed ==
select is(
  (select public.get_app_version_policy('android')),
  '{"minimum_version": "1.13.1", "recommended_version": "1.13.1"}'::jsonb,
  'android starts at the current store version'
);
select is(
  (select public.get_app_version_policy('ios')),
  '{"minimum_version": "1.13.1", "recommended_version": "1.13.1"}'::jsonb,
  'ios starts at the current store version'
);
select is(
  (select public.get_app_version_policy('web')),
  null::jsonb,
  'an unknown platform reads null'
);

-- == a non-staff account is refused ==
insert into auth.users (id, email, email_confirmed_at, is_anonymous, raw_user_meta_data) values
  ('00000000-0000-4000-8000-00000000d801', 'policy-staff@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000d802', 'policy-reader@example.invalid', now(), false, '{}');
insert into content.staff_members (user_id, role, active)
  values ('00000000-0000-4000-8000-00000000d801', 'admin', true);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000d802', true);
select throws_ok(
  $$ select public.admin_set_app_version_policy('android', '1.14.0', '1.14.0', null) $$,
  '42501',
  null,
  'a signed-in visitor who is not staff cannot write'
);
reset role;
select is(
  (select public.get_app_version_policy('android') ->> 'minimum_version'),
  '1.13.1',
  'the refused write changed nothing'
);

-- == staff write ==
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000d801', true);
select is(
  (select public.admin_set_app_version_policy('android', '1.14.0', '1.15.0', '1.14.0')
     - 'updated_at' - 'updated_by'),
  '{"platform": "android", "minimum_version": "1.14.0", "recommended_version": "1.15.0", "latest_released_version": "1.14.0"}'::jsonb,
  'staff store a row and get it back'
);
select is(
  (select public.admin_set_app_version_policy('android', '1.14.0', '1.15.0', '1.14.0') ->> 'updated_by'),
  '00000000-0000-4000-8000-00000000d801',
  'the write is attributed to the staff member'
);
select ok(
  (select (public.admin_set_app_version_policy('android', '1.14.0', '1.15.0', '1.14.0') ->> 'updated_at')::timestamptz
     > now() - interval '1 minute'),
  'the write is timestamped'
);
select is(
  (select public.get_app_version_policy('ios') ->> 'minimum_version'),
  '1.13.1',
  'the other platform is untouched'
);

-- == format and ordering are refused, leaving the row unchanged ==
select throws_ok(
  $$ select public.admin_set_app_version_policy('android', '1.14', '1.15.0', null) $$,
  '22023',
  null,
  'a version that is not three numbers is refused'
);
select throws_ok(
  $$ select public.admin_set_app_version_policy('android', '1.16.0', '1.15.0', null) $$,
  '22023',
  null,
  'a recommended version below the minimum is refused'
);
select throws_ok(
  $$ select public.admin_set_app_version_policy('web', '1.14.0', '1.14.0', null) $$,
  '22023',
  null,
  'an unknown platform is refused'
);
select is(
  (select public.get_app_version_policy('android')),
  '{"minimum_version": "1.14.0", "recommended_version": "1.15.0"}'::jsonb,
  'the refused writes changed nothing'
);
select is(
  (select public.admin_set_app_version_policy('android', '1.14.0', '1.15.0', null) ->> 'latest_released_version'),
  null,
  'the last released version may be cleared'
);
reset role;

select * from finish();
rollback;
