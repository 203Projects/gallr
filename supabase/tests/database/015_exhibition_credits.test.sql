begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(16);

select has_column(
  'content', 'exhibition_versions', 'credits_ko',
  'canonical versions store Korean credits'
);
select has_column(
  'content', 'exhibition_versions', 'credits_en',
  'canonical versions store English credits'
);
select has_column(
  'public', 'exhibition_catalog_v2', 'credits_ko',
  'canonical public catalog exposes Korean credits'
);
select has_column(
  'public', 'exhibition_catalog_v2', 'credits_en',
  'canonical public catalog exposes English credits'
);
select has_column(
  'public', 'exhibitions', 'credits_ko',
  'legacy rollback reader remains compatible with Korean credits'
);
select has_column(
  'public', 'exhibitions', 'credits_en',
  'legacy rollback reader remains compatible with English credits'
);

select col_not_null(
  'content', 'exhibition_versions', 'credits_ko',
  'Korean version credits never return null'
);
select col_not_null(
  'content', 'exhibition_versions', 'credits_en',
  'English version credits never return null'
);

select has_function(
  'content_private',
  'admin_validate_patch_without_credits',
  array['jsonb'],
  'the established admin validator is preserved'
);

select ok(
  position(
    '''credits_ko'''
    in pg_get_functiondef(
      'content_private.admin_exhibition_json(text,uuid)'::regprocedure
    )
  ) > 0
    and position(
      '''credits_en'''
      in pg_get_functiondef(
        'content_private.admin_exhibition_json(text,uuid)'::regprocedure
      )
    ) > 0,
  'admin exhibition JSON includes both credit fields'
);

select ok(
  not (
    select procedure.prosecdef
    from pg_proc as procedure
    where procedure.oid =
      'public.admin_save_exhibition_draft(text,uuid,integer,jsonb)'::regprocedure
  ),
  'public draft-save wrapper remains security invoker'
);

select ok(
  has_table_privilege('anon', 'public.exhibition_catalog_v2', 'SELECT')
    and has_table_privilege(
      'authenticated',
      'public.exhibition_catalog_v2',
      'SELECT'
    ),
  'existing public catalog readers can select the new columns'
);

insert into auth.users (id, email, raw_user_meta_data)
values (
  '00000000-0000-0000-0000-000000001501'::uuid,
  'exhibition-credits-contributor@example.invalid',
  '{}'::jsonb
);

insert into content.staff_members (user_id, role, active)
values (
  '00000000-0000-0000-0000-000000001501'::uuid,
  'contributor'::content.staff_role,
  true
);

create temporary table exhibition_credits_test_state (
  payload jsonb not null
) on commit drop;
grant select, insert, update, delete on exhibition_credits_test_state
  to authenticated;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000001501","role":"authenticated"}',
  true
);

insert into pg_temp.exhibition_credits_test_state (payload)
values (public.admin_create_exhibition_draft());

select ok(
  (
    select payload @> '{"credits_ko":"","credits_en":""}'::jsonb
    from pg_temp.exhibition_credits_test_state
  ),
  'new admin drafts expose both credit fields'
);

update pg_temp.exhibition_credits_test_state
set payload = public.admin_save_exhibition_draft(
  payload ->> 'id',
  (payload ->> 'working_version_id')::uuid,
  (payload ->> 'revision')::integer,
  jsonb_build_object(
    'credits_ko', '자료 제공: 테스트 갤러리',
    'credits_en', 'Courtesy of Test Gallery',
    'hours', E'화–토 10:00–18:00\n일·월 휴관'
  )
);

select ok(
  (
    select payload @> jsonb_build_object(
      'credits_ko', '자료 제공: 테스트 갤러리',
      'credits_en', 'Courtesy of Test Gallery',
      'hours', E'화–토 10:00–18:00\n일·월 휴관'
    )
    from pg_temp.exhibition_credits_test_state
  ),
  'draft save returns credits and multiline hours without transformation'
);

select throws_ok(
  format(
    'select public.admin_save_exhibition_draft(%L, %L::uuid, 2, %L::jsonb)',
    (
      select payload ->> 'id'
      from pg_temp.exhibition_credits_test_state
    ),
    (
      select payload ->> 'working_version_id'
      from pg_temp.exhibition_credits_test_state
    ),
    '{"credits_ko":42}'
  ),
  '22023',
  'patch_field_has_invalid_type',
  'credit fields reject non-string values'
);

reset role;

select ok(
  (
    select version.credits_ko = '자료 제공: 테스트 갤러리'
      and version.credits_en = 'Courtesy of Test Gallery'
      and version.hours = E'화–토 10:00–18:00\n일·월 휴관'
    from content.exhibition_versions as version
    where version.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.exhibition_credits_test_state
    )
  ),
  'canonical draft stores the exact credits and multiline hours'
);

select * from finish();
rollback;
