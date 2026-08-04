begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(35);

-- Schema and least-privilege contract.
select has_table('content', 'galleries', 'gallery identities exist');
select has_table(
  'content',
  'gallery_memberships',
  'gallery owner memberships exist'
);
select has_column(
  'content',
  'exhibitions',
  'gallery_id',
  'exhibition identities can be linked to a gallery'
);
select is(
  (
    select count(*)::integer
    from pg_type as type
    join pg_namespace as namespace on namespace.oid = type.typnamespace
    where namespace.nspname = 'content'
      and type.typname in (
        'gallery_status',
        'gallery_member_role',
        'gallery_membership_status'
      )
  ),
  3,
  'gallery lifecycle enums exist'
);
select is(
  (
    select count(*)::integer
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname in ('galleries', 'gallery_memberships')
      and relation.relrowsecurity
  ),
  2,
  'RLS is enabled on both owner tables'
);
select is(
  (
    select count(*)::integer
    from pg_indexes
    where schemaname = 'content'
      and indexname in (
        'galleries_canonical_venue_idx',
        'galleries_name_ko_lower_idx',
        'galleries_name_en_lower_idx',
        'gallery_memberships_user_idx',
        'gallery_memberships_one_active_owner_idx',
        'gallery_memberships_one_workspace_per_user_idx',
        'exhibitions_gallery_idx'
      )
  ),
  7,
  'foreign keys, search, RLS, and partial uniqueness are indexed'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('content.galleries'),
        ('content.gallery_memberships')
    ) as relation(name)
    where has_table_privilege('authenticated', relation.name, 'SELECT')
       or has_table_privilege('authenticated', relation.name, 'INSERT')
       or has_table_privilege('authenticated', relation.name, 'UPDATE')
       or has_table_privilege('authenticated', relation.name, 'DELETE')
  ),
  0,
  'authenticated receives no generic table privileges'
);
select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'owner_current_access',
        'owner_search_galleries',
        'owner_claim_existing_gallery',
        'owner_create_gallery_claim'
      )
      and not procedure.prosecdef
  ),
  4,
  'owner RPC wrappers are SECURITY INVOKER'
);
select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'owner_current_access',
        'owner_search_galleries',
        'owner_claim_existing_gallery',
        'owner_create_gallery_claim'
      )
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  4,
  'owner RPC wrappers pin an empty search path'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('public.owner_current_access()'),
        ('public.owner_search_galleries(text)'),
        ('public.owner_claim_existing_gallery(uuid,text,text,text,uuid)'),
        ('public.owner_create_gallery_claim(text,text,text,text,text,uuid)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  4,
  'authenticated can execute each owner RPC'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('public.owner_current_access()'),
        ('public.owner_search_galleries(text)'),
        ('public.owner_claim_existing_gallery(uuid,text,text,text,uuid)'),
        ('public.owner_create_gallery_claim(text,text,text,text,text,uuid)')
    ) as signature(value)
    where has_function_privilege('anon', signature.value, 'EXECUTE')
  ),
  0,
  'anonymous callers cannot execute owner RPCs'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('public.owner_current_access()'),
        ('public.owner_search_galleries(text)'),
        ('public.owner_claim_existing_gallery(uuid,text,text,text,uuid)'),
        ('public.owner_create_gallery_claim(text,text,text,text,text,uuid)')
    ) as signature(value)
    where has_function_privilege('service_role', signature.value, 'EXECUTE')
  ),
  0,
  'service role receives no implicit owner RPC grant'
);

-- Test identities. Auth user inserts also exercise the existing profile hook.
insert into auth.users (
  id,
  email,
  email_confirmed_at,
  raw_user_meta_data
)
values
  (
    '00000000-0000-0000-0000-000000000801'::uuid,
    'owner-one@example.invalid',
    now(),
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000802'::uuid,
    'owner-two@example.invalid',
    now(),
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000803'::uuid,
    'unconfirmed@example.invalid',
    null,
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000804'::uuid,
    'owner-four@example.invalid',
    now(),
    '{}'::jsonb
  );

insert into content.venues (
  id,
  slug,
  name_ko,
  name_en,
  address_ko,
  created_by
)
values
  (
    '80000000-0000-0000-0000-000000000001'::uuid,
    'gallery-alpha',
    '알파 갤러리',
    'Gallery Alpha',
    '서울특별시 용산구 알파로 1',
    null
  ),
  (
    '80000000-0000-0000-0000-000000000002'::uuid,
    'gallery-beta',
    '베타 갤러리',
    'Gallery Beta',
    '서울특별시 종로구 베타로 2',
    null
  );

insert into content.galleries (
  id,
  canonical_venue_id,
  name_ko,
  name_en,
  status
)
values
  (
    '81000000-0000-0000-0000-000000000001'::uuid,
    '80000000-0000-0000-0000-000000000001'::uuid,
    '알파 갤러리',
    'Gallery Alpha',
    'active'::content.gallery_status
  ),
  (
    '81000000-0000-0000-0000-000000000002'::uuid,
    '80000000-0000-0000-0000-000000000002'::uuid,
    '베타 갤러리',
    'Gallery Beta',
    'active'::content.gallery_status
  ),
  (
    '81000000-0000-0000-0000-000000000003'::uuid,
    null,
    '숨김 갤러리',
    'Hidden Gallery',
    'disabled'::content.gallery_status
  );

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000801","role":"authenticated"}',
  true
);

select is(
  (
    select count(*)
    from public.owner_search_galleries('알파')
  ),
  1::bigint,
  'owner search returns matching active galleries only'
);
select ok(
  (
    select item ? 'gallery_id'
      and item ? 'name_ko'
      and item ? 'name_en'
      and item ? 'address_ko'
      and item ? 'is_claimed'
      and not item ? 'owner_user_id'
      and not item ? 'claim_note'
      and not item ? 'claim_website_url'
      and not item ? 'claim_social_url'
    from public.owner_search_galleries('알파') as item
  ),
  'owner search exposes only the safe gallery DTO'
);

select throws_ok(
  $$ select public.owner_claim_existing_gallery(
    '81000000-0000-0000-0000-000000000001'::uuid,
    null,
    null,
    null,
    '82000000-0000-0000-0000-000000000001'::uuid
  ) $$,
  '22023',
  'gallery_claim_evidence_required',
  'an existing-gallery claim requires evidence'
);

select is(
  public.owner_claim_existing_gallery(
    '81000000-0000-0000-0000-000000000001'::uuid,
    'https://alpha.example.test',
    null,
    'I manage the official gallery website.',
    '82000000-0000-0000-0000-000000000001'::uuid
  ) #>> '{membership,status}',
  'pending',
  'a confirmed owner can create a pending claim'
);
select is(
  public.owner_current_access() #>> '{gallery,id}',
  '81000000-0000-0000-0000-000000000001',
  'current access resolves only the caller workspace'
);

create temporary table gallery_owner_test_state (
  payload jsonb
) on commit drop;
grant select, insert, update, delete on gallery_owner_test_state to authenticated;
insert into pg_temp.gallery_owner_test_state (payload)
values (
  public.owner_claim_existing_gallery(
    '81000000-0000-0000-0000-000000000001'::uuid,
    'https://alpha.example.test',
    null,
    'I manage the official gallery website.',
    '82000000-0000-0000-0000-000000000001'::uuid
  )
);
select is(
  (select payload from pg_temp.gallery_owner_test_state),
  public.owner_current_access(),
  'same-request replay returns the existing claim'
);
select is(
  (
    select count(*)
    from content.audit_log
    where actor_user_id = '00000000-0000-0000-0000-000000000801'::uuid
      and action = 'gallery.claim_requested'
      and request_id = '82000000-0000-0000-0000-000000000001'::uuid
  ),
  1::bigint,
  'claim replay records one audit event'
);
reset role;
select is(
  (
    select count(*)
    from content.outbox_events
    where deduplication_key =
      'gallery.claim_requested:00000000-0000-0000-0000-000000000801:82000000-0000-0000-0000-000000000001'
  ),
  1::bigint,
  'claim replay records one outbox event'
);
select ok(
  (
    select not payload ? 'email'
      and not payload ? 'claim_note'
      and not payload ? 'claim_website_url'
      and not payload ? 'claim_social_url'
    from content.outbox_events
    where deduplication_key =
      'gallery.claim_requested:00000000-0000-0000-0000-000000000801:82000000-0000-0000-0000-000000000001'
  ),
  'outbox payload omits private claim evidence and email'
);
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000801","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.owner_claim_existing_gallery(
    '81000000-0000-0000-0000-000000000002'::uuid,
    'https://beta.example.test',
    null,
    null,
    '82000000-0000-0000-0000-000000000002'::uuid
  ) $$,
  '23505',
  'owner_workspace_already_exists',
  'one user cannot open a second pending workspace'
);
select throws_ok(
  $$ select public.owner_claim_existing_gallery(
    '81000000-0000-0000-0000-000000000002'::uuid,
    'https://different.example.test',
    null,
    null,
    '82000000-0000-0000-0000-000000000001'::uuid
  ) $$,
  '22023',
  'idempotency_conflict',
  'request UUID reuse with different inputs is rejected'
);
reset role;

-- Unconfirmed email is rejected for both claim paths.
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000803","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.owner_claim_existing_gallery(
    '81000000-0000-0000-0000-000000000001'::uuid,
    'https://alpha.example.test',
    null,
    null,
    '82000000-0000-0000-0000-000000000003'::uuid
  ) $$,
  '42501',
  'confirmed_email_required',
  'existing claim requires a confirmed email'
);
select throws_ok(
  $$ select public.owner_create_gallery_claim(
    '새 갤러리',
    'New Gallery',
    'https://new.example.test',
    null,
    null,
    '82000000-0000-0000-0000-000000000004'::uuid
  ) $$,
  '42501',
  'confirmed_email_required',
  'new gallery claim requires a confirmed email'
);
reset role;

-- Multiple pending claims can coexist, but only one can become active.
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000802","role":"authenticated"}',
  true
);
select is(
  public.owner_claim_existing_gallery(
    '81000000-0000-0000-0000-000000000001'::uuid,
    null,
    'https://social.example.test/alpha',
    null,
    '82000000-0000-0000-0000-000000000005'::uuid
  ) #>> '{membership,status}',
  'pending',
  'a second legitimate claimant can enter staff review'
);
reset role;

update content.gallery_memberships
set status = 'active'::content.gallery_membership_status
where user_id = '00000000-0000-0000-0000-000000000802'::uuid;

select throws_ok(
  $$ update content.gallery_memberships
     set status = 'active'::content.gallery_membership_status
     where user_id = '00000000-0000-0000-0000-000000000801'::uuid $$,
  '23505',
  null,
  'a gallery cannot have two active owners'
);

-- Once a gallery has an active owner, new claims fail before membership write.
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000804","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.owner_claim_existing_gallery(
    '81000000-0000-0000-0000-000000000001'::uuid,
    'https://alpha.example.test',
    null,
    null,
    '82000000-0000-0000-0000-000000000006'::uuid
  ) $$,
  '23505',
  'gallery_already_claimed',
  'an actively owned gallery cannot receive a new claim'
);
select throws_ok(
  $$ select * from content.gallery_memberships $$,
  '42501',
  null,
  'owner cannot bypass RPC privacy with direct table reads'
);

select is(
  public.owner_create_gallery_claim(
    '  감마 갤러리  ',
    '  Gallery Gamma  ',
    'https://gamma.example.test',
    null,
    'Official opening workspace.',
    '82000000-0000-0000-0000-000000000007'::uuid
  ) #>> '{membership,status}',
  'pending',
  'confirmed owner can create a new pending gallery workspace'
);
select is(
  public.owner_current_access() #>> '{gallery,name_ko}',
  '감마 갤러리',
  'new gallery names are normalized'
);
select is(
  (
    select count(*)
    from content.audit_log
    where actor_user_id = '00000000-0000-0000-0000-000000000804'::uuid
      and action = 'gallery.created_and_claimed'
      and request_id = '82000000-0000-0000-0000-000000000007'::uuid
  ),
  1::bigint,
  'new gallery claim records one audit event'
);
reset role;
select is(
  (
    select count(*)
    from content.outbox_events
    where deduplication_key =
      'gallery.created_and_claimed:00000000-0000-0000-0000-000000000804:82000000-0000-0000-0000-000000000007'
  ),
  1::bigint,
  'new gallery claim records one outbox event'
);

update content.gallery_memberships
set status = 'suspended'::content.gallery_membership_status
where user_id = '00000000-0000-0000-0000-000000000804'::uuid;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000804","role":"authenticated"}',
  true
);
select is(
  public.owner_current_access() #>> '{membership,status}',
  'suspended',
  'current access preserves suspended state for fail-closed UI'
);
reset role;

-- Stable gallery ownership does not replace the versioned venue snapshot.
insert into content.exhibitions (id, gallery_id)
values (
  'gallery-owned-contract-test',
  '81000000-0000-0000-0000-000000000002'::uuid
);
select throws_ok(
  $$ delete from content.galleries
     where id = '81000000-0000-0000-0000-000000000002'::uuid $$,
  '23503',
  null,
  'linked gallery identities cannot be deleted out from under exhibitions'
);

select * from finish();
rollback;
