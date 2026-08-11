begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(27);

select ok(
  not has_table_privilege('authenticated', 'content.editor_memberships', 'SELECT'),
  'authenticated cannot read editor memberships directly'
);
select ok(
  not has_table_privilege('authenticated', 'content.editor_memberships', 'UPDATE'),
  'authenticated cannot change editor memberships directly'
);
select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in ('editor_list_pick_candidates', 'editor_set_pick')
      and not procedure.prosecdef
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  2,
  'editor RPCs are SECURITY INVOKER with an empty search path'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('public.editor_list_pick_candidates(text)'),
        ('public.editor_set_pick(text,uuid,integer,boolean)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  2,
  'authenticated receives the narrow editor RPC surface'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('public.editor_list_pick_candidates(text)'),
        ('public.editor_set_pick(text,uuid,integer,boolean)')
    ) as signature(value)
    where has_function_privilege('anon', signature.value, 'EXECUTE')
       or has_function_privilege('service_role', signature.value, 'EXECUTE')
  ),
  0,
  'anon and service role receive no editor RPC grant'
);

insert into auth.users (id, email, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000002301', 'editor-one@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002302', 'editor-two@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002303', 'portal-staff@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002304', 'inactive-editor@example.invalid', '{}'::jsonb);

insert into public.editors (
  id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en,
  is_active, active_from
)
values
  ('portal-editor-one', '에디터 하나', 'Editor One', '에디터', 'Editor', '소개', 'Bio', true, current_date),
  ('portal-editor-two', '에디터 둘', 'Editor Two', '에디터', 'Editor', '소개', 'Bio', true, current_date),
  ('portal-editor-inactive', '비활성 에디터', 'Inactive Editor', '에디터', 'Editor', '소개', 'Bio', false, current_date);

insert into content.editor_memberships (user_id, editor_id, active)
values
  ('00000000-0000-0000-0000-000000002301', 'portal-editor-one', true),
  ('00000000-0000-0000-0000-000000002302', 'portal-editor-two', true),
  ('00000000-0000-0000-0000-000000002304', 'portal-editor-inactive', false);

insert into content.staff_members (user_id, role, active)
values ('00000000-0000-0000-0000-000000002303', 'contributor', true);

insert into content.exhibitions (id, created_by, updated_by)
values
  ('portal-available', '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303'),
  ('portal-own', '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303'),
  ('portal-other', '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303'),
  ('portal-working-other', '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303');

insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status, editor_id,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date,
  description_ko, published_at, published_by, created_by, updated_by
)
values
  ('23000000-0000-0000-0000-000000000001', 'portal-available', 1, 2, 'published', null,
    '선택 가능', 'Available', '갤러리 A', 'Gallery A',
    '서울', 'Seoul', '종로구', 'Jongno-gu', '서울 종로구 1', '1 Jongno-gu, Seoul',
    37.57, 126.98, current_date, current_date + 30,
    '보존할 설명', now(), '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303'),
  ('23000000-0000-0000-0000-000000000002', 'portal-own', 1, 5, 'published', 'portal-editor-one',
    '나의 선택', 'My pick', '갤러리 B', 'Gallery B',
    '서울', 'Seoul', '종로구', 'Jongno-gu', '서울 종로구 2', '2 Jongno-gu, Seoul',
    37.571, 126.981, current_date, current_date + 30,
    '설명', now(), '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303'),
  ('23000000-0000-0000-0000-000000000003', 'portal-other', 1, 4, 'published', 'portal-editor-two',
    '다른 선택', 'Other pick', '갤러리 C', 'Gallery C',
    '서울', 'Seoul', '종로구', 'Jongno-gu', '서울 종로구 3', '3 Jongno-gu, Seoul',
    37.572, 126.982, current_date, current_date + 30,
    '설명', now(), '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303'),
  ('23000000-0000-0000-0000-000000000004', 'portal-working-other', 1, 3, 'published', 'portal-editor-two',
    '작업 중', 'Being edited', '갤러리 D', 'Gallery D',
    '서울', 'Seoul', '종로구', 'Jongno-gu', '서울 종로구 4', '4 Jongno-gu, Seoul',
    37.573, 126.983, current_date, current_date + 30,
    '설명', now(), '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303'),
  ('23000000-0000-0000-0000-000000000005', 'portal-working-other', 2, 4, 'draft', null,
    '작업 중', 'Being edited', '갤러리 D', 'Gallery D',
    '서울', 'Seoul', '종로구', 'Jongno-gu', '서울 종로구 4', '4 Jongno-gu, Seoul',
    37.573, 126.983, current_date, current_date + 30,
    '설명', null, null, '00000000-0000-0000-0000-000000002303', '00000000-0000-0000-0000-000000002303');

update content.exhibitions
set published_version_id = case id
  when 'portal-available' then '23000000-0000-0000-0000-000000000001'::uuid
  when 'portal-own' then '23000000-0000-0000-0000-000000000002'::uuid
  when 'portal-other' then '23000000-0000-0000-0000-000000000003'::uuid
  when 'portal-working-other' then '23000000-0000-0000-0000-000000000004'::uuid
end;

insert into content.media_assets (
  id, status, bucket_id, object_path, public_url,
  mime_type, byte_size, checksum_sha256, uploaded_by, published_at
)
values (
  '23000000-0000-0000-0000-000000000010', 'published', 'exhibition-media',
  'portal/source.jpg', 'https://example.invalid/portal.jpg', 'image/jpeg', 100,
  repeat('a', 64), '00000000-0000-0000-0000-000000002303', now()
);

insert into content.exhibition_version_media (
  version_id, media_id, role, sort_order, created_by
)
values (
  '23000000-0000-0000-0000-000000000001',
  '23000000-0000-0000-0000-000000000010', 'cover', 0,
  '00000000-0000-0000-0000-000000002303'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002301","role":"authenticated"}',
  true
);

select is(public.admin_current_staff() ->> 'role', 'editor', 'portal access resolves editor role');
select is(public.admin_current_staff() ->> 'editor_id', 'portal-editor-one', 'portal access returns the bound editor ID');
select is(public.admin_current_staff() ->> 'editor_name', 'Editor One', 'portal access returns a display label');
select throws_ok(
  $$ select * from public.admin_list_exhibitions('', null) $$,
  '42501', 'active_staff_membership_required',
  'editor membership does not grant staff query access'
);
select is(
  (select count(*)::integer from public.editor_list_pick_candidates('')),
  2,
  'editor sees only unassigned and own current working assignments'
);
select results_eq(
  $$ select value ->> 'id' from public.editor_list_pick_candidates('available') value $$,
  $$ values ('portal-available'::text) $$,
  'editor search is scoped to eligible candidates'
);
select ok(
  (select (value ->> 'live')::boolean and (value ->> 'selected')::boolean
   from public.editor_list_pick_candidates('') value
   where value ->> 'id' = 'portal-own'),
  'own published pick is marked selected and live'
);

create temp table editor_portal_state (key text primary key, payload jsonb);
grant select, insert, update on editor_portal_state to authenticated;
insert into editor_portal_state values (
  'added',
  public.editor_set_pick(
    'portal-available', '23000000-0000-0000-0000-000000000001', 2, true
  )
);

select ok(
  (select (payload ->> 'selected')::boolean and not (payload ->> 'live')::boolean
   from editor_portal_state where key = 'added'),
  'adding a pick returns a pending selected state'
);
reset role;
select isnt(
  (select payload ->> 'working_version_id' from editor_portal_state where key = 'added'),
  '23000000-0000-0000-0000-000000000001',
  'adding a published exhibition creates a distinct draft version'
);
select is(
  (select editor_id from content.exhibition_versions where id = '23000000-0000-0000-0000-000000000001'),
  null::text,
  'published editor attribution remains immutable'
);
select is(
  (select description_ko from content.exhibition_versions
   where id = (select (payload ->> 'working_version_id')::uuid from editor_portal_state where key = 'added')),
  '보존할 설명',
  'draft cloning preserves exhibition content'
);
select is(
  (select count(*)::integer from content.exhibition_version_media
   where version_id = (select (payload ->> 'working_version_id')::uuid from editor_portal_state where key = 'added')),
  1,
  'draft cloning preserves version media'
);
set local role authenticated;
select throws_ok(
  $$ select public.editor_set_pick('portal-available', '23000000-0000-0000-0000-000000000001', 2, true) $$,
  '40001', 'working_version_changed',
  'stale working identity fails closed'
);
select throws_ok(
  $$ select public.editor_set_pick('portal-other', '23000000-0000-0000-0000-000000000003', 4, false) $$,
  '42501', 'editor_pick_not_available',
  'editor cannot remove another editor assignment'
);
select throws_ok(
  $$ select public.editor_set_pick('portal-working-other', '23000000-0000-0000-0000-000000000005', 4, true) $$,
  '42501', 'editor_pick_not_available',
  'an unassigned draft cannot expose another editor live assignment'
);

insert into editor_portal_state
select 'removed', public.editor_set_pick(
  'portal-available',
  (select (payload ->> 'working_version_id')::uuid from editor_portal_state where key = 'added'),
  (select (payload ->> 'revision')::integer from editor_portal_state where key = 'added'),
  false
);
select ok(
  not (select (payload ->> 'selected')::boolean from editor_portal_state where key = 'removed'),
  'editor can cancel their own pending addition'
);

insert into editor_portal_state values (
  'live-removed',
  public.editor_set_pick(
    'portal-own', '23000000-0000-0000-0000-000000000002', 5, false
  )
);
select ok(
  not (select (payload ->> 'selected')::boolean from editor_portal_state where key = 'live-removed')
  and (select (payload ->> 'live')::boolean from editor_portal_state where key = 'live-removed'),
  'removing a live pick is represented as pending removal'
);
select is(
  (select count(*)::integer from content.audit_log
   where actor_user_id = '00000000-0000-0000-0000-000000002301'
     and action in ('editor.pick_added', 'editor.pick_removed')),
  3,
  'accepted editor changes append actor-attributed audit evidence'
);
select ok(
  (select bool_and(metadata ?& array['editor_id', 'previous_editor_id', 'selected', 'working_version_id'])
   from content.audit_log
   where actor_user_id = '00000000-0000-0000-0000-000000002301'
     and action in ('editor.pick_added', 'editor.pick_removed')),
  'editor audit evidence contains the scoped mutation context'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002304","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select * from public.editor_list_pick_candidates('') $$,
  '42501', 'active_editor_membership_required',
  'inactive editor membership is denied'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002303","role":"authenticated"}',
  true
);
select is(public.admin_current_staff() ->> 'role', 'contributor', 'staff access resolution remains unchanged');
select throws_ok(
  $$ select * from public.editor_list_pick_candidates('') $$,
  '42501', 'active_editor_membership_required',
  'staff role alone does not grant editor collection access'
);

reset role;

select * from finish();
rollback;
