begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(12);

select has_function(
  'content_private', 'queue_owner_exhibition_published', array[]::text[],
  'the publication email trigger function exists'
);
select has_trigger(
  'content', 'exhibitions', 'exhibitions_owner_published_outbox',
  'owner exhibitions that become published notify their owners'
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
  ('00000000-0000-0000-0000-000000004201', 'Owner.One@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004202', 'owner.two@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004203', 'pending@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004204', null, '{}'::jsonb);

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
   '00000000-0000-0000-0000-000000004201', '00000000-0000-0000-0000-000000004201');

insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours,
  created_by, updated_by
)
values
  ('42200000-0000-0000-0000-000000000001', 'published-email', 1, 1, 'draft',
   '작은 방의 기록', 'Notes from a Small Room', '장소', 'Venue', '서울', 'Seoul',
   '종로구', 'Jongno-gu', '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-09-30',
   'Daily', '00000000-0000-0000-0000-000000004201', '00000000-0000-0000-0000-000000004201'),
  ('42200000-0000-0000-0000-000000000002', 'staff-only', 1, 1, 'draft',
   '직원 전시', 'Staff Show', '장소', 'Venue', '서울', 'Seoul',
   '종로구', 'Jongno-gu', '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-09-30',
   'Daily', '00000000-0000-0000-0000-000000004201', '00000000-0000-0000-0000-000000004201');

delete from content.outbox_events where event_type = 'owner_exhibition.published';

-- Publication through the real version transition -------------------------------

update content.exhibitions
set published_version_id = '42200000-0000-0000-0000-000000000001'
where id = 'published-email';
update content.exhibition_versions
set status = 'published', published_at = now(),
  published_by = '00000000-0000-0000-0000-000000004201'
where id = '42200000-0000-0000-0000-000000000001';

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
  'publication emails the active owner and skips pending and revoked members'
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

-- Re-publication after an edit does not email again ---------------------------

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
   'Daily', '00000000-0000-0000-0000-000000004201', '00000000-0000-0000-0000-000000004201');
update content.exhibitions
set owner_status = 'submitted', owner_status_changed_at = now()
where id = 'published-email';
update content.exhibition_versions
set status = 'superseded'
where id = '42200000-0000-0000-0000-000000000001';
update content.exhibitions
set published_version_id = '42200000-0000-0000-0000-000000000003'
where id = 'published-email';
update content.exhibition_versions
set status = 'published', published_at = now(),
  published_by = '00000000-0000-0000-0000-000000004201'
where id = '42200000-0000-0000-0000-000000000003';

select is(
  (select owner_status::text from content.exhibitions where id = 'published-email'),
  'published',
  'the edited exhibition is published again'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'owner_exhibition.published'
      and aggregate_id = 'published-email'
  ),
  1,
  'a later publication of the same exhibition does not email again'
);

-- Exhibitions outside the owner workspace and audiences without email ------------

update content.exhibitions
set published_version_id = '42200000-0000-0000-0000-000000000002'
where id = 'staff-only';
update content.exhibition_versions
set status = 'published', published_at = now(),
  published_by = '00000000-0000-0000-0000-000000004201'
where id = '42200000-0000-0000-0000-000000000002';

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'owner_exhibition.published'
      and aggregate_id = 'staff-only'
  ),
  0,
  'staff-managed exhibitions without a gallery do not email anyone'
);

insert into content.exhibitions (
  id, gallery_id, owner_status, owner_status_changed_at, created_by, updated_by
)
values
  ('no-audience', '42100000-0000-0000-0000-000000000002', 'submitted', now(),
   '00000000-0000-0000-0000-000000004204', '00000000-0000-0000-0000-000000004204');

update content.exhibitions
set owner_status = 'published', owner_status_changed_at = now()
where id = 'no-audience';

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'owner_exhibition.published'
      and aggregate_id = 'no-audience'
  ),
  0,
  'no event is queued when no active owner has a well-formed email'
);

select set_config('gallr.suppress_admin_notifications', 'on', true);

insert into content.exhibitions (
  id, gallery_id, owner_status, owner_status_changed_at, created_by, updated_by
)
values
  ('suppressed', '42100000-0000-0000-0000-000000000001', 'submitted', now(),
   '00000000-0000-0000-0000-000000004201', '00000000-0000-0000-0000-000000004201');
update content.exhibitions
set owner_status = 'published', owner_status_changed_at = now()
where id = 'suppressed';

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'owner_exhibition.published'
      and aggregate_id = 'suppressed'
  ),
  0,
  'the bulk-operation suppression setting also skips publication emails'
);

select set_config('gallr.suppress_admin_notifications', '', true);

select * from finish();

rollback;
