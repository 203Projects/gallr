begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(19);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.editor_get_profile()'),
        ('public.editor_submit_profile(text,text)'),
        ('public.editor_submit_curation(jsonb)'),
        ('public.editor_submit_exhibition(jsonb)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  4,
  'authenticated receives the scoped editor workflow RPCs'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_list_editor_requests(text)'),
        ('public.admin_review_editor_request(uuid,boolean,text)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  2,
  'authenticated receives admin review wrappers with server-side authorization'
);

insert into auth.users (id, email, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000002701', 'curation-admin@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002702', 'curation-editor@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002703', 'curation-other@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002704', 'curation-contributor@example.invalid', '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values
  ('00000000-0000-0000-0000-000000002701', 'admin', true),
  ('00000000-0000-0000-0000-000000002704', 'contributor', true);

insert into public.editors (
  id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en,
  is_active, active_from
)
values
  ('curation-editor', '큐레이션 에디터', 'Curation Editor', '에디터', 'Editor', '기존 소개', 'Old bio', true, current_date),
  ('curation-other', '다른 에디터', 'Other Editor', '에디터', 'Editor', '소개', 'Bio', true, current_date);

insert into content.editor_memberships (user_id, editor_id, active)
values
  ('00000000-0000-0000-0000-000000002702', 'curation-editor', true),
  ('00000000-0000-0000-0000-000000002703', 'curation-other', true);

insert into content.exhibitions (id, created_by, updated_by)
values
  ('curation-ongoing', '00000000-0000-0000-0000-000000002701', '00000000-0000-0000-0000-000000002701'),
  ('curation-upcoming', '00000000-0000-0000-0000-000000002701', '00000000-0000-0000-0000-000000002701'),
  ('curation-closed', '00000000-0000-0000-0000-000000002701', '00000000-0000-0000-0000-000000002701');

insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status, editor_id,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  opening_date, closing_date, latitude, longitude, description_ko,
  published_at, published_by, created_by, updated_by
)
values
  ('27000000-0000-0000-0000-000000000001', 'curation-ongoing', 1, 2, 'published', null,
   '진행 중 전시', 'Ongoing', '갤러리', 'Gallery', '서울', 'Seoul', '종로구', 'Jongno-gu', '서울 종로구', '',
   current_date - 5, current_date + 5, 37.57, 126.98, '설명', now(), '00000000-0000-0000-0000-000000002701', '00000000-0000-0000-0000-000000002701', '00000000-0000-0000-0000-000000002701'),
  ('27000000-0000-0000-0000-000000000002', 'curation-upcoming', 1, 2, 'published', null,
   '예정 전시', 'Upcoming', '갤러리', 'Gallery', '서울', 'Seoul', '종로구', 'Jongno-gu', '서울 종로구', '',
   current_date + 1, current_date + 10, 37.57, 126.98, '설명', now(), '00000000-0000-0000-0000-000000002701', '00000000-0000-0000-0000-000000002701', '00000000-0000-0000-0000-000000002701'),
  ('27000000-0000-0000-0000-000000000003', 'curation-closed', 1, 2, 'published', null,
   '종료 전시', 'Closed', '갤러리', 'Gallery', '서울', 'Seoul', '종로구', 'Jongno-gu', '서울 종로구', '',
   current_date - 10, current_date - 1, 37.57, 126.98, '설명', now(), '00000000-0000-0000-0000-000000002701', '00000000-0000-0000-0000-000000002701', '00000000-0000-0000-0000-000000002701');

update content.exhibitions
set published_version_id = case id
  when 'curation-ongoing' then '27000000-0000-0000-0000-000000000001'::uuid
  when 'curation-upcoming' then '27000000-0000-0000-0000-000000000002'::uuid
  when 'curation-closed' then '27000000-0000-0000-0000-000000000003'::uuid
end;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002702","role":"authenticated"}', true);

select is(public.editor_get_profile() ->> 'editor_id', 'curation-editor', 'profile identity comes from membership');
select is(public.editor_get_profile() ->> 'bio_ko', '기존 소개', 'profile returns the current bio');
select results_eq(
  $$ select value ->> 'id' from public.editor_list_pick_candidates('') value $$,
  $$ values ('curation-ongoing'::text) $$,
  'candidate list contains ongoing exhibitions only'
);

create temp table editor_workflow_state (key text primary key, payload jsonb);
grant select, insert, update on editor_workflow_state to authenticated;
insert into editor_workflow_state values (
  'profile', public.editor_submit_profile('새로운 소개', 'New bio')
);
select is(
  (select payload ->> 'status' from editor_workflow_state where key = 'profile'),
  'submitted',
  'bio edit becomes a submitted request'
);
reset role;
select is((select bio_ko from public.editors where id = 'curation-editor'), '기존 소개', 'bio is unchanged before approval');
select is((select count(*)::integer from content.editor_requests where editor_id = 'curation-editor' and kind = 'profile' and status = 'submitted'), 1, 'profile request is stored once');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002704","role":"authenticated"}', true);
select throws_ok(
  $$ select public.admin_review_editor_request(
    (select (payload ->> 'request_id')::uuid from editor_workflow_state where key = 'profile'), true, ''
  ) $$,
  '42501', 'insufficient_staff_role',
  'contributor cannot review editor requests'
);

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002701","role":"authenticated"}', true);
select is((select count(*)::integer from public.admin_list_editor_requests('submitted')), 1, 'admin sees submitted profile request');
insert into editor_workflow_state values (
  'profile-approved', public.admin_review_editor_request(
    (select (payload ->> 'request_id')::uuid from editor_workflow_state where key = 'profile'), true, ''
  )
);
reset role;
select is((select bio_ko from public.editors where id = 'curation-editor'), '새로운 소개', 'admin approval applies submitted bio');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002702","role":"authenticated"}', true);
insert into editor_workflow_state values (
  'curation', public.editor_submit_curation(jsonb_build_array(jsonb_build_object(
    'exhibition_id', 'curation-ongoing',
    'expected_version_id', '27000000-0000-0000-0000-000000000001',
    'expected_revision', 2,
    'selected', true
  )))
);
select is((select payload ->> 'kind' from editor_workflow_state where key = 'curation'), 'curation', 'curation changes become one grouped request');
reset role;
select is((select editor_id from content.exhibition_versions where id = '27000000-0000-0000-0000-000000000001'), null::text, 'published attribution remains unchanged before approval');
select is((select count(*)::integer from content.editor_requests where editor_id = 'curation-editor' and kind = 'curation' and status = 'submitted'), 1, 'curation review request is stored');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002701","role":"authenticated"}', true);
insert into editor_workflow_state values (
  'curation-approved', public.admin_review_editor_request(
    (select (payload ->> 'request_id')::uuid from editor_workflow_state where key = 'curation'), true, ''
  )
);
reset role;
select is(
  (select editor_id from content.exhibition_versions
   where id = (select published_version_id from content.exhibitions where id = 'curation-ongoing')),
  'curation-editor',
  'admin approval publishes the submitted curation attribution'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002702","role":"authenticated"}', true);
insert into editor_workflow_state values (
  'exhibition', public.editor_submit_exhibition(jsonb_build_object(
    'name_ko', '누락 전시', 'name_en', 'Missing Exhibition',
    'venue_name_ko', '새 갤러리', 'venue_name_en', 'New Gallery',
    'opening_date', to_char(current_date - 1, 'YYYY-MM-DD'),
    'closing_date', to_char(current_date + 20, 'YYYY-MM-DD'),
    'address_ko', '서울 용산구', 'address_en', '', 'hours', '10:00–18:00',
    'description_ko', '전시 소개', 'description_en', 'Description'
  ))
);
reset role;
select is(
  (select source from content.exhibition_submissions
   where id = (select (payload ->> 'submission_id')::uuid from editor_workflow_state where key = 'exhibition')),
  'editor_workspace',
  'missing exhibition enters the canonical queue as editor_workspace'
);
select is(
  (select payload ->> 'editor_id' from content.exhibition_submissions
   where id = (select (payload ->> 'submission_id')::uuid from editor_workflow_state where key = 'exhibition')),
  'curation-editor',
  'missing exhibition submission is bound to the authenticated editor'
);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000002701","role":"authenticated"}', true);
insert into editor_workflow_state values (
  'exhibition-approved', public.admin_accept_exhibition_submission(
    (select (payload ->> 'submission_id')::uuid from editor_workflow_state where key = 'exhibition'),
    '27000000-0000-0000-0000-000000000099'
  )
);
reset role;
select is(
  (select editor_id from content.exhibition_versions
   where exhibition_id = (select payload -> 'exhibition' ->> 'id' from editor_workflow_state where key = 'exhibition-approved')
     and status = 'draft'),
  'curation-editor',
  'accepting an editor suggestion creates an attributed canonical draft'
);
select is(
  (select count(*)::integer from content.audit_log
   where actor_user_id in ('00000000-0000-0000-0000-000000002701', '00000000-0000-0000-0000-000000002702')
     and action in ('editor.profile_submitted', 'editor.curation_submitted', 'editor.exhibition_submitted', 'editor.request_accepted', 'editor.pick_added')),
  6,
  'editor submissions and admin decisions emit actor-attributed audit evidence'
);

select * from finish();
rollback;
