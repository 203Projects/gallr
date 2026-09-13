begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(33);

-- Contract surface -----------------------------------------------------------

select has_function(
  'content_private', 'admin_notification_recipients', array[]::text[],
  'admin recipient resolver exists'
);
select has_function(
  'content_private', 'enqueue_admin_notification',
  array['text', 'text', 'text', 'text', 'jsonb', 'text', 'timestamptz'],
  'admin notification enqueue helper exists'
);
select has_trigger(
  'content', 'exhibition_submissions', 'exhibition_submissions_admin_notification',
  'submitted exhibition submissions notify staff'
);
select has_trigger(
  'content', 'audit_log', 'audit_log_admin_notification',
  'allowlisted audit actions notify staff'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    cross join lateral aclexplode(procedure.proacl) as privilege
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname in (
        'admin_notification_recipients',
        'enqueue_admin_notification',
        'queue_admin_notification_for_submission',
        'queue_admin_notification_for_audit'
      )
      and (
        privilege.grantee = 0
        or privilege.grantee in (
          select oid from pg_catalog.pg_roles
          where rolname in ('anon', 'authenticated', 'service_role')
        )
      )
  ),
  'notification helpers expose no client or service-role surface'
);

select ok(
  (
    select bool_and(
      procedure.prosecdef
      and procedure.proconfig = array['search_path=""']::text[]
    )
    from pg_catalog.pg_proc as procedure
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname in (
        'admin_notification_recipients',
        'enqueue_admin_notification',
        'queue_admin_notification_for_submission',
        'queue_admin_notification_for_audit'
      )
  ),
  'notification helpers are security definer with a pinned empty search path'
);

-- Fixtures --------------------------------------------------------------------

insert into auth.users (id, email, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000004101', 'Active.Admin@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004102', 'inactive-admin@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004103', 'publisher@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004104', null, '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004105', 'Owner@example.invalid', '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values
  ('00000000-0000-0000-0000-000000004101', 'admin', true),
  ('00000000-0000-0000-0000-000000004102', 'admin', false),
  ('00000000-0000-0000-0000-000000004103', 'publisher', true),
  ('00000000-0000-0000-0000-000000004104', 'admin', true);

insert into content.galleries (id, name_ko, name_en, status, created_by, updated_by)
values (
  '41100000-0000-0000-0000-000000000001', '스페이스 원', 'Space One', 'pending',
  '00000000-0000-0000-0000-000000004105', '00000000-0000-0000-0000-000000004105'
);

insert into content.exhibitions (id, created_by, updated_by)
values (
  'notify-exhibition',
  '00000000-0000-0000-0000-000000004101',
  '00000000-0000-0000-0000-000000004101'
);

insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours,
  created_by, updated_by
)
values (
  '41200000-0000-0000-0000-000000000001', 'notify-exhibition', 1, 1, 'draft',
  '숨긴 전시', 'Hidden Show', '장소', 'Venue', '서울', 'Seoul', '종로구', 'Jongno-gu',
  '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-09-30', 'Daily',
  '00000000-0000-0000-0000-000000004101', '00000000-0000-0000-0000-000000004101'
);

delete from content.outbox_events where event_type = 'admin_notification.requested';

-- Recipient resolution --------------------------------------------------------

select is(
  content_private.admin_notification_recipients(),
  array['active.admin@example.invalid']::text[],
  'only active admins with an email address receive notifications'
);

-- Submission trigger ----------------------------------------------------------

insert into content.exhibition_submissions (
  id, status, source, submitter_email, payload, submitted_at
) values (
  '41300000-0000-0000-0000-000000000001', 'submitted', 'public_form',
  'Visitor@example.invalid',
  '{"name_ko":"공개 제출","name_en":"Public Entry","venue_name_ko":"장소","venue_name_en":"Venue Hall"}'::jsonb,
  '2026-09-13 03:00:00+00'
);

select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type = 'admin_notification.requested'
      and aggregate_type = 'exhibition_submission'
      and aggregate_id = '41300000-0000-0000-0000-000000000001'
  ),
  1,
  'a public-form submission enqueues one admin notification'
);

select is(
  (
    select payload
    from content.outbox_events
    where aggregate_id = '41300000-0000-0000-0000-000000000001'
      and event_type = 'admin_notification.requested'
  ) - 'occurred_at',
  jsonb_build_object(
    'kind', 'exhibition_submission.submitted',
    'entity_type', 'exhibition_submission',
    'entity_id', '41300000-0000-0000-0000-000000000001',
    'actor_email', 'visitor@example.invalid',
    'recipient_emails', jsonb_build_array('active.admin@example.invalid'),
    'context', jsonb_build_object(
      'exhibition_name', 'Public Entry',
      'venue_name', 'Venue Hall',
      'source', 'public_form',
      'submitter_email', 'visitor@example.invalid'
    )
  ),
  'the submission payload carries the bounded context staff need'
);

select ok(
  (
    select (payload ->> 'occurred_at')::timestamptz = '2026-09-13 03:00:00+00'
    from content.outbox_events
    where aggregate_id = '41300000-0000-0000-0000-000000000001'
      and event_type = 'admin_notification.requested'
  ),
  'the submission notification records the submission time'
);

select is(
  (
    select deduplication_key from content.outbox_events
    where aggregate_id = '41300000-0000-0000-0000-000000000001'
      and event_type = 'admin_notification.requested'
  ),
  'admin_notification:submission:41300000-0000-0000-0000-000000000001:2026-09-13T03:00:00Z',
  'the submission notification is deduplicated per submitted_at'
);

insert into content.exhibition_submissions (
  id, status, source, submitter_email, payload
) values (
  '41300000-0000-0000-0000-000000000002', 'pending_upload', 'public_form',
  'later@example.invalid', '{"name_ko":"대기 제출"}'::jsonb
);

select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type = 'admin_notification.requested'
      and aggregate_id = '41300000-0000-0000-0000-000000000002'
  ),
  0,
  'a submission still uploading does not notify staff'
);

update content.exhibition_submissions
set status = 'submitted', submitted_at = now()
where id = '41300000-0000-0000-0000-000000000002';

select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type = 'admin_notification.requested'
      and aggregate_id = '41300000-0000-0000-0000-000000000002'
  ),
  1,
  'a submission that becomes submitted notifies staff'
);

select is(
  (
    select payload -> 'context' ->> 'exhibition_name' from content.outbox_events
    where event_type = 'admin_notification.requested'
      and aggregate_id = '41300000-0000-0000-0000-000000000002'
  ),
  '대기 제출',
  'the Korean name is used when no English name exists'
);

update content.exhibition_submissions
set status = 'in_review', reviewed_by = '00000000-0000-0000-0000-000000004101'
where id = '41300000-0000-0000-0000-000000000002';

select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type = 'admin_notification.requested'
      and aggregate_id = '41300000-0000-0000-0000-000000000002'
  ),
  1,
  'staff review transitions do not notify staff again'
);

insert into content.exhibition_submissions (
  id, status, source, submitter_email, payload, submitted_at
) values (
  '41300000-0000-0000-0000-000000000003', 'submitted', 'editor_workspace',
  'editor@example.invalid', '{"name_ko":"에디터 제출"}'::jsonb, now()
);

select is(
  (
    select payload -> 'context' ->> 'source' from content.outbox_events
    where event_type = 'admin_notification.requested'
      and aggregate_id = '41300000-0000-0000-0000-000000000003'
  ),
  'editor_workspace',
  'editor-workspace submissions notify staff with their source'
);

-- Audit trigger ---------------------------------------------------------------

insert into content.audit_log (
  id, actor_user_id, action, entity_type, entity_id, metadata, occurred_at
) values (
  '41400000-0000-0000-0000-000000000001',
  '00000000-0000-0000-0000-000000004105',
  'gallery.claim_requested', 'gallery', '41100000-0000-0000-0000-000000000001',
  jsonb_build_object(
    'gallery_id', '41100000-0000-0000-0000-000000000001',
    'request_fingerprint', 'secret-fingerprint'
  ),
  '2026-09-13 04:00:00+00'
);

select is(
  (
    select payload - 'occurred_at'
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000001'
  ),
  jsonb_build_object(
    'kind', 'gallery.claim_requested',
    'entity_type', 'gallery',
    'entity_id', '41100000-0000-0000-0000-000000000001',
    'actor_email', 'owner@example.invalid',
    'recipient_emails', jsonb_build_array('active.admin@example.invalid'),
    'context', jsonb_build_object('gallery_name', 'Space One')
  ),
  'a gallery claim notifies staff with the gallery name and claimant'
);

select is(
  (
    select deduplication_key
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000001'
  ),
  'admin_notification:audit:41400000-0000-0000-0000-000000000001',
  'audit notifications are deduplicated per audit row'
);

select ok(
  (
    select (payload ->> 'occurred_at')::timestamptz = '2026-09-13 04:00:00+00'
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000001'
  ),
  'audit notifications record the audit time'
);

insert into content.audit_log (
  id, actor_user_id, action, entity_type, entity_id, metadata
) values (
  '41400000-0000-0000-0000-000000000002',
  '00000000-0000-0000-0000-000000004105',
  'owner_exhibition.hidden', 'exhibition', 'notify-exhibition',
  jsonb_build_object('version_id', '41200000-0000-0000-0000-000000000001')
);

select is(
  (
    select payload -> 'context'
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000002'
  ),
  jsonb_build_object('exhibition_name', 'Hidden Show'),
  'exhibition-scoped actions resolve the exhibition name'
);

insert into content.audit_log (
  id, actor_user_id, action, entity_type, entity_id, metadata
) values (
  '41400000-0000-0000-0000-000000000003',
  '00000000-0000-0000-0000-000000004105',
  'local_promotion.requested', 'local_promotion', '41500000-0000-0000-0000-000000000001',
  jsonb_build_object(
    'launch_kit_id', '41600000-0000-0000-0000-000000000001',
    'exhibition_id', 'notify-exhibition',
    'gallery_id', '41100000-0000-0000-0000-000000000001'
  )
);

select is(
  (
    select payload -> 'context'
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000003'
  ),
  jsonb_build_object('exhibition_name', 'Hidden Show', 'gallery_name', 'Space One'),
  'promotion requests resolve both the exhibition and gallery names'
);

insert into content.audit_log (
  id, actor_user_id, action, entity_type, entity_id, metadata
) values (
  '41400000-0000-0000-0000-000000000004',
  '00000000-0000-0000-0000-000000004105',
  'editor.curation_submitted', 'editor_request', '41700000-0000-0000-0000-000000000001',
  jsonb_build_object('editor_id', 'editor-one', 'change_count', 3)
);

select is(
  (
    select payload -> 'context'
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000004'
  ),
  jsonb_build_object('editor_id', 'editor-one', 'change_count', 3),
  'editor requests carry the editor id and change count'
);

insert into content.audit_log (
  id, action, entity_type, entity_id, metadata
) values (
  '41400000-0000-0000-0000-000000000005',
  'launch_kit.activated', 'launch_kit', '41600000-0000-0000-0000-000000000001',
  jsonb_build_object(
    'exhibition_id', 'notify-exhibition',
    'stripe_event_id', 'evt_secret',
    'amount_total', 99000,
    'currency', 'krw'
  )
);

select is(
  (
    select payload - 'occurred_at'
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000005'
  ),
  jsonb_build_object(
    'kind', 'launch_kit.activated',
    'entity_type', 'launch_kit',
    'entity_id', '41600000-0000-0000-0000-000000000001',
    'actor_email', null,
    'recipient_emails', jsonb_build_array('active.admin@example.invalid'),
    'context', jsonb_build_object('exhibition_name', 'Hidden Show')
  ),
  'system-originated activations notify staff without leaking billing metadata'
);

insert into content.audit_log (
  id, actor_user_id, action, entity_type, entity_id, metadata
) values (
  '41400000-0000-0000-0000-000000000006',
  '00000000-0000-0000-0000-000000004105',
  'editor.onboarded', 'editor', 'editor-two',
  jsonb_build_object('invited_by', '00000000-0000-0000-0000-000000004101')
);

select is(
  (
    select payload -> 'context'
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000006'
  ),
  jsonb_build_object('editor_id', 'editor-two'),
  'editor onboarding records the editor id from the audit entity'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and payload ->> 'kind' in (
        'gallery.created_and_claimed', 'gallery.info_saved',
        'editor.profile_submitted'
      )
  ),
  0,
  'no notifications exist for the remaining allowlisted actions yet'
);

insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
values
  ('00000000-0000-0000-0000-000000004105', 'gallery.created_and_claimed', 'gallery',
   '41100000-0000-0000-0000-000000000001', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004105', 'gallery.info_saved', 'gallery',
   '41100000-0000-0000-0000-000000000001', '{"changed_fields":["name_en"]}'::jsonb),
  ('00000000-0000-0000-0000-000000004105', 'editor.profile_submitted', 'editor_request',
   '41700000-0000-0000-0000-000000000002', '{"editor_id":"editor-one"}'::jsonb);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and payload ->> 'kind' in (
        'gallery.created_and_claimed', 'gallery.info_saved',
        'editor.profile_submitted'
      )
  ),
  3,
  'every allowlisted owner and editor action notifies staff'
);

insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
values
  ('00000000-0000-0000-0000-000000004101', 'editor.created', 'editor', 'editor-three', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004101', 'exhibition.published', 'exhibition',
   'notify-exhibition', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004105', 'owner_exhibition.submitted', 'exhibition',
   'notify-exhibition', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004105', 'editor.exhibition_submitted',
   'exhibition_submission', '41300000-0000-0000-0000-000000000003', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004105', 'owner_exhibition.draft_saved', 'exhibition',
   'notify-exhibition', '{}'::jsonb);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and payload ->> 'kind' in (
        'editor.created', 'exhibition.published', 'owner_exhibition.submitted',
        'editor.exhibition_submitted', 'owner_exhibition.draft_saved'
      )
  ),
  0,
  'staff actions, routine saves, and submission audit rows do not double-notify'
);

-- Deduplication and empty audiences -------------------------------------------

select is(
  content_private.enqueue_admin_notification(
    'gallery.claim_requested', 'gallery', '41100000-0000-0000-0000-000000000001',
    'owner@example.invalid', '{}'::jsonb,
    'admin_notification:test:replay'
  ),
  true,
  'the enqueue helper reports a newly queued notification'
);

select is(
  content_private.enqueue_admin_notification(
    'gallery.claim_requested', 'gallery', '41100000-0000-0000-0000-000000000001',
    'owner@example.invalid', '{}'::jsonb,
    'admin_notification:test:replay'
  ),
  false,
  'a replayed deduplication key does not queue a second notification'
);

select is(
  (
    select count(*)::integer from content.outbox_events
    where deduplication_key = 'admin_notification:test:replay'
  ),
  1,
  'the replayed key keeps exactly one outbox event'
);

update content.staff_members set active = false
where user_id = '00000000-0000-0000-0000-000000004101';

select is(
  content_private.admin_notification_recipients(),
  array[]::text[],
  'deactivating the last admin leaves no recipients'
);

insert into content.audit_log (
  id, actor_user_id, action, entity_type, entity_id, metadata
) values (
  '41400000-0000-0000-0000-000000000007',
  '00000000-0000-0000-0000-000000004105',
  'gallery.claim_requested', 'gallery', '41100000-0000-0000-0000-000000000001',
  '{}'::jsonb
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000007'
  ),
  0,
  'no notification is queued when nobody can receive it'
);

select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type = 'admin_notification.requested'
      and jsonb_typeof(payload -> 'recipient_emails') <> 'array'
  ),
  0,
  'every queued notification carries a recipient array'
);

select * from finish();

rollback;
