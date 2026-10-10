-- Preserve notification identities at claim intake, independently of later Auth edits.
alter table content.gallery_memberships
  add column if not exists claimant_email text,
  add column if not exists claim_email_captured_at timestamptz;

create or replace function content_private.capture_claim_email()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.status = 'pending'::content.gallery_membership_status
     and (tg_op = 'INSERT' or old.status is distinct from new.status) then
    select lower(btrim(u.email)) into new.claimant_email
    from auth.users u where u.id = new.user_id;
    new.claim_email_captured_at := now();
  end if;
  return new;
end;
$$;
revoke all on function content_private.capture_claim_email() from public, anon, authenticated, service_role;
drop trigger if exists gallery_memberships_capture_claim_email on content.gallery_memberships;
create trigger gallery_memberships_capture_claim_email
before insert or update of status on content.gallery_memberships
for each row execute function content_private.capture_claim_email();

-- Prefer the existing immutable intake snapshot for historical pending claims.
-- Only older claims without one fall back to their current Auth address.
update content.gallery_memberships m
set claimant_email = coalesce(
      (
        select nullif(lower(btrim(event.payload ->> 'actor_email')), '')
        from content.audit_log audit
        join content.outbox_events event
          on event.deduplication_key = format('admin_notification:audit:%s', audit.id)
          and event.event_type = 'admin_notification.requested'
        where audit.actor_user_id = m.user_id
          and audit.entity_type = 'gallery'
          and audit.entity_id = m.gallery_id::text
          and audit.action in ('gallery.claim_requested', 'gallery.created_and_claimed')
        order by audit.occurred_at desc, audit.id desc
        limit 1
      ),
      lower(btrim(u.email))
    ),
    claim_email_captured_at = now()
from auth.users u
where m.user_id = u.id and m.status = 'pending'::content.gallery_membership_status
  and m.claim_email_captured_at is null;

create or replace function content_private.queue_gallery_claim_decision()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_event_type text;
  v_recipient_email text;
  v_gallery_name text;
begin
  if old.status <> 'pending'::content.gallery_membership_status
     or new.status not in (
       'active'::content.gallery_membership_status,
       'rejected'::content.gallery_membership_status
     ) then
    return new;
  end if;

  v_recipient_email := new.claimant_email;

  v_event_type := case
    when new.status = 'active'::content.gallery_membership_status
      then 'gallery_claim.accepted'
    else 'gallery_claim.rejected'
  end;
  v_gallery_name := content_private.admin_notification_gallery_name(
    new.gallery_id
  );

  -- The key carries the decision time because a rejected or revoked claimant
  -- may claim again and must receive the next decision too.
  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key,
    max_attempts
  ) values (
    'gallery_membership',
    format('%s:%s', new.gallery_id, new.user_id),
    v_event_type,
    jsonb_build_object(
      'source', 'owner_workspace',
      'recipient_email', v_recipient_email,
      'gallery_name', left(v_gallery_name, 500),
      'review_notes', left(coalesce(new.review_notes, ''), 2000)
    ),
    format(
      'gallery_claim:%s:%s:%s:%s',
      new.gallery_id,
      new.user_id,
      case when new.status = 'active'::content.gallery_membership_status
        then 'accepted' else 'rejected' end,
      to_char(
        coalesce(new.reviewed_at, now()) at time zone 'UTC',
        'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
      )
    ),
    12
  ) on conflict (deduplication_key) do nothing;

  return new;
end;
$$;


create or replace function content_private.owner_submit_exhibition_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_request_id uuid,
  p_submitter_email text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := content_private.owner_assert_authenticated();
  v_gallery_id uuid := content_private.owner_assert_gallery_membership(true);
  v_version content.exhibition_versions%rowtype;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_submission_id uuid := gen_random_uuid();
  v_email text;
  v_response jsonb;
begin
  v_email := lower(btrim(p_submitter_email));
  if v_email is null or length(v_email) > 254 or v_email !~ '^[^[:space:]@<>]+@[^[:space:]@<>]+\.[^[:space:]@<>]+$' then
    raise exception using errcode = '22023', message = 'owner_submission_contact_invalid';
  end if;
  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object(
      'exhibition_id', p_exhibition_id,
      'version_id', p_expected_version_id,
      'revision', p_expected_revision,
      'submitter_email', v_email
    )
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'owner_submit_exhibition', v_fingerprint
  ) as request;
  if v_is_replay then return v_stored; end if;

  v_version := content_private.owner_assert_exhibition_draft(
    p_exhibition_id, p_expected_version_id, p_expected_revision
  );
  if not exists (
    select 1 from content.exhibitions
    where id = p_exhibition_id and gallery_id = v_gallery_id
  ) then
    raise exception using errcode = '42501', message = 'owner_exhibition_access_denied';
  end if;
  if nullif(btrim(v_version.name_ko), '') is null
     or nullif(btrim(v_version.venue_name_ko), '') is null
     or nullif(btrim(v_version.city_ko), '') is null
     or nullif(btrim(v_version.region_ko), '') is null
     or nullif(btrim(v_version.address_ko), '') is null
     or v_version.opening_date is null
     or v_version.closing_date is null
     or v_version.closing_date < v_version.opening_date
     or nullif(btrim(v_version.hours), '') is null then
    raise exception using errcode = '23514', message = 'owner_submission_incomplete';
  end if;
  if not exists (
    select 1
    from content.exhibition_version_media as attachment
    join content.media_assets as asset on asset.id = attachment.media_id
    where attachment.version_id = v_version.id
      and attachment.role = 'cover'::content.media_role
      and asset.status in (
        'ready'::content.media_asset_status,
        'published'::content.media_asset_status
      )
  ) then
    raise exception using errcode = '23514', message = 'owner_submission_cover_required';
  end if;

  insert into content.exhibition_submissions (
    id, status, submitter_email, payload, source, owner_exhibition_id, submitted_at
  ) values (
    v_submission_id, 'submitted', lower(v_email),
    jsonb_build_object(
      'name_ko', v_version.name_ko,
      'name_en', v_version.name_en,
      'venue_name_ko', v_version.venue_name_ko,
      'venue_name_en', v_version.venue_name_en,
      'opening_date', to_char(v_version.opening_date, 'YYYY-MM-DD'),
      'closing_date', to_char(v_version.closing_date, 'YYYY-MM-DD'),
      'address_ko', v_version.address_ko,
      'address_en', v_version.address_en,
      'hours', coalesce(v_version.hours, ''),
      'description_ko', v_version.description_ko,
      'description_en', v_version.description_en,
      'reception_date', '',
      'reception_end', '',
      'version_id', v_version.id,
      'revision', v_version.revision
    ),
    'owner_workspace', p_exhibition_id, now()
  );
  update content.exhibitions
  set
    owner_status = 'submitted',
    owner_review_notes = null,
    owner_status_changed_at = now(),
    updated_by = v_user_id
  where id = p_exhibition_id;
  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, request_id, metadata
  ) values (
    v_user_id, 'owner_exhibition.submitted', 'exhibition', p_exhibition_id,
    p_request_id,
    jsonb_build_object(
      'gallery_id', v_gallery_id,
      'version_id', v_version.id,
      'revision', v_version.revision,
      'submission_id', v_submission_id
    )
  );
  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key
  ) values (
    'exhibition', p_exhibition_id, 'owner_exhibition.submitted',
    jsonb_build_object(
      'exhibition_id', p_exhibition_id,
      'gallery_id', v_gallery_id,
      'submission_id', v_submission_id
    ),
    format('owner_exhibition:%s:submitted:%s', p_exhibition_id, p_request_id)
  );
  v_response := content_private.owner_exhibition_json(
    p_exhibition_id, v_version.id
  );
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'owner_submit_exhibition', v_fingerprint, v_response
  );
end;
$$;

revoke all on function content_private.owner_submit_exhibition_impl(text, uuid, integer, uuid, text)
  from public, anon, authenticated, service_role;
grant execute on function content_private.owner_submit_exhibition_impl(text, uuid, integer, uuid, text) to authenticated;

-- The four-argument command remains available to existing clients. Only this
-- overload accepts an entered contact, whose normalized value is fingerprinted.
create or replace function public.owner_submit_exhibition(
  p_exhibition_id text, p_expected_version_id uuid,
  p_expected_revision integer, p_request_id uuid, p_submitter_email text
)
returns jsonb language sql volatile security invoker set search_path = '' as $$
  select content_private.owner_submit_exhibition_impl(
    p_exhibition_id, p_expected_version_id, p_expected_revision, p_request_id, p_submitter_email
  );
$$;
revoke all on function public.owner_submit_exhibition(text, uuid, integer, uuid, text)
  from public, anon, authenticated, service_role;
grant execute on function public.owner_submit_exhibition(text, uuid, integer, uuid, text) to authenticated;
