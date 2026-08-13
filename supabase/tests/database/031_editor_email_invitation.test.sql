begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(17);

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'admin_register_editor_invitation',
        'editor_complete_onboarding'
      )
      and not procedure.prosecdef
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  2,
  'public invitation RPCs are SECURITY INVOKER with an empty search path'
);
select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname in (
        'admin_register_editor_invitation_impl',
        'editor_complete_onboarding_impl'
      )
      and procedure.prosecdef
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  2,
  'private invitation implementations are pinned SECURITY DEFINER functions'
);

select ok(
  not has_table_privilege('authenticated', 'content.editor_invitations', 'SELECT')
  and not has_table_privilege('anon', 'content.editor_invitations', 'SELECT')
  and not has_table_privilege('service_role', 'content.editor_invitations', 'SELECT'),
  'pending editor invitations are not exposed through table privileges'
);
select ok(
  has_function_privilege(
    'authenticated',
    'public.admin_register_editor_invitation(uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.admin_register_editor_invitation(uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'public.admin_register_editor_invitation(uuid)',
    'EXECUTE'
  ),
  'only authenticated callers can reach the admin invitation command'
);
select ok(
  has_function_privilege(
    'authenticated',
    'public.editor_complete_onboarding(text,text,text,text,text,text,text,text,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.editor_complete_onboarding(text,text,text,text,text,text,text,text,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'public.editor_complete_onboarding(text,text,text,text,text,text,text,text,text)',
    'EXECUTE'
  ),
  'only authenticated callers can reach editor self-onboarding'
);

insert into auth.users (id, email, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000003101', 'invitation-admin@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003102', 'invitation-contributor@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003103', 'pending-editor@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003104', 'outsider@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003105', 'staff-target@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003106', 'duplicate-slug@example.invalid', '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values
  ('00000000-0000-0000-0000-000000003101', 'admin', true),
  ('00000000-0000-0000-0000-000000003102', 'contributor', true),
  ('00000000-0000-0000-0000-000000003105', 'publisher', true);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003101","role":"authenticated"}',
  true
);

select is(
  public.admin_register_editor_invitation(
    '00000000-0000-0000-0000-000000003103'
  ) ->> 'status',
  'invited',
  'an admin records the Auth invitation without creating a profile'
);
reset role;
select is(
  (select count(*)::integer from content.editor_invitations
   where user_id = '00000000-0000-0000-0000-000000003103'),
  1,
  'the pending invitation is retained server-side'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003103","role":"authenticated"}',
  true
);
select is(
  public.admin_current_staff() ->> 'role',
  'editor_onboarding',
  'a pending invited account receives only onboarding access'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003102","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.admin_register_editor_invitation(
    '00000000-0000-0000-0000-000000003106'
  ) $$,
  '42501', 'insufficient_staff_role',
  'a contributor cannot record an editor invitation'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003101","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.admin_register_editor_invitation(
    '00000000-0000-0000-0000-000000003105'
  ) $$,
  '23514', 'editor_account_conflicts_with_staff',
  'a staff account cannot become an editor invitation'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003104","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.editor_complete_onboarding(
    'outsider', '외부인', 'Outsider', '에디터', 'Editor',
    '소개', 'Bio', '큐레이션', 'Curation'
  ) $$,
  '42501', 'editor_invitation_required',
  'an uninvited account cannot create an editor identity'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003103","role":"authenticated"}',
  true
);
create temp table completed_editor (payload jsonb);
grant select, insert on completed_editor to authenticated;
insert into completed_editor values (
  public.editor_complete_onboarding(
    'mina-kim', '김미나', 'Mina Kim', '객원 에디터', 'Guest Editor',
    '개인 소개', 'Personal bio', '큐레이션 소개', 'Curation statement'
  )
);
select is(
  (select payload ->> 'editor_id' from completed_editor),
  'mina-kim',
  'the invited editor creates their own identity'
);
reset role;
select ok(
  exists (
    select 1 from public.editors
    where id = 'mina-kim' and not is_active
      and bio_ko = '개인 소개'
      and curation_description_ko = '큐레이션 소개'
  ),
  'self-onboarding creates an unpublished profile with separate copy'
);
select ok(
  exists (
    select 1 from content.editor_memberships
    where user_id = '00000000-0000-0000-0000-000000003103'
      and editor_id = 'mina-kim' and active
  )
  and not exists (
    select 1 from content.editor_invitations
    where user_id = '00000000-0000-0000-0000-000000003103'
  ),
  'completion atomically replaces the invitation with editor membership'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003103","role":"authenticated"}',
  true
);
select is(
  public.admin_current_staff() ->> 'role',
  'editor',
  'the completed account resolves as an editor'
);
reset role;
select is(
  (select count(*)::integer from content.audit_log
   where action = 'editor.onboarded' and entity_id = 'mina-kim'),
  1,
  'self-onboarding records actor-attributed audit evidence'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003101","role":"authenticated"}',
  true
);
select public.admin_register_editor_invitation(
  '00000000-0000-0000-0000-000000003106'
);
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003106","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.editor_complete_onboarding(
    'mina-kim', '중복', 'Duplicate', '에디터', 'Editor',
    '소개', 'Bio', '큐레이션', 'Curation'
  ) $$,
  '23505', null,
  'a duplicate public slug cannot consume the pending invitation'
);

select * from finish();
rollback;
