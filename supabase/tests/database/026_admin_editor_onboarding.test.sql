begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(12);

select ok(
  has_function_privilege(
    'authenticated',
    'public.admin_create_editor_onboarding(uuid,text,text,text,text,text,text,text,boolean,date,date)',
    'EXECUTE'
  ),
  'authenticated callers receive the narrow onboarding command'
);
select ok(
  not has_function_privilege(
    'anon',
    'public.admin_create_editor_onboarding(uuid,text,text,text,text,text,text,text,boolean,date,date)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'public.admin_create_editor_onboarding(uuid,text,text,text,text,text,text,text,boolean,date,date)',
    'EXECUTE'
  ),
  'anon and service role cannot execute the onboarding command'
);
select ok(
  not (
    select procedure.prosecdef
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'admin_create_editor_onboarding'
      and pg_get_function_identity_arguments(procedure.oid) =
        'p_user_id uuid, p_editor_id text, p_name_ko text, p_name_en text, p_title_ko text, p_title_en text, p_bio_ko text, p_bio_en text, p_is_active boolean, p_active_from date, p_active_to date'
  ),
  'the public onboarding wrapper is SECURITY INVOKER'
);

insert into auth.users (id, email, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000002601', 'onboarding-admin@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002602', 'onboarding-contributor@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002603', 'existing-editor@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002604', 'new-editor@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002605', 'denied-target@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002606', 'invalid-target@example.invalid', '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values
  ('00000000-0000-0000-0000-000000002601', 'admin', true),
  ('00000000-0000-0000-0000-000000002602', 'contributor', true);

insert into public.editors (
  id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en,
  is_active, active_from
)
values (
  'existing-onboarding-editor', '기존 에디터', 'Existing Editor',
  '에디터', 'Editor', '소개', 'Bio', true, current_date
);
insert into content.editor_memberships (user_id, editor_id, active)
values (
  '00000000-0000-0000-0000-000000002603',
  'existing-onboarding-editor', true
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002601","role":"authenticated"}',
  true
);

create temp table onboarding_state (payload jsonb);
grant select, insert on onboarding_state to authenticated;
insert into onboarding_state values (
  public.admin_create_editor_onboarding(
    '00000000-0000-0000-0000-000000002604',
    'new-editor', '새 에디터', 'New Editor', '객원 에디터', 'Guest Editor',
    '한국어 소개', 'English bio', false, current_date, null
  )
);

select is(
  (select payload ->> 'editor_id' from onboarding_state),
  'new-editor',
  'admin command returns the created editor identity'
);
reset role;
select is(
  (select count(*)::integer from public.editors where id = 'new-editor'),
  1,
  'admin command creates one editor profile'
);
select is(
  (select editor_id from content.editor_memberships
   where user_id = '00000000-0000-0000-0000-000000002604'),
  'new-editor',
  'admin command links the invited account to the editor'
);
select is(
  (select count(*)::integer from content.audit_log
   where actor_user_id = '00000000-0000-0000-0000-000000002601'
     and action = 'editor.created'
     and entity_id = 'new-editor'),
  1,
  'admin command records actor-attributed audit evidence'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002602","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.admin_create_editor_onboarding(
    '00000000-0000-0000-0000-000000002605', 'denied-editor',
    '거부', 'Denied', '에디터', 'Editor', '소개', 'Bio', false,
    current_date, null
  ) $$,
  '42501', 'admin_role_required',
  'contributor cannot create an editor'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002603","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.admin_create_editor_onboarding(
    '00000000-0000-0000-0000-000000002605', 'editor-denied',
    '거부', 'Denied', '에디터', 'Editor', '소개', 'Bio', false,
    current_date, null
  ) $$,
  '42501', 'admin_role_required',
  'editor membership cannot create another editor'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002601","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.admin_create_editor_onboarding(
    '00000000-0000-0000-0000-000000002606', 'Bad Slug',
    '거부', 'Denied', '에디터', 'Editor', '소개', 'Bio', false,
    current_date, null
  ) $$,
  '22023', 'editor_slug_invalid',
  'invalid editor slug fails before insert'
);
select throws_ok(
  $$ select public.admin_create_editor_onboarding(
    '00000000-0000-0000-0000-000000002606', 'bad-dates',
    '거부', 'Denied', '에디터', 'Editor', '소개', 'Bio', true,
    current_date, current_date - 1
  ) $$,
  '22023', 'editor_date_range_invalid',
  'invalid editor date range fails before insert'
);
reset role;
select is(
  (select count(*)::integer from public.editors where id in ('denied-editor', 'editor-denied', 'Bad Slug', 'bad-dates')),
  0,
  'denied and invalid commands leave no editor rows'
);

select * from finish();
rollback;
