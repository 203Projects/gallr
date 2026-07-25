-- Remove client access to Supabase's privileged RLS helper when it is present.
-- The helper is created outside this repository's migration history on hosted
-- projects, so keep this migration compatible with local stacks where it may
-- not exist.
do $$
begin
  if to_regprocedure('public.rls_auto_enable()') is not null then
    execute
      'revoke execute on function public.rls_auto_enable() '
      'from public, anon, authenticated';
  end if;
end;
$$;

-- Public buckets serve object URLs without a storage.objects SELECT policy.
-- Remove bucket-wide listing while retaining public delivery.
drop policy if exists "Public read avatars"
  on storage.objects;

drop policy if exists "Public read exhibition images"
  on storage.objects;

-- Avatar upsert still requires SELECT in addition to the existing INSERT and
-- UPDATE policies, but callers may only select their own path.
drop policy if exists "Owner read avatars"
  on storage.objects;

create policy "Owner read avatars"
  on storage.objects
  for select
  to authenticated
  using (
    bucket_id = 'avatars'
    and (select auth.uid())::text = split_part(name, '.', 1)
  );
