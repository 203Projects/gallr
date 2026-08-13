begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(28);

select has_column('public', 'editors', 'curation_description_ko', 'editors stores a Korean curation statement');
select has_column('public', 'editors', 'curation_description_en', 'editors stores an English curation statement');
select ok(
  has_function_privilege(
    'authenticated',
    'public.editor_submit_curation(jsonb,text,text)',
    'EXECUTE'
  ),
  'authenticated editors receive the statement-aware curation command'
);
select ok(
  has_function_privilege(
    'authenticated',
    'public.editor_list_curation_history()',
    'EXECUTE'
  ),
  'authenticated editors receive their curation history query'
);
select ok(
  not has_function_privilege('anon', 'public.editor_list_curation_history()', 'EXECUTE')
  and not has_function_privilege('service_role', 'public.editor_list_curation_history()', 'EXECUTE'),
  'anonymous and service roles receive no curation history grant'
);
select ok(
  (
    select not procedure.prosecdef
      and procedure.proconfig @> array['search_path=""']::text[]
    from pg_proc as procedure
    where procedure.oid = 'public.editor_list_curation_history()'::regprocedure
  ),
  'public curation history wrapper is SECURITY INVOKER with an empty search path'
);
select ok(
  has_function_privilege(
    'authenticated',
    'public.admin_create_editor_onboarding(uuid,text,text,text,text,text,text,text,text,text,boolean,date,date)',
    'EXECUTE'
  ),
  'authenticated callers receive the statement-aware onboarding command'
);

insert into auth.users (id, email, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000002801', 'statement-admin@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002802', 'statement-editor@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002803', 'statement-onboard@example.invalid', '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values ('00000000-0000-0000-0000-000000002801', 'admin', true);

insert into public.editors (
  id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en,
  curation_description_ko, curation_description_en, is_active, active_from
)
values (
  'statement-editor', '문장 에디터', 'Statement Editor', '에디터', 'Editor',
  '개인 약력', 'Personal biography', '기존 큐레이션', 'Existing curation',
  true, current_date
);

insert into content.editor_memberships (user_id, editor_id, active)
values ('00000000-0000-0000-0000-000000002802', 'statement-editor', true);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002802","role":"authenticated"}', true);

select is(public.editor_get_profile() ->> 'bio_ko', '개인 약력', 'profile keeps the personal biography separate');
select is(public.editor_get_profile() ->> 'curation_description_ko', '기존 큐레이션', 'profile returns the current curation statement');

create temp table statement_state (key text primary key, payload jsonb);
grant select, insert, update on statement_state to authenticated;
insert into statement_state values (
  'submitted',
  public.editor_submit_curation('[]'::jsonb, '새 큐레이션 문장', 'New curatorial statement')
);

select is((select payload ->> 'status' from statement_state where key = 'submitted'), 'submitted', 'a statement-only curation request is accepted');
reset role;
select is((select curation_description_ko from public.editors where id = 'statement-editor'), '기존 큐레이션', 'public statement is unchanged before approval');
select is(
  (select payload ->> 'curation_description_ko' from content.editor_requests where editor_id = 'statement-editor' and status = 'submitted'),
  '새 큐레이션 문장',
  'review request preserves the exact proposed Korean statement'
);
select is(
  (select jsonb_array_length(payload -> 'changes') from content.editor_requests where editor_id = 'statement-editor' and status = 'submitted'),
  0,
  'statement-only request records an empty exhibition change set'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002802","role":"authenticated"}', true);
select is((public.editor_get_profile() ->> 'pending_curation')::boolean, true, 'profile reports the open grouped curation request');

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002801","role":"authenticated"}', true);
insert into statement_state values (
  'approved',
  public.admin_review_editor_request(
    (select (payload ->> 'request_id')::uuid from statement_state where key = 'submitted'),
    true,
    ''
  )
);
reset role;

select is((select curation_description_ko from public.editors where id = 'statement-editor'), '새 큐레이션 문장', 'approval publishes the proposed statement');
select is((select bio_ko from public.editors where id = 'statement-editor'), '개인 약력', 'approval does not overwrite the personal biography');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002802","role":"authenticated"}', true);
insert into statement_state values (
  'rejected-submission',
  public.editor_submit_curation('[]'::jsonb, '거절할 문장', 'Rejected statement')
);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002801","role":"authenticated"}', true);
insert into statement_state values (
  'rejected',
  public.admin_review_editor_request(
    (select (payload ->> 'request_id')::uuid from statement_state where key = 'rejected-submission'),
    false,
    'Revise the framing.'
  )
);
reset role;

select is((select curation_description_ko from public.editors where id = 'statement-editor'), '새 큐레이션 문장', 'rejection leaves the approved statement unchanged');
select is((select payload ->> 'status' from statement_state where key = 'rejected'), 'rejected', 'admin rejection closes the request');
update content.editor_requests
set created_at = created_at + interval '1 second'
where id = (
  select (payload ->> 'request_id')::uuid
  from statement_state
  where key = 'rejected-submission'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002802","role":"authenticated"}', true);
select is(
  (select count(*)::integer from public.editor_list_curation_history()),
  2,
  'editor history includes every own curation submission'
);
select is(
  (select value ->> 'status' from public.editor_list_curation_history() value limit 1),
  'rejected',
  'editor history is newest first and exposes review status'
);
select is(
  (select value ->> 'review_notes' from public.editor_list_curation_history() value limit 1),
  'Revise the framing.',
  'editor history exposes the reviewer note'
);
select is(
  (select value ->> 'curation_description_ko' from public.editor_list_curation_history() value offset 1 limit 1),
  '새 큐레이션 문장',
  'editor history preserves the submitted statement snapshot'
);
select is(
  (select jsonb_array_length(value -> 'changes') from public.editor_list_curation_history() value limit 1),
  0,
  'statement-only history entries expose an empty exhibition change list'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002802","role":"authenticated"}', true);
select throws_ok(
  $$ select public.admin_create_editor_onboarding(
    '00000000-0000-0000-0000-000000002803', 'denied-statement-editor',
    '거부', 'Denied', '에디터', 'Editor', '개인 소개', 'Personal bio',
    '큐레이션 문장', 'Curation statement', false, current_date, null
  ) $$,
  '42501', 'admin_role_required',
  'an editor cannot onboard another editor with a statement'
);

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002801","role":"authenticated"}', true);
insert into statement_state values (
  'onboarded',
  public.admin_create_editor_onboarding(
    '00000000-0000-0000-0000-000000002803', 'onboarded-statement-editor',
    '새 에디터', 'New Editor', '객원 에디터', 'Guest Editor',
    '서로 다른 개인 소개', 'A distinct biography',
    '서로 다른 큐레이션 문장', 'A distinct curation statement',
    false, current_date, null
  )
);
reset role;

select is((select bio_ko from public.editors where id = 'onboarded-statement-editor'), '서로 다른 개인 소개', 'onboarding persists the personal biography');
select is((select curation_description_ko from public.editors where id = 'onboarded-statement-editor'), '서로 다른 큐레이션 문장', 'onboarding persists the distinct curation statement');
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002803","role":"authenticated"}', true);
select is(
  (select count(*)::integer from public.editor_list_curation_history()),
  0,
  'editor history cannot expose another editor submissions'
);
reset role;
select is(
  (select count(*)::integer from content.audit_log
   where action = 'editor.curation_submitted'
     and metadata ->> 'editor_id' = 'statement-editor'),
  2,
  'statement submissions retain actor-attributed audit evidence'
);

select * from finish();
rollback;
