begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(25);

select ok(
  has_function_privilege('authenticated', 'public.admin_list_editors()', 'EXECUTE')
  and has_function_privilege(
    'authenticated',
    'public.admin_update_editor(text,integer,text,text,text,text,text,text,text,text,boolean,date,date)',
    'EXECUTE'
  )
  and has_function_privilege(
    'authenticated',
    'public.admin_set_editor_access(text,integer,boolean)',
    'EXECUTE'
  ),
  'authenticated callers receive the narrow editor management commands'
);

select ok(
  not has_function_privilege('anon', 'public.admin_list_editors()', 'EXECUTE')
  and not has_function_privilege('service_role', 'public.admin_list_editors()', 'EXECUTE'),
  'anon and service role cannot execute editor management commands'
);

select ok(
  not (
    select procedure.prosecdef
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'admin_update_editor'
  ),
  'the public editor update wrapper is SECURITY INVOKER'
);

insert into auth.users (id, email, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000003001', 'manage-admin@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003002', 'manage-contributor@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003003', 'managed-editor@example.invalid', '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values
  ('00000000-0000-0000-0000-000000003001', 'admin', true),
  ('00000000-0000-0000-0000-000000003002', 'contributor', true);

insert into public.editors (
  id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en,
  curation_description_ko, curation_description_en,
  is_active, active_from, active_to
)
values (
  'managed-editor', '관리 에디터', 'Managed Editor', '객원 에디터', 'Guest Editor',
  '개인 소개', 'Personal bio', '큐레이션 소개', 'Curation statement',
  true, '2026-08-01', null
);

insert into content.editor_memberships (user_id, editor_id, active)
values ('00000000-0000-0000-0000-000000003003', 'managed-editor', true);

insert into content.editor_requests (editor_id, requested_by, kind, payload)
values (
  'managed-editor',
  '00000000-0000-0000-0000-000000003003',
  'profile',
  '{"bio_ko":"보존할 요청"}'::jsonb
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003001","role":"authenticated"}',
  true
);

create temp table editor_management_state (key text primary key, payload jsonb);
grant select, insert, update on editor_management_state to authenticated;

insert into editor_management_state
select 'listed', value
from public.admin_list_editors() as value
where value ->> 'editor_id' = 'managed-editor';

select is(
  (select payload ->> 'email' from editor_management_state where key = 'listed'),
  'managed-editor@example.invalid',
  'admin list returns the linked account email'
);
select is(
  (select (payload ->> 'revision')::integer from editor_management_state where key = 'listed'),
  1,
  'existing editors begin with revision one'
);
select is(
  (select payload ->> 'access_active' from editor_management_state where key = 'listed'),
  'true',
  'admin list returns active workspace access'
);

insert into editor_management_state values (
  'updated',
  public.admin_update_editor(
    'managed-editor', 1,
    '  수정 에디터  ', 'Updated Editor', '수석 에디터', 'Senior Editor',
    '수정 소개', 'Updated bio', '수정 큐레이션', 'Updated curation',
    true, '2026-08-02', '2026-12-31'
  )
);

reset role;

select is(
  (select name_ko from public.editors where id = 'managed-editor'),
  '수정 에디터',
  'admin update normalizes and persists profile fields'
);
select is(
  (select revision from public.editors where id = 'managed-editor'),
  2,
  'admin update increments the editor revision'
);
select is(
  (select (payload ->> 'revision')::integer from editor_management_state where key = 'updated'),
  2,
  'admin update returns the new revision'
);
select is(
  (select count(*)::integer from content.audit_log
   where action = 'editor.updated' and entity_id = 'managed-editor'),
  1,
  'admin update records an audit event'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003001","role":"authenticated"}',
  true
);

select throws_ok(
  $$ select public.admin_update_editor(
    'managed-editor', 1,
    '충돌', 'Conflict', '에디터', 'Editor', '소개', 'Bio', '큐레이션', 'Curation',
    true, '2026-08-02', null
  ) $$,
  '40001', 'revision_conflict',
  'stale editor updates fail closed'
);
select throws_ok(
  $$ select public.admin_update_editor(
    'managed-editor', 2,
    '날짜', 'Dates', '에디터', 'Editor', '소개', 'Bio', '큐레이션', 'Curation',
    true, '2026-08-03', '2026-08-02'
  ) $$,
  '22023', 'editor_date_range_invalid',
  'invalid editor schedules fail before mutation'
);

insert into editor_management_state values (
  'deactivated',
  public.admin_set_editor_access('managed-editor', 2, false)
);

reset role;

select is(
  (select active from content.editor_memberships where editor_id = 'managed-editor'),
  false,
  'deactivation removes editor workspace access'
);
select is(
  (select is_active from public.editors where id = 'managed-editor'),
  false,
  'deactivation hides the public editor profile'
);
select is(
  (select revision from public.editors where id = 'managed-editor'),
  3,
  'deactivation increments the editor revision'
);
select is(
  (select count(*)::integer from public.editors where id = 'managed-editor'),
  1,
  'deactivation preserves the editor identity'
);
select is(
  (select count(*)::integer from content.editor_requests where editor_id = 'managed-editor'),
  1,
  'deactivation preserves pending editor requests'
);
select is(
  (select count(*)::integer from content.audit_log
   where action = 'editor.access_deactivated' and entity_id = 'managed-editor'),
  1,
  'deactivation records an audit event'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003001","role":"authenticated"}',
  true
);

insert into editor_management_state values (
  'restored',
  public.admin_set_editor_access('managed-editor', 3, true)
);

reset role;
select is(
  (select active from content.editor_memberships where editor_id = 'managed-editor'),
  true,
  'restore returns editor workspace access'
);
select is(
  (select is_active from public.editors where id = 'managed-editor'),
  false,
  'restore does not implicitly republish the profile'
);
select is(
  (select count(*)::integer from content.audit_log
   where action = 'editor.access_restored' and entity_id = 'managed-editor'),
  1,
  'restore records an audit event'
);

update public.editors
set bio_en = 'A separately approved bio'
where id = 'managed-editor';
select is(
  (select revision from public.editors where id = 'managed-editor'),
  5,
  'editor changes outside the management RPC also invalidate stale revisions'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003002","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.admin_list_editors() $$,
  '42501', 'insufficient_staff_role',
  'contributors cannot list managed editors'
);
select throws_ok(
  $$ select public.admin_set_editor_access('managed-editor', 4, false) $$,
  '42501', 'insufficient_staff_role',
  'contributors cannot change editor access'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003003","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.admin_update_editor(
    'managed-editor', 4,
    '거부', 'Denied', '에디터', 'Editor', '소개', 'Bio', '큐레이션', 'Curation',
    false, current_date, null
  ) $$,
  '42501', 'active_staff_membership_required',
  'editors cannot update their admin-managed profile directly'
);

select * from finish();
rollback;
