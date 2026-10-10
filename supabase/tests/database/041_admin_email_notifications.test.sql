begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(59);

-- Contract surface -----------------------------------------------------------

select has_function(
  'content_private', 'admin_notification_recipients', array[]::text[],
  'admin recipient resolver exists'
);
select has_function(
  'content_private', 'enqueue_admin_notification',
  array['text', 'text', 'text', 'text', 'jsonb', 'text', 'timestamptz', 'boolean'],
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
select has_trigger(
  'content', 'gallery_memberships', 'gallery_membership_claim_decision_outbox',
  'gallery claim decisions notify the claimant'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_proc as procedure
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname in (
        'admin_notification_recipients',
        'admin_notification_exhibition_name',
        'admin_notification_gallery_name',
        'admin_notification_editor_name',
        'admin_notifications_suppressed',
        'admin_notification_audit_actions',
        'enqueue_admin_notification',
        'queue_admin_notification_for_submission',
        'queue_admin_notification_for_audit',
        'queue_gallery_claim_decision'
      )
  ),
  10,
  'every notification helper exists'
);

select is(
  cardinality(content_private.admin_notification_audit_actions()),
  9,
  'the audit allowlist names nine owner and editor actions'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    cross join lateral pg_catalog.aclexplode(
      coalesce(
        procedure.proacl,
        pg_catalog.acldefault('f', procedure.proowner)
      )
    ) as privilege
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname in (
        'admin_notification_recipients',
        'admin_notification_exhibition_name',
        'admin_notification_gallery_name',
        'admin_notification_editor_name',
        'admin_notifications_suppressed',
        'enqueue_admin_notification',
        'queue_admin_notification_for_submission',
        'queue_admin_notification_for_audit',
        'queue_gallery_claim_decision'
      )
      and privilege.privilege_type = 'EXECUTE'
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
    select pg_catalog.pg_get_triggerdef(trigger.oid)
      like '%WHEN ((new.action = ANY (content_private.admin_notification_audit_actions())))%'
    from pg_catalog.pg_trigger as trigger
    where trigger.tgname = 'audit_log_admin_notification'
      and trigger.tgrelid = 'content.audit_log'::regclass
  ),
  'the audit trigger filters on the shared allowlist before entering the function'
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
        'admin_notification_exhibition_name',
        'admin_notification_gallery_name',
        'admin_notification_editor_name',
        'admin_notifications_suppressed',
        'enqueue_admin_notification',
        'queue_admin_notification_for_submission',
        'queue_admin_notification_for_audit',
        'queue_gallery_claim_decision'
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
  ('00000000-0000-0000-0000-000000004105', 'Owner@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004106', 'not-an-email', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004107', 'Second.Admin@example.invalid', '{}'::jsonb),
  ('00000000-0000-0000-0000-000000004108', 'Competing@example.invalid', '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values
  ('00000000-0000-0000-0000-000000004101', 'admin', true),
  ('00000000-0000-0000-0000-000000004102', 'admin', false),
  ('00000000-0000-0000-0000-000000004103', 'publisher', true),
  ('00000000-0000-0000-0000-000000004104', 'admin', true),
  ('00000000-0000-0000-0000-000000004106', 'admin', true);

insert into content.galleries (id, name_ko, name_en, status, created_by, updated_by)
values
  ('41100000-0000-0000-0000-000000000001', '스페이스 원', 'Space One', 'pending',
   '00000000-0000-0000-0000-000000004105', '00000000-0000-0000-0000-000000004105'),
  ('41100000-0000-0000-0000-000000000002', '스페이스 투', '', 'pending',
   '00000000-0000-0000-0000-000000004104', '00000000-0000-0000-0000-000000004104');

insert into content.gallery_memberships (
  gallery_id, user_id, status, claim_note, created_by, updated_by
)
values
  ('41100000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000004105',
   'pending', 'We run this space',
   '00000000-0000-0000-0000-000000004105', '00000000-0000-0000-0000-000000004105'),
  ('41100000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000004108',
   'pending', 'Competing claim',
   '00000000-0000-0000-0000-000000004108', '00000000-0000-0000-0000-000000004108'),
  ('41100000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000004104',
   'pending', 'No email on file',
   '00000000-0000-0000-0000-000000004104', '00000000-0000-0000-0000-000000004104');

insert into content.exhibitions (id, gallery_id, created_by, updated_by)
values
  ('notify-exhibition', '41100000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-000000004101', '00000000-0000-0000-0000-000000004101'),
  ('notify-published-only', null,
   '00000000-0000-0000-0000-000000004101', '00000000-0000-0000-0000-000000004101');

insert into public.editors (
  id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en, is_active, active_from
)
values ('editor-two', '두번째 에디터', 'Second Editor', '에디터', 'Editor', '소개', 'Bio', true, current_date);

insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours,
  published_at, published_by, created_by, updated_by
)
values
  ('41200000-0000-0000-0000-000000000001', 'notify-exhibition', 1, 1, 'draft',
   '숨긴 전시', 'Hidden Show', '장소', 'Venue', '서울', 'Seoul', '종로구', 'Jongno-gu',
   '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-09-30', 'Daily', null, null,
   '00000000-0000-0000-0000-000000004101', '00000000-0000-0000-0000-000000004101'),
  ('41200000-0000-0000-0000-000000000002', 'notify-exhibition', 2, 1, 'superseded',
   '', 'Superseded Show', '장소', 'Venue', '서울', 'Seoul', '종로구', 'Jongno-gu',
   '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-09-30', 'Daily', now(),
   '00000000-0000-0000-0000-000000004101',
   '00000000-0000-0000-0000-000000004101', '00000000-0000-0000-0000-000000004101'),
  ('41200000-0000-0000-0000-000000000003', 'notify-published-only', 1, 1, 'published',
   '공개 전시', 'Live Show', '장소', 'Venue', '서울', 'Seoul', '종로구', 'Jongno-gu',
   '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-09-30', 'Daily', now(),
   '00000000-0000-0000-0000-000000004101',
   '00000000-0000-0000-0000-000000004101', '00000000-0000-0000-0000-000000004101'),
  ('41200000-0000-0000-0000-000000000004', 'notify-published-only', 2, 1, 'superseded',
   '', 'Superseded Live Show', '장소', 'Venue', '서울', 'Seoul', '종로구', 'Jongno-gu',
   '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-09-30', 'Daily', now(),
   '00000000-0000-0000-0000-000000004101',
   '00000000-0000-0000-0000-000000004101', '00000000-0000-0000-0000-000000004101');

delete from content.outbox_events where event_type = 'admin_notification.requested';

-- Recipient resolution --------------------------------------------------------

select is(
  content_private.admin_notification_recipients(),
  array['active.admin@example.invalid']::text[],
  'only active admins with a well-formed email address receive notifications'
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

-- The media snapshot trigger needs a full owner media pipeline; this test
-- only exercises the notification trigger, so the snapshot is bypassed inside
-- the rolled-back transaction.
alter table content.exhibition_submissions
  disable trigger exhibition_submission_snapshot_owner_media;
insert into content.exhibition_submissions (
  id, status, source, owner_exhibition_id, submitter_email, payload, submitted_at
) values (
  '41300000-0000-0000-0000-000000000006', 'submitted', 'owner_workspace',
  'notify-exhibition', 'owner@example.invalid',
  '{"version_id":"41200000-0000-0000-0000-000000000001","name_ko":"숨긴 전시","name_en":"Hidden Show"}'::jsonb,
  now()
);
alter table content.exhibition_submissions
  enable trigger exhibition_submission_snapshot_owner_media;

select is(
  (
    select payload -> 'context' from content.outbox_events
    where event_type = 'admin_notification.requested'
      and aggregate_id = '41300000-0000-0000-0000-000000000006'
  ),
  jsonb_build_object(
    'exhibition_name', 'Hidden Show',
    'gallery_name', 'Space One',
    'source', 'owner_workspace',
    'submitter_email', 'owner@example.invalid'
  ),
  'owner-workspace submissions name the gallery for staff'
);

insert into content.exhibition_submissions (
  id, status, source, submitter_email, payload, submitted_at
) values (
  '41300000-0000-0000-0000-000000000005', 'submitted', 'public_form',
  'active.admin@example.invalid', '{"name_ko":"사칭 제출"}'::jsonb, now()
);

select is(
  (
    select payload -> 'recipient_emails' from content.outbox_events
    where event_type = 'admin_notification.requested'
      and aggregate_id = '41300000-0000-0000-0000-000000000005'
  ),
  jsonb_build_array('active.admin@example.invalid'),
  'a submitter who types an admin address cannot remove that admin'
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
    'context', jsonb_build_object(
      'gallery_name', 'Space One', 'claim_note', 'We run this space'
    )
  ),
  'a gallery claim notifies staff with the gallery name, claimant, and claim note'
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

select is(
  (
    select max_attempts
    from content.outbox_events
    where deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000001'
  ),
  12,
  'admin notifications get a longer retry budget than the default'
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
  'exhibition-scoped actions prefer the draft name over newer versions'
);

insert into content.audit_log (
  id, actor_user_id, action, entity_type, entity_id, metadata
) values (
  '41400000-0000-0000-0000-000000000012',
  '00000000-0000-0000-0000-000000004105',
  'owner_exhibition.hidden', 'exhibition', 'notify-published-only', '{}'::jsonb
);

select is(
  (
    select payload -> 'context' ->> 'exhibition_name'
    from content.outbox_events
    where deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000012'
  ),
  'Live Show',
  'without a draft the published name wins over superseded versions'
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
  jsonb_build_object('editor_id', 'editor-two', 'editor_name', 'Second Editor'),
  'editor onboarding records the editor id and display name'
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
  ('00000000-0000-0000-0000-000000004105', 'gallery.info_saved', 'gallery',
   '41100000-0000-0000-0000-000000000001', '{"changed_fields":["name_ko"]}'::jsonb);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and payload ->> 'kind' = 'gallery.info_saved'
  ),
  1,
  'repeated gallery profile saves coalesce into one notification per hour'
);

select is(
  (
    select deduplication_key
    from content.outbox_events
    where event_type = 'admin_notification.requested'
      and payload ->> 'kind' = 'gallery.info_saved'
  ),
  format(
    'admin_notification:audit:gallery.info_saved:41100000-0000-0000-0000-000000000001:%s',
    to_char(now() at time zone 'UTC', 'YYYY-MM-DD"T"HH24')
  ),
  'gallery profile saves are keyed by gallery and hour'
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

-- Gallery claim decisions -------------------------------------------------------

-- Decisions must use the address captured at claim intake, not a later Auth edit.
update auth.users set email = 'changed-owner@example.invalid'
where id = '00000000-0000-0000-0000-000000004105';

delete from content.outbox_events where event_type like 'gallery_claim.%';

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000004101","role":"authenticated"}',
  true
);
select lives_ok(
  $$ select public.admin_approve_gallery_claim(
    '41100000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000004105',
    '41900000-0000-0000-0000-000000000001'
  ) $$,
  'an admin approves the owner claim'
);
select lives_ok(
  $$ select public.admin_reject_gallery_claim(
    '41100000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000004104',
    'No evidence attached.',
    '41900000-0000-0000-0000-000000000002'
  ) $$,
  'an admin rejects a claim from a user without an email'
);
reset role;

select is(
  (
    select payload
    from content.outbox_events
    where event_type = 'gallery_claim.accepted'
      and aggregate_id = '41100000-0000-0000-0000-000000000001:00000000-0000-0000-0000-000000004105'
  ),
  jsonb_build_object(
    'source', 'owner_workspace',
    'recipient_email', 'owner@example.invalid',
    'gallery_name', 'Space One',
    'review_notes', ''
  ),
  'an approved claim queues one acceptance email to the claimant'
);

select is(
  (
    select aggregate_type || '|' || max_attempts::text || '|' || deduplication_key
    from content.outbox_events
    where event_type = 'gallery_claim.accepted'
      and aggregate_id = '41100000-0000-0000-0000-000000000001:00000000-0000-0000-0000-000000004105'
  ),
  format(
    'gallery_membership|12|gallery_claim:41100000-0000-0000-0000-000000000001:00000000-0000-0000-0000-000000004105:accepted:%s',
    (
      select to_char(reviewed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
      from content.gallery_memberships
      where gallery_id = '41100000-0000-0000-0000-000000000001'
        and user_id = '00000000-0000-0000-0000-000000004105'
    )
  ),
  'the acceptance event is addressed to the membership, keyed per decision, with the longer retry budget'
);

select is(
  (
    select payload
    from content.outbox_events
    where event_type = 'gallery_claim.rejected'
      and aggregate_id = '41100000-0000-0000-0000-000000000001:00000000-0000-0000-0000-000000004108'
  ),
  jsonb_build_object(
    'source', 'owner_workspace',
    'recipient_email', 'competing@example.invalid',
    'gallery_name', 'Space One',
    'review_notes', 'Another claim for this gallery was approved.'
  ),
  'a competing claim rejected by the approval queues a rejection email with the saved notes'
);

select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type like 'gallery_claim.%'
      and aggregate_id like '41100000-0000-0000-0000-000000000002:%'
  ),
  1,
  'a missing claimant address remains a durable operational failure'
);

select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type like 'gallery_claim.%'
  ),
  3,
  'claim decisions queue exactly one event per decided claim'
);

-- A rejected claimant may claim again; the second decision must email again.
update content.gallery_memberships
set status = 'pending', reviewed_at = null, reviewed_by = null, review_notes = null
where gallery_id = '41100000-0000-0000-0000-000000000001'
  and user_id = '00000000-0000-0000-0000-000000004108';

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000004101","role":"authenticated"}',
  true
);
select lives_ok(
  $$ select public.admin_reject_gallery_claim(
    '41100000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000004108',
    'Still no evidence.',
    '41900000-0000-0000-0000-000000000003'
  ) $$,
  'an admin rejects the re-submitted claim'
);
reset role;

select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type = 'gallery_claim.rejected'
      and aggregate_id = '41100000-0000-0000-0000-000000000001:00000000-0000-0000-0000-000000004108'
  ),
  2,
  'a re-submitted claim that is rejected again queues a second rejection email'
);

-- Malformed identifiers and context bounds --------------------------------------

select lives_ok(
  $$
    insert into content.audit_log (
      id, actor_user_id, action, entity_type, entity_id, metadata
    ) values (
      '41400000-0000-0000-0000-000000000008',
      '00000000-0000-0000-0000-000000004105',
      'gallery.claim_requested', 'gallery', 'legacy-slug',
      '{"gallery_id":"not-a-uuid"}'::jsonb
    )
  $$,
  'malformed gallery identifiers do not abort the audited command'
);

select is(
  (
    select payload -> 'context'
    from content.outbox_events
    where deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000008'
  ),
  '{}'::jsonb,
  'unresolvable gallery identifiers produce no context'
);

select ok(
  content_private.enqueue_admin_notification(
    'gallery.claim_requested', 'gallery', 'context-bounds',
    'owner@example.invalid',
    jsonb_build_object(
      'a', 'x', 'b', '', 'c', null, 'd', jsonb_build_array(1),
      'e', jsonb_build_object('f', 1), 'g', 2, 'h', true,
      'long', repeat('x', 600)
    ),
    'admin_notification:test:context-bounds'
  ),
  'the enqueue helper accepts a mixed context object'
);

select is(
  (
    select payload -> 'context'
    from content.outbox_events
    where deduplication_key = 'admin_notification:test:context-bounds'
  ),
  jsonb_build_object('a', 'x', 'g', 2, 'h', true, 'long', repeat('x', 500)),
  'context keeps scalars, drops blanks and nesting, and truncates long strings'
);

-- Actor exclusion and operator bypass -------------------------------------------

insert into content.staff_members (user_id, role, active)
values ('00000000-0000-0000-0000-000000004107', 'admin', true);

insert into content.audit_log (
  id, actor_user_id, action, entity_type, entity_id, metadata
) values (
  '41400000-0000-0000-0000-000000000009',
  '00000000-0000-0000-0000-000000004101',
  'gallery.claim_requested', 'gallery', '41100000-0000-0000-0000-000000000001',
  '{}'::jsonb
);

select is(
  (
    select payload -> 'recipient_emails'
    from content.outbox_events
    where deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000009'
  ),
  jsonb_build_array('second.admin@example.invalid'),
  'an admin acting as an owner is not emailed about their own action'
);

select set_config('gallr.suppress_admin_notifications', 'on', true);

insert into content.audit_log (
  id, actor_user_id, action, entity_type, entity_id, metadata
) values (
  '41400000-0000-0000-0000-000000000010',
  '00000000-0000-0000-0000-000000004105',
  'gallery.claim_requested', 'gallery', '41100000-0000-0000-0000-000000000001',
  '{}'::jsonb
);

insert into content.exhibition_submissions (
  id, status, source, submitter_email, payload, submitted_at
) values (
  '41300000-0000-0000-0000-000000000004', 'submitted', 'public_form',
  'bulk@example.invalid', '{"name_ko":"일괄 제출"}'::jsonb, now()
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000010'
       or aggregate_id = '41300000-0000-0000-0000-000000000004'
  ),
  0,
  'the suppression setting lets bulk operations skip staff notifications'
);

select set_config('gallr.suppress_admin_notifications', 'true', true);

insert into content.audit_log (
  id, actor_user_id, action, entity_type, entity_id, metadata
) values (
  '41400000-0000-0000-0000-000000000011',
  '00000000-0000-0000-0000-000000004105',
  'gallery.claim_requested', 'gallery', '41100000-0000-0000-0000-000000000001',
  '{}'::jsonb
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000011'
  ),
  1,
  'only the exact value on suppresses notifications'
);

select set_config('gallr.suppress_admin_notifications', '', true);

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
where user_id in (
  '00000000-0000-0000-0000-000000004101',
  '00000000-0000-0000-0000-000000004107'
);

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
    select payload -> 'recipient_emails'
    from content.outbox_events
    where deduplication_key = 'admin_notification:audit:41400000-0000-0000-0000-000000000007'
  ),
  '[]'::jsonb,
  'a notification is still queued with an empty staff audience so the intake inbox receives it'
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
