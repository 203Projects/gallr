-- Admin email notifications.
--
-- Owner, editor, and public-form actions that staff must review or should
-- know about now enqueue one durable `admin_notification.requested` outbox
-- event addressed to every active admin. The outbox-delivery Edge Function
-- renders and sends the email. Two triggers feed the queue: one on
-- exhibition submissions (every source, on becoming `submitted`) and one on
-- the audit log for an allowlist of external-actor actions. Helpers are
-- SECURITY DEFINER with an empty search path and are callable only from
-- triggers; no client or service role can execute them directly.

create or replace function content_private.admin_notification_recipients()
returns text[]
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    array_agg(lower(btrim(account.email)) order by lower(btrim(account.email))),
    array[]::text[]
  )
  from content.staff_members as staff
  join auth.users as account on account.id = staff.user_id
  where staff.active
    and staff.role = 'admin'::content.staff_role
    and btrim(account.email) ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$';
$$;

revoke all on function content_private.admin_notification_recipients()
  from public, anon, authenticated, service_role;

-- Resolves a display name for context enrichment. The latest draft wins so
-- staff see the name the owner or editor is currently working with; without a
-- draft the published name is used before any superseded version.
create or replace function content_private.admin_notification_exhibition_name(
  p_exhibition_id text
)
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    nullif(btrim(version.name_en), ''),
    nullif(btrim(version.name_ko), '')
  )
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
  order by
    (version.status = 'draft'::content.exhibition_version_status) desc,
    (version.status = 'published'::content.exhibition_version_status) desc,
    version.version_number desc
  limit 1;
$$;

revoke all on function content_private.admin_notification_exhibition_name(text)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_notification_gallery_name(
  p_gallery_id uuid
)
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    nullif(btrim(gallery.name_en), ''),
    nullif(btrim(gallery.name_ko), '')
  )
  from content.galleries as gallery
  where gallery.id = p_gallery_id;
$$;

revoke all on function content_private.admin_notification_gallery_name(uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_notification_editor_name(
  p_editor_id text
)
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    nullif(btrim(editor.name_en), ''),
    nullif(btrim(editor.name_ko), '')
  )
  from public.editors as editor
  where editor.id = p_editor_id;
$$;

revoke all on function content_private.admin_notification_editor_name(text)
  from public, anon, authenticated, service_role;

-- Bulk backfills, fixture loads, and audited replays set
-- `gallr.suppress_admin_notifications = 'on'` for their transaction so staff
-- are not emailed once per row. Any other value keeps notifications on.
create or replace function content_private.admin_notifications_suppressed()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    current_setting('gallr.suppress_admin_notifications', true), ''
  ) = 'on';
$$;

revoke all on function content_private.admin_notifications_suppressed()
  from public, anon, authenticated, service_role;

-- Returns true when a new event was queued and false when the deduplication
-- key already existed or nobody can receive the notification. The acting user
-- is never a recipient of their own action. Context strings are bounded so the
-- consumer never has to reject an otherwise valid event. The retry budget is
-- raised above the outbox default so a delivery function that lags the
-- migration by a few hours does not dead-letter notifications.
create or replace function content_private.enqueue_admin_notification(
  p_kind text,
  p_entity_type text,
  p_entity_id text,
  p_actor_email text,
  p_context jsonb,
  p_deduplication_key text,
  p_occurred_at timestamptz default now()
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor_email text := nullif(lower(btrim(coalesce(p_actor_email, ''))), '');
  v_recipients text[] := array_remove(
    content_private.admin_notification_recipients(),
    v_actor_email
  );
  v_context jsonb;
  v_inserted boolean := false;
begin
  if coalesce(array_length(v_recipients, 1), 0) = 0 then
    return false;
  end if;

  select coalesce(
    jsonb_object_agg(
      entry.key,
      case
        when jsonb_typeof(entry.value) = 'string'
          then to_jsonb(left(btrim(entry.value #>> '{}'), 500))
        else entry.value
      end
    ),
    '{}'::jsonb
  )
  into v_context
  from jsonb_each(coalesce(p_context, '{}'::jsonb)) as entry
  where jsonb_typeof(entry.value) in ('string', 'number', 'boolean')
    and (
      jsonb_typeof(entry.value) <> 'string'
      or nullif(btrim(entry.value #>> '{}'), '') is not null
    );

  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key,
    max_attempts
  ) values (
    p_entity_type,
    p_entity_id,
    'admin_notification.requested',
    jsonb_build_object(
      'kind', p_kind,
      'entity_type', p_entity_type,
      'entity_id', p_entity_id,
      'actor_email', v_actor_email,
      'recipient_emails', to_jsonb(v_recipients),
      'occurred_at', coalesce(p_occurred_at, now()),
      'context', v_context
    ),
    p_deduplication_key,
    12
  )
  on conflict (deduplication_key) do nothing;
  get diagnostics v_inserted = row_count;
  return v_inserted;
end;
$$;

revoke all on function content_private.enqueue_admin_notification(
  text, text, text, text, jsonb, text, timestamptz
) from public, anon, authenticated, service_role;

create or replace function content_private.queue_admin_notification_for_submission()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_submitter_email text := nullif(
    lower(btrim(coalesce(new.submitter_email, ''))), ''
  );
begin
  if content_private.admin_notifications_suppressed() then
    return new;
  end if;
  if new.status <> 'submitted'::content.submission_status then
    return new;
  end if;
  if tg_op = 'UPDATE' and old.status = new.status then
    return new;
  end if;

  perform content_private.enqueue_admin_notification(
    'exhibition_submission.submitted',
    'exhibition_submission',
    new.id::text,
    v_submitter_email,
    jsonb_build_object(
      'exhibition_name', coalesce(
        nullif(btrim(new.payload ->> 'name_en'), ''),
        nullif(btrim(new.payload ->> 'name_ko'), '')
      ),
      'venue_name', coalesce(
        nullif(btrim(new.payload ->> 'venue_name_en'), ''),
        nullif(btrim(new.payload ->> 'venue_name_ko'), '')
      ),
      'source', new.source,
      'submitter_email', v_submitter_email
    ),
    format(
      'admin_notification:submission:%s:%s',
      new.id,
      to_char(
        coalesce(new.submitted_at, now()) at time zone 'UTC',
        'YYYY-MM-DD"T"HH24:MI:SS"Z"'
      )
    ),
    coalesce(new.submitted_at, now())
  );
  return new;
end;
$$;

revoke all on function content_private.queue_admin_notification_for_submission()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_submissions_admin_notification
  on content.exhibition_submissions;
create trigger exhibition_submissions_admin_notification
after insert or update of status on content.exhibition_submissions
for each row
execute function content_private.queue_admin_notification_for_submission();

-- Submission audit rows (`owner_exhibition.submitted`,
-- `editor.exhibition_submitted`) are intentionally absent: the submission
-- trigger above already covers every submission source.
create or replace function content_private.queue_admin_notification_for_audit()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_context jsonb := '{}'::jsonb;
  v_actor_email text;
  v_gallery_id uuid;
  v_exhibition_id text;
  v_editor_id text;
  v_deduplication_key text;
begin
  if content_private.admin_notifications_suppressed() then
    return new;
  end if;
  if new.action not in (
    'gallery.claim_requested',
    'gallery.created_and_claimed',
    'gallery.info_saved',
    'owner_exhibition.hidden',
    'local_promotion.requested',
    'launch_kit.activated',
    'editor.profile_submitted',
    'editor.curation_submitted',
    'editor.onboarded'
  ) then
    return new;
  end if;

  begin
    v_gallery_id := coalesce(
      (new.metadata ->> 'gallery_id')::uuid,
      case when new.entity_type = 'gallery' then new.entity_id::uuid end
    );
  exception when invalid_text_representation then
    v_gallery_id := null;
  end;
  v_exhibition_id := coalesce(
    nullif(new.metadata ->> 'exhibition_id', ''),
    case when new.entity_type = 'exhibition' then new.entity_id end
  );
  v_editor_id := coalesce(
    nullif(new.metadata ->> 'editor_id', ''),
    case when new.entity_type = 'editor' then new.entity_id end
  );

  if v_exhibition_id is not null then
    v_context := v_context || jsonb_build_object(
      'exhibition_name',
      content_private.admin_notification_exhibition_name(v_exhibition_id)
    );
  end if;
  if v_gallery_id is not null then
    v_context := v_context || jsonb_build_object(
      'gallery_name',
      content_private.admin_notification_gallery_name(v_gallery_id)
    );
  end if;
  if v_editor_id is not null then
    v_context := v_context || jsonb_build_object(
      'editor_id', v_editor_id,
      'editor_name', content_private.admin_notification_editor_name(v_editor_id)
    );
  end if;
  if jsonb_typeof(new.metadata -> 'change_count') = 'number' then
    v_context := v_context
      || jsonb_build_object('change_count', new.metadata -> 'change_count');
  end if;
  if jsonb_typeof(new.metadata -> 'entitlement_source') = 'string' then
    v_context := v_context || jsonb_build_object(
      'entitlement_source', new.metadata -> 'entitlement_source'
    );
  end if;

  if new.actor_user_id is not null then
    select account.email
    into v_actor_email
    from auth.users as account
    where account.id = new.actor_user_id;
  end if;

  -- Profile saves are repeatable edits: one email per gallery per hour is
  -- enough for staff and bounds what a scripted owner session can send.
  v_deduplication_key := case new.action
    when 'gallery.info_saved' then format(
      'admin_notification:audit:%s:%s:%s',
      new.action,
      new.entity_id,
      to_char(new.occurred_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24')
    )
    else format('admin_notification:audit:%s', new.id)
  end;

  perform content_private.enqueue_admin_notification(
    new.action,
    new.entity_type,
    new.entity_id,
    v_actor_email,
    v_context,
    v_deduplication_key,
    new.occurred_at
  );
  return new;
end;
$$;

revoke all on function content_private.queue_admin_notification_for_audit()
  from public, anon, authenticated, service_role;

-- The WHEN clause keeps hot audit writers (admin saves, imports, mirrors) from
-- entering the function at all; the in-function allowlist remains as defence.
drop trigger if exists audit_log_admin_notification on content.audit_log;
create trigger audit_log_admin_notification
after insert on content.audit_log
for each row
when (
  new.action in (
    'gallery.claim_requested',
    'gallery.created_and_claimed',
    'gallery.info_saved',
    'owner_exhibition.hidden',
    'local_promotion.requested',
    'launch_kit.activated',
    'editor.profile_submitted',
    'editor.curation_submitted',
    'editor.onboarded'
  )
)
execute function content_private.queue_admin_notification_for_audit();
