begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(13);

select has_function(
  'content_private', 'queue_owner_exhibition_published', array[]::text[],
  'the publication email trigger function exists'
);
select has_trigger(
  'content', 'exhibition_versions', 'exhibition_versions_owner_published_outbox',
  'publishing a version notifies the gallery owner'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    cross join lateral pg_catalog.aclexplode(
      coalesce(procedure.proacl, pg_catalog.acldefault('f', procedure.proowner))
    ) as privilege
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname = 'queue_owner_exhibition_published'
      and privilege.privilege_type = 'EXECUTE'
      and (
        privilege.grantee = 0
        or privilege.grantee in (
          select oid from pg_catalog.pg_roles
          where rolname in ('anon', 'authenticated', 'service_role')
        )
      )
  ),
  'the trigger function exposes no client or service-role surface'
);

select ok(
  (
    select procedure.prosecdef
      and procedure.proconfig = array['search_path=""']::text[]
    from pg_catalog.pg_proc as procedure
    where procedure.oid =
      'content_private.queue_owner_exhibition_published()'::regprocedure
  ),
  'the trigger function is security definer with a pinned empty search path'
);

-- Fixtures ----------------------------------------------------------------------

insert into auth.users (id, email, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000004200', 'staff@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004201', 'Owner.One@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004202', 'former@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004203', 'pending@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004204', null, '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values ('00000000-0000-0000-0000-000000004200', 'admin', true);

-- The schema allows one active owner per gallery; pending and revoked
-- memberships never receive the publication email.
insert into content.galleries (id, name_ko, name_en, status, created_by, updated_by)
values
  ('42100000-0000-0000-0000-000000000001', '스페이스 원', 'Space One', 'active',
   '00000000-0000-0000-0000-000000004201', '00000000-0000-0000-0000-000000004201'),
  ('42100000-0000-0000-0000-000000000002', '스페이스 투', 'Space Two', 'active',
   '00000000-0000-0000-0000-000000004204', '00000000-0000-0000-0000-000000004204');

insert into content.gallery_memberships (
  gallery_id, user_id, status, claim_note, created_by, updated_by
)
values
  ('42100000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000004201',
   'active', 'first owner',
   '00000000-0000-0000-0000-000000004201', '00000000-0000-0000-0000-000000004201'),
  ('42100000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000004202',
   'revoked', 'former owner',
   '00000000-0000-0000-0000-000000004202', '00000000-0000-0000-0000-000000004202'),
  ('42100000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000004203',
   'pending', 'still pending',
   '00000000-0000-0000-0000-000000004203', '00000000-0000-0000-0000-000000004203'),
  ('42100000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000004204',
   'active', 'no email',
   '00000000-0000-0000-0000-000000004204', '00000000-0000-0000-0000-000000004204');

insert into content.exhibitions (
  id, gallery_id, owner_status, owner_status_changed_at, created_by, updated_by
)
values
  ('published-email', '42100000-0000-0000-0000-000000000001', 'submitted', now(),
   '00000000-0000-0000-0000-000000004201', '00000000-0000-0000-0000-000000004201'),
  ('staff-only', null, null, null,
   '00000000-0000-0000-0000-000000004200', '00000000-0000-0000-0000-000000004200'),
  ('no-audience', '42100000-0000-0000-0000-000000000002', 'submitted', now(),
   '00000000-0000-0000-0000-000000004204', '00000000-0000-0000-0000-000000004204'),
  ('suppressed', '42100000-0000-0000-0000-000000000001', 'submitted', now(),
   '00000000-0000-0000-0000-000000004201', '00000000-0000-0000-0000-000000004201');

insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours,
  created_by, updated_by
)
select
  version_id, exhibition_id, 1, 1, 'draft',
  '작은 방의 기록', name_en, '장소', 'Venue', '서울', 'Seoul',
  '종로구', 'Jongno-gu', '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-09-30',
  'Daily', '00000000-0000-0000-0000-000000004200', '00000000-0000-0000-0000-000000004200'
from (
  values
    ('42200000-0000-0000-0000-000000000001'::uuid, 'published-email', 'Notes from a Small Room'),
    ('42200000-0000-0000-0000-000000000002'::uuid, 'staff-only', 'Staff Show'),
    ('42200000-0000-0000-0000-000000000004'::uuid, 'no-audience', 'Nobody Home'),
    ('42200000-0000-0000-0000-000000000005'::uuid, 'suppressed', 'Quiet Import')
) as fixture(version_id, exhibition_id, name_en);

delete from content.outbox_events where event_type = 'owner_exhibition.published';

-- Publication through the real staff command --------------------------------------

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000004200","role":"authenticated"}',
  true
);
select lives_ok(
  $$ select public.admin_publish_exhibition(
    'published-email', '42200000-0000-0000-0000-000000000001'::uuid, 1,
    '42900000-0000-0000-0000-000000000001'::uuid
  ) $$,
  'staff publish the owner exhibition through the command API'
);
reset role;

select is(
  (select owner_status::text from content.exhibitions where id = 'published-email'),
  'published',
  'the existing publication sync still flips the owner status'
);

select is(
  (
    select payload
    from content.outbox_events
    where event_type = 'owner_exhibition.published'
      and aggregate_id = 'published-email'
  ),
  jsonb_build_object(
    'source', 'owner_workspace',
    'recipient_emails', jsonb_build_array('owner.one@example.invalid'),
    'exhibition_id', 'published-email',
    'exhibition_name_en', 'Notes from a Small Room',
    'exhibition_name_ko', '작은 방의 기록'
  ),
  'the first publication emails the active owner and skips pending and revoked members'
);

select is(
  (
    select aggregate_type || '|' || max_attempts::text || '|' || deduplication_key
    from content.outbox_events
    where event_type = 'owner_exhibition.published'
      and aggregate_id = 'published-email'
  ),
  'exhibition|12|owner_exhibition:published-email:published',
  'the publication event is keyed once per exhibition with the longer retry budget'
);

-- Republication after an edit does not email again ----------------------------

insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours,
  created_by, updated_by
)
values
  ('42200000-0000-0000-0000-000000000003', 'published-email', 2, 1, 'draft',
   '작은 방의 기록', 'Notes from a Small Room (edited)', '장소', 'Venue', '서울', 'Seoul',
   '종로구', 'Jongno-gu', '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-09-30',
   'Daily', '00000000-0000-0000-0000-000000004200', '00000000-0000-0000-0000-000000004200');
delete from content.outbox_events where event_type = 'owner_exhibition.published';

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000004200","role":"authenticated"}',
  true
);
select lives_ok(
  $$ select public.admin_publish_exhibition(
    'published-email', '42200000-0000-0000-0000-000000000003'::uuid, 1,
    '42900000-0000-0000-0000-000000000002'::uuid
  ) $$,
  'staff publish the edited exhibition again'
);
select lives_ok(
  $$ select public.admin_publish_exhibition(
    'staff-only', '42200000-0000-0000-0000-000000000002'::uuid, 1,
    '42900000-0000-0000-0000-000000000003'::uuid
  ) $$,
  'staff publish an exhibition that has no gallery'
);
select lives_ok(
  $$ select public.admin_publish_exhibition(
    'no-audience', '42200000-0000-0000-0000-000000000004'::uuid, 1,
    '42900000-0000-0000-0000-000000000004'::uuid
  ) $$,
  'staff publish an exhibition whose owner has no email'
);
select set_config('gallr.suppress_admin_notifications', 'on', true);
select lives_ok(
  $$ select public.admin_publish_exhibition(
    'suppressed', '42200000-0000-0000-0000-000000000005'::uuid, 1,
    '42900000-0000-0000-0000-000000000005'::uuid
  ) $$,
  'staff publish while notifications are suppressed'
);
select set_config('gallr.suppress_admin_notifications', '', true);
reset role;

select is(
  (
    select string_agg(aggregate_id, ',' order by aggregate_id)
    from content.outbox_events
    where event_type = 'owner_exhibition.published'
  ),
  null,
  'republication, staff-only exhibitions, audiences without email, and suppressed runs queue nothing'
);

select * from finish();

rollback;
