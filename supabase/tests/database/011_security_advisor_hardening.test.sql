begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(10);

select ok(
  not exists (
    select 1
    from pg_proc as procedure
    join pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    cross join lateral aclexplode(
      coalesce(
        procedure.proacl,
        acldefault('f', procedure.proowner)
      )
    ) as privilege
    where namespace.nspname = 'public'
      and procedure.proname = 'rls_auto_enable'
      and procedure.pronargs = 0
      and procedure.prokind = 'f'
      and privilege.grantee = 0
      and privilege.privilege_type = 'EXECUTE'
  ),
  'PUBLIC cannot execute public.rls_auto_enable() when the helper exists'
);

select ok(
  not exists (
    select 1
    from pg_proc as procedure
    join pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'rls_auto_enable'
      and procedure.pronargs = 0
      and procedure.prokind = 'f'
      and has_function_privilege('anon', procedure.oid, 'EXECUTE')
  ),
  'anonymous callers cannot execute public.rls_auto_enable()'
);

select ok(
  not exists (
    select 1
    from pg_proc as procedure
    join pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'rls_auto_enable'
      and procedure.pronargs = 0
      and procedure.prokind = 'f'
      and has_function_privilege('authenticated', procedure.oid, 'EXECUTE')
  ),
  'authenticated callers cannot execute public.rls_auto_enable()'
);

select is(
  (
    select count(*)::integer
    from storage.buckets
    where id in ('avatars', 'exhibition-images')
      and public
  ),
  2,
  'avatar and exhibition-image buckets remain public for object delivery'
);

select is(
  (
    select count(*)::integer
    from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname in (
        'Public read avatars',
        'Public read exhibition images'
      )
  ),
  0,
  'bucket-wide legacy read policies are removed'
);

select ok(
  not exists (
    select 1
    from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and cmd = 'SELECT'
      and roles @> array['public']::name[]
      and (
        coalesce(qual, '') like '%avatars%'
        or coalesce(qual, '') like '%exhibition-images%'
      )
  ),
  'no public-role policy allows listing either public bucket'
);

select is(
  (
    select count(*)::integer
    from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname = 'Owner read avatars'
      and cmd = 'SELECT'
  ),
  1,
  'one authenticated avatar owner-read policy exists'
);

select is(
  (
    select roles
    from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname = 'Owner read avatars'
      and cmd = 'SELECT'
  ),
  array['authenticated']::name[],
  'avatar owner-read is restricted to the authenticated role'
);

select ok(
  (
    select
      coalesce(qual, '') like '%bucket_id = ''avatars''%'
      and coalesce(qual, '') like '%auth.uid()%'
      and coalesce(qual, '') like '%split_part(name, ''.''::text, 1)%'
    from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname = 'Owner read avatars'
      and cmd = 'SELECT'
  ),
  'avatar owner-read checks both bucket and path ownership'
);

select is(
  (
    select count(*)::integer
    from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname in (
        'Owner upload avatars',
        'Owner update avatars'
      )
      and cmd in ('INSERT', 'UPDATE')
  ),
  2,
  'existing avatar insert and update paths remain available'
);

select * from finish();

rollback;
