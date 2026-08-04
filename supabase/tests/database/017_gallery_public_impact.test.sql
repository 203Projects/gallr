begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(19);

select has_table(
  'content',
  'exhibition_daily_metrics',
  'daily aggregate table exists'
);
select has_column(
  'content',
  'exhibition_daily_metrics',
  'page_loads',
  'aggregate stores page loads'
);
select is(
  (
    select relation.relrowsecurity
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname = 'exhibition_daily_metrics'
  ),
  true,
  'aggregate table has RLS enabled'
);
select is(
  (
    select count(*)::integer
    from (values ('anon'), ('authenticated')) as caller(role_name)
    where has_table_privilege(caller.role_name, 'content.exhibition_daily_metrics', 'SELECT')
       or has_table_privilege(caller.role_name, 'content.exhibition_daily_metrics', 'INSERT')
       or has_table_privilege(caller.role_name, 'content.exhibition_daily_metrics', 'UPDATE')
  ),
  0,
  'browser and owner roles receive no aggregate table privileges'
);
select ok(
  has_function_privilege(
    'service_role',
    'public.record_exhibition_page_load(text)',
    'EXECUTE'
  ),
  'service role can call the narrow recorder'
);
select is(
  (
    select count(*)::integer
    from (values ('anon'), ('authenticated')) as caller(role_name)
    where has_function_privilege(
      caller.role_name,
      'public.record_exhibition_page_load(text)',
      'EXECUTE'
    )
  ),
  0,
  'browser and owner roles cannot call the recorder directly'
);
select is(
  (
    select procedure.prosecdef
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'record_exhibition_page_load'
  ),
  false,
  'public recorder wrapper is security invoker'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'content_private.owner_exhibition_json(text,uuid)',
    'EXECUTE'
  ),
  'owners cannot bypass gallery scope through the internal exhibition serializer'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000001001', 'impact-owner@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000001002', 'other-impact-owner@example.invalid', now(), '{}'::jsonb);

insert into content.galleries (id, name_ko, status)
values
  ('a1000000-0000-0000-0000-000000000001', '임팩트 갤러리', 'active'),
  ('a1000000-0000-0000-0000-000000000002', '다른 갤러리', 'active');

insert into content.gallery_memberships (
  gallery_id, user_id, status, claim_note
)
values
  (
    'a1000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000001001',
    'active',
    'test ownership'
  ),
  (
    'a1000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000001002',
    'active',
    'test ownership'
  );

insert into content.exhibitions (id, gallery_id, owner_status)
values
  ('impact-published', 'a1000000-0000-0000-0000-000000000001', 'published'),
  ('impact-draft', 'a1000000-0000-0000-0000-000000000001', 'draft'),
  ('impact-other', 'a1000000-0000-0000-0000-000000000002', 'published');

insert into content.exhibition_versions (
  id, exhibition_id, version_number, status, name_ko, venue_name_ko,
  city_ko, region_ko, address_ko, opening_date, closing_date, hours,
  published_at
)
values
  (
    'a2000000-0000-0000-0000-000000000001', 'impact-published', 1,
    'published', '공개 전시', '임팩트 갤러리', '서울', '종로구', '서울 종로구',
    current_date, current_date + 30, '11:00-18:00', now()
  ),
  (
    'a2000000-0000-0000-0000-000000000002', 'impact-draft', 1,
    'draft', '초안 전시', '임팩트 갤러리', '서울', '종로구', '서울 종로구',
    current_date, current_date + 30, '11:00-18:00', null
  ),
  (
    'a2000000-0000-0000-0000-000000000003', 'impact-other', 1,
    'published', '다른 전시', '다른 갤러리', '서울', '용산구', '서울 용산구',
    current_date, current_date + 30, '11:00-18:00', now()
  );

update content.exhibitions
set published_version_id = case id
  when 'impact-published' then 'a2000000-0000-0000-0000-000000000001'::uuid
  when 'impact-other' then 'a2000000-0000-0000-0000-000000000003'::uuid
end
where id in ('impact-published', 'impact-other');

set local role service_role;
select is(
  public.record_exhibition_page_load(' impact-published '),
  false,
  'non-canonical IDs are rejected'
);
select is(
  public.record_exhibition_page_load('impact-draft'),
  false,
  'draft exhibitions are not counted'
);
select is(
  public.record_exhibition_page_load('impact-published'),
  true,
  'published exhibition is counted'
);
select is(
  public.record_exhibition_page_load('impact-published'),
  true,
  'repeated loads increment the same day'
);
reset role;

select is(
  (
    select page_loads
    from content.exhibition_daily_metrics
    where exhibition_id = 'impact-published'
      and metric_date = (now() at time zone 'UTC')::date
  ),
  2::bigint,
  'same-day loads are atomically aggregated'
);
select is(
  (
    select count(*)::integer
    from content.exhibition_daily_metrics
    where exhibition_id = 'impact-draft'
  ),
  0,
  'rejected records leave no aggregate row'
);

update content.exhibitions
set archived_at = now()
where id = 'impact-published';
set local role service_role;
select is(
  public.record_exhibition_page_load('impact-published'),
  false,
  'archived exhibitions are not counted'
);
reset role;
update content.exhibitions set archived_at = null where id = 'impact-published';

insert into content.exhibition_daily_metrics (
  exhibition_id, metric_date, page_loads
)
values
  ('impact-published', (now() at time zone 'UTC')::date - 40, 5),
  ('impact-other', (now() at time zone 'UTC')::date, 99);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000001001","role":"authenticated"}',
  true
);
select is(
  (select count(*)::integer from public.owner_list_exhibitions()),
  2,
  'owner list remains gallery-scoped despite another gallery metric'
);
select is(
  (
    select (payload ->> 'page_loads_30d')::bigint
    from public.owner_list_exhibitions() as payload
    where payload ->> 'id' = 'impact-published'
  ),
  2::bigint,
  'owner sees the rolling 30-day page loads'
);
select is(
  (
    select (payload ->> 'page_loads_all_time')::bigint
    from public.owner_list_exhibitions() as payload
    where payload ->> 'id' = 'impact-published'
  ),
  7::bigint,
  'owner sees all-time page loads including older days'
);
select is(
  (
    select (payload ->> 'page_loads_all_time')::bigint
    from public.owner_list_exhibitions() as payload
    where payload ->> 'id' = 'impact-draft'
  ),
  0::bigint,
  'unpublished owner records expose zero impact'
);
reset role;

select * from finish();
rollback;
