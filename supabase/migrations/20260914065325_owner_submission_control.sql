-- Owner-controlled withdrawal/discard retains immutable review snapshots.
-- Lock reviews before exhibitions, matching staff acceptance/rejection.
create or replace function content_private.owner_change_submission_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_request_id uuid,
  p_discard boolean
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_membership content.gallery_memberships%rowtype :=
    content_private.owner_assert_gallery_membership_record(false);
  v_user_id uuid := content_private.owner_assert_authenticated();
  v_exhibition content.exhibitions%rowtype;
  v_version content.exhibition_versions%rowtype;
  v_submission content.exhibition_submissions%rowtype;
  v_latest_id uuid;
  v_command text;
  v_fingerprint text;
  v_replay boolean;
  v_response jsonb;
begin
  if p_discard is null or p_expected_version_id is null
     or p_expected_revision is null or p_expected_revision < 1 then
    raise exception using errcode = '22023', message = 'owner_revision_required';
  end if;
  if not p_discard then
    perform content_private.owner_assert_gallery_membership(true);
  end if;
  v_command := case when p_discard then 'owner_discard_exhibition' else 'owner_withdraw_exhibition' end;
  v_fingerprint := content_private.command_request_fingerprint(jsonb_build_object(
    'exhibition_id', p_exhibition_id, 'version_id', p_expected_version_id,
    'revision', p_expected_revision
  ));
  select request.is_replay, request.stored_response into v_replay, v_response
  from content_private.begin_command_request(v_user_id, p_request_id, v_command, v_fingerprint) as request;
  if v_replay then return v_response; end if;

  -- Scope before locking so foreign callers cannot contend on another gallery.
  if not exists (
    select 1 from content.exhibitions
    where id = p_exhibition_id and gallery_id = v_membership.gallery_id
      and owner_status is not null and owner_hidden_at is null
      and (v_membership.status = 'active' or created_by = v_user_id)
  ) then
    raise exception using errcode = '42501', message = 'owner_exhibition_access_denied';
  end if;
  -- The unique open round takes precedence over historical timestamp order.
  select submission.* into v_submission
  from content.exhibition_submissions as submission
  where submission.owner_exhibition_id = p_exhibition_id and submission.source = 'owner_workspace'
  order by (submission.status in ('submitted', 'in_review')) desc,
    submission.created_at desc, submission.id desc
  limit 1 for update;

  select exhibition.* into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id for update;
  if v_exhibition.gallery_id is distinct from v_membership.gallery_id
     or v_exhibition.owner_hidden_at is not null
     or v_exhibition.owner_status is null
     or (v_membership.status = 'pending' and v_exhibition.created_by is distinct from v_user_id) then
    raise exception using errcode = '42501', message = 'owner_exhibition_access_denied';
  end if;
  -- A concurrent resubmission may have created a round before we locked the
  -- exhibition. Never withdraw an unobserved round with stale client input.
  select submission.id into v_latest_id
  from content.exhibition_submissions as submission
  where submission.owner_exhibition_id = p_exhibition_id and submission.source = 'owner_workspace'
  order by (submission.status in ('submitted', 'in_review')) desc,
    submission.created_at desc, submission.id desc limit 1;
  if v_latest_id is distinct from v_submission.id then
    raise exception using errcode = '40001', message = 'revision_conflict';
  end if;
  if v_exhibition.published_version_id is not null
     or v_exhibition.owner_status in ('published', 'archived')
     or v_submission.status = 'accepted' then
    raise exception using errcode = '22023', message = 'owner_submission_already_decided';
  end if;
  if (not p_discard and (v_exhibition.owner_status <> 'submitted'
      or v_submission.status is null or v_submission.status not in ('submitted', 'in_review')))
     or v_exhibition.owner_status not in ('draft', 'needs_changes', 'submitted') then
    raise exception using errcode = '40001', message = 'revision_conflict';
  end if;
  select version.* into v_version from content.exhibition_versions as version
  where version.id = p_expected_version_id and version.exhibition_id = p_exhibition_id
    and version.status = 'draft' for update;
  if not found or v_version.revision <> p_expected_revision then
    raise exception using errcode = '40001', message = 'revision_conflict';
  end if;

  if v_submission.status in ('submitted', 'in_review') then
    update content.exhibition_submissions set status = 'withdrawn'
    where id = v_submission.id;
  end if;
  update content.exhibition_versions set revision = revision + 1, updated_by = v_user_id
  where id = v_version.id;
  update content.exhibitions set owner_status = 'draft', owner_review_notes = null,
    owner_status_changed_at = now(), updated_by = v_user_id,
    owner_hidden_at = case when p_discard then now() else null end,
    owner_hidden_by = case when p_discard then v_user_id else null end
  where id = p_exhibition_id;
  insert into content.audit_log(actor_user_id, action, entity_type, entity_id, request_id, metadata)
  values (v_user_id, case when p_discard then 'owner_exhibition.discarded' else 'owner_exhibition.withdrawn' end,
    'exhibition', p_exhibition_id, p_request_id, jsonb_build_object(
      'submission_id', v_submission.id, 'version_id', v_version.id, 'revision', v_version.revision + 1
    ));
  v_response := case when p_discard then jsonb_build_object('id', p_exhibition_id, 'discarded', true)
    else content_private.owner_exhibition_json(p_exhibition_id, v_version.id) end;
  return content_private.complete_command_request(v_user_id, p_request_id, v_command, v_fingerprint, v_response);
end;
$$;

create or replace function public.owner_withdraw_exhibition(
  p_exhibition_id text, p_expected_version_id uuid, p_expected_revision integer, p_request_id uuid
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$
  select content_private.owner_change_submission_impl(
    p_exhibition_id, p_expected_version_id, p_expected_revision, p_request_id, false
  );
$$;

create or replace function public.owner_discard_exhibition(
  p_exhibition_id text, p_expected_version_id uuid, p_expected_revision integer, p_request_id uuid
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$
  select content_private.owner_change_submission_impl(
    p_exhibition_id, p_expected_version_id, p_expected_revision, p_request_id, true
  );
$$;

revoke all on function content_private.owner_change_submission_impl(text,uuid,integer,uuid,boolean)
  from public, anon, authenticated, service_role;
grant execute on function content_private.owner_change_submission_impl(text,uuid,integer,uuid,boolean) to authenticated;
revoke all on function public.owner_withdraw_exhibition(text,uuid,integer,uuid),
  public.owner_discard_exhibition(text,uuid,integer,uuid) from public, anon, authenticated, service_role;
grant execute on function public.owner_withdraw_exhibition(text,uuid,integer,uuid),
  public.owner_discard_exhibition(text,uuid,integer,uuid) to authenticated;

comment on function public.owner_withdraw_exhibition(text,uuid,integer,uuid) is
  'Withdraw an open caller-gallery review and return the retained editable draft; revision-checked with request replay.';
comment on function public.owner_discard_exhibition(text,uuid,integer,uuid) is
  'Withdraw open review and soft-hide an unpublished caller-gallery draft, retaining audit and media history.';
