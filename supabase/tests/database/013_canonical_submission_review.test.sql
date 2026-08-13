begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(29);

select has_function(
  'public',
  'check_exhibition_submission_rate_limit',
  array['text', 'text'],
  'the pre-upload rate-limit RPC exists'
);
select ok(
  has_function_privilege(
    'service_role',
    'public.check_exhibition_submission_rate_limit(text,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.check_exhibition_submission_rate_limit(text,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.check_exhibition_submission_rate_limit(text,text)',
    'EXECUTE'
  ),
  'only the service role can preflight intake rate limits'
);
select has_function(
  'public',
  'create_exhibition_submission',
  array['uuid', 'text', 'jsonb', 'text', 'text', 'jsonb'],
  'the service-role submission intake RPC exists'
);
select ok(
  has_function_privilege(
    'service_role',
    'public.create_exhibition_submission(uuid,text,jsonb,text,text,jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.create_exhibition_submission(uuid,text,jsonb,text,text,jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.create_exhibition_submission(uuid,text,jsonb,text,text,jsonb)',
    'EXECUTE'
  ),
  'only the service role can execute submission intake'
);
select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'check_exhibition_submission_rate_limit',
        'create_exhibition_submission',
        'admin_list_exhibition_submissions',
        'admin_start_exhibition_submission_review',
        'admin_accept_exhibition_submission',
        'admin_reject_exhibition_submission'
      )
      and not procedure.prosecdef
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  6,
  'all public submission RPCs are hardened SECURITY INVOKER functions'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_list_exhibition_submissions(text,text)'),
        ('public.admin_start_exhibition_submission_review(uuid)'),
        ('public.admin_accept_exhibition_submission(uuid,uuid)'),
        ('public.admin_reject_exhibition_submission(uuid,text,uuid)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
      and not has_function_privilege('anon', signature.value, 'EXECUTE')
      and not has_function_privilege('service_role', signature.value, 'EXECUTE')
  ),
  4,
  'review commands are authenticated-only'
);

insert into auth.users (id, email, raw_user_meta_data)
values (
  '00000000-0000-0000-0000-000000000701'::uuid,
  'submission-admin@example.invalid',
  '{}'::jsonb
);
insert into auth.users (id, email, raw_user_meta_data)
values (
  '00000000-0000-0000-0000-000000000702'::uuid,
  'submission-contributor@example.invalid',
  '{}'::jsonb
);
insert into content.staff_members (user_id, role, active)
values (
  '00000000-0000-0000-0000-000000000701'::uuid,
  'admin'::content.staff_role,
  true
);
insert into content.staff_members (user_id, role, active)
values (
  '00000000-0000-0000-0000-000000000702'::uuid,
  'contributor'::content.staff_role,
  true
);

set local role service_role;
select is(
  public.create_exhibition_submission(
    '70000000-0000-0000-0000-000000000001'::uuid,
    ' Gallery@Example.com ',
    jsonb_build_object(
      'name_ko', '기억의 층위',
      'name_en', 'Layers of Memory',
      'venue_name_ko', '아트스페이스 이튼',
      'venue_name_en', 'Artspace Eaton',
      'opening_date', '2026-08-15',
      'closing_date', '2026-09-21',
      'address_ko', '서울특별시 성동구 연무장길 68',
      'address_en', '68 Yeonmujang-gil, Seongdong-gu, Seoul',
      'hours', '화–금 11:00–19:00',
      'description_ko', '제출 설명',
      'description_en', 'Submitted description',
      'reception_date', '2026-08-15T18:00',
      'reception_end', '2026-08-15T20:00',
      'ignored_key', 'must not persist'
    ),
    repeat('a', 64),
    'pgTAP agent',
    '[]'::jsonb
  ) ->> 'status',
  'submitted',
  'service-role intake creates a submitted review item'
);
reset role;

insert into content.media_assets (
  id,
  status,
  bucket_id,
  object_path,
  public_url,
  mime_type,
  byte_size,
  metadata,
  uploaded_by,
  published_at
)
values (
  '70000000-0000-0000-0000-000000000201'::uuid,
  'published'::content.media_asset_status,
  'exhibition-media',
  'submissions/70000000-0000-0000-0000-000000000001/70000000-0000-0000-0000-000000000201/original.jpg',
  'https://images.example.invalid/cms/70000000-0000-0000-0000-000000000201/original.jpg',
  'image/jpeg',
  2048,
  '{"original_filename":"installation.jpg"}'::jsonb,
  '00000000-0000-0000-0000-000000000701'::uuid,
  now()
);
insert into content.submission_media (submission_id, media_id, sort_order)
values (
  '70000000-0000-0000-0000-000000000001'::uuid,
  '70000000-0000-0000-0000-000000000201'::uuid,
  0
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000702","role":"authenticated"}',
  true
);
select throws_ok(
  $$select public.admin_start_exhibition_submission_review(
    '70000000-0000-0000-0000-000000000001'::uuid
  )$$,
  '42501',
  'insufficient_staff_role',
  'contributors cannot make submission review decisions'
);
reset role;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000701","role":"authenticated"}',
  true
);

select is(
  (
    select submitter_email
    from content.exhibition_submissions
    where id = '70000000-0000-0000-0000-000000000001'::uuid
  ),
  'gallery@example.com',
  'submitter email is normalized'
);
select ok(
  not (
    select payload ? 'ignored_key'
    from content.exhibition_submissions
    where id = '70000000-0000-0000-0000-000000000001'::uuid
  ),
  'intake whitelists durable payload fields'
);
select is(
  (
    select payload ->> 'name_ko'
    from content.exhibition_submissions
    where id = '70000000-0000-0000-0000-000000000001'::uuid
  ),
  '기억의 층위',
  'intake preserves the exhibition identity'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000701","role":"authenticated"}',
  true
);

select is(
  (
    select count(*)
    from public.admin_list_exhibition_submissions('', 'submitted')
  ),
  1::bigint,
  'staff can list the submitted queue'
);
select is(
  (
    select item ->> 'submitter_email'
    from public.admin_list_exhibition_submissions('gallery@example.com', null)
      as item
  ),
  'gallery@example.com',
  'staff search includes private submitter contact'
);
select is(
  (
    select item #>> '{media,0,public_url}'
    from public.admin_list_exhibition_submissions('', 'submitted') as item
  ),
  'https://images.example.invalid/cms/70000000-0000-0000-0000-000000000201/original.jpg',
  'staff submission DTO exposes a published media delivery URL'
);
reset role;
delete from content.submission_media
where submission_id = '70000000-0000-0000-0000-000000000001'::uuid
  and media_id = '70000000-0000-0000-0000-000000000201'::uuid;
delete from content.media_assets
where id = '70000000-0000-0000-0000-000000000201'::uuid;
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000701","role":"authenticated"}',
  true
);
select is(
  public.admin_start_exhibition_submission_review(
    '70000000-0000-0000-0000-000000000001'::uuid
  ) ->> 'status',
  'in_review',
  'staff can start review'
);
select is(
  (
    select reviewed_by
    from content.exhibition_submissions
    where id = '70000000-0000-0000-0000-000000000001'::uuid
  ),
  '00000000-0000-0000-0000-000000000701'::uuid,
  'review ownership is recorded'
);

create temporary table submission_test_state (
  payload jsonb
) on commit drop;
grant select, insert, update, delete on submission_test_state to authenticated;
insert into pg_temp.submission_test_state (payload)
values (
  public.admin_accept_exhibition_submission(
    '70000000-0000-0000-0000-000000000001'::uuid,
    '70000000-0000-0000-0000-000000000101'::uuid
  )
);

select is(
  (select payload #>> '{submission,status}' from pg_temp.submission_test_state),
  'accepted',
  'acceptance closes the submission'
);
select is(
  (select payload #>> '{exhibition,status}' from pg_temp.submission_test_state),
  'draft',
  'acceptance creates a draft only'
);
select is(
  (select payload #>> '{exhibition,name_ko}' from pg_temp.submission_test_state),
  '기억의 층위',
  'the accepted draft receives submitted exhibition fields'
);
select is(
  (select payload #>> '{exhibition,address_ko}' from pg_temp.submission_test_state),
  '서울특별시 성동구 연무장길 68',
  'the accepted draft receives the submitted address'
);
select is(
  (select payload #>> '{exhibition,contact}' from pg_temp.submission_test_state),
  '',
  'private submitter email is not copied into the public contact field'
);
select is(
  (
    select count(*)
    from content.exhibitions
    where id = (
      select payload #>> '{exhibition,id}'
      from pg_temp.submission_test_state
    )
      and published_version_id is null
  ),
  1::bigint,
  'accepted exhibition remains unpublished'
);
select is(
  public.admin_accept_exhibition_submission(
    '70000000-0000-0000-0000-000000000001'::uuid,
    '70000000-0000-0000-0000-000000000101'::uuid
  ),
  (select payload from pg_temp.submission_test_state),
  'acceptance is idempotent for the same request ID'
);
select is(
  (
    select count(*)
    from content.audit_log
    where entity_id = '70000000-0000-0000-0000-000000000001'
      and action = 'submission.accepted'
  ),
  1::bigint,
  'acceptance records one audit event'
);

reset role;
set local role service_role;
select public.create_exhibition_submission(
  '70000000-0000-0000-0000-000000000002'::uuid,
  'reject@example.com',
  jsonb_build_object(
    'name_ko', '반려 대상',
    'venue_name_ko', '테스트 갤러리',
    'opening_date', '2026-08-01',
    'closing_date', '2026-08-02',
    'address_ko', '서울특별시 종로구 테스트로 1',
    'hours', '11:00–18:00'
  ),
  repeat('b', 64),
  'pgTAP agent',
  '[]'::jsonb
);
reset role;
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000701","role":"authenticated"}',
  true
);
select is(
  public.admin_reject_exhibition_submission(
    '70000000-0000-0000-0000-000000000002'::uuid,
    '전시 정보 확인이 필요합니다.',
    '70000000-0000-0000-0000-000000000102'::uuid
  ) ->> 'status',
  'rejected',
  'staff can reject a submission'
);
select is(
  (
    select review_notes
    from content.exhibition_submissions
    where id = '70000000-0000-0000-0000-000000000002'::uuid
  ),
  '전시 정보 확인이 필요합니다.',
  'rejection stores review notes'
);
select is(
  (
    select count(*)
    from content.audit_log
    where entity_id = '70000000-0000-0000-0000-000000000002'
      and action = 'submission.rejected'
  ),
  1::bigint,
  'rejection records one audit event'
);
select throws_ok(
  $$select public.admin_reject_exhibition_submission(
    '70000000-0000-0000-0000-000000000002'::uuid,
    '',
    '70000000-0000-0000-0000-000000000103'::uuid
  )$$,
  '22023',
  'review_notes_required',
  'rejection requires an explicit reason'
);
select is(
  (
    select count(*)
    from content.exhibition_submissions
    where status in (
      'submitted'::content.submission_status,
      'in_review'::content.submission_status
    )
  ),
  0::bigint,
  'the reviewed test queue has no open items'
);

select * from finish();
rollback;
