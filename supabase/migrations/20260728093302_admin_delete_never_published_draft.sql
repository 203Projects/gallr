-- Permanently remove accidental identities that have never entered the
-- editorial/publication lifecycle. Published or related records remain
-- archive-only. The command is administrator-only, concurrency checked, and
-- idempotent.

create or replace function content_private.admin_delete_exhibition_draft_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_exhibition content.exhibitions%rowtype;
  v_version content.exhibition_versions%rowtype;
  v_response jsonb;
begin
  v_user_id := content_private.admin_assert_staff(
    'admin'::content.staff_role
  );

  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'exhibition_not_found';
  end if;

  if v_exhibition.archived_at is not null
     or v_exhibition.published_version_id is not null
     or exists (
       select 1
       from content.exhibition_versions as historical_version
       where historical_version.exhibition_id = p_exhibition_id
         and historical_version.status = 'published'::content.exhibition_version_status
     ) then
    raise exception using
      errcode = '22023',
      message = 'only_never_published_drafts_can_be_deleted';
  end if;

  select version.*
  into v_version
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and version.status = 'draft'::content.exhibition_version_status
  order by version.version_number desc
  limit 1
  for update;

  if not found or v_version.id is distinct from p_expected_version_id then
    raise exception using errcode = 'P0002', message = 'working_version_not_found';
  end if;
  if p_expected_revision is null or v_version.revision <> p_expected_revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_version.revision::text;
  end if;

  if exists (
    select 1
    from content.exhibition_version_media as attachment
    where attachment.version_id = v_version.id
  ) then
    raise exception using
      errcode = '23503',
      message = 'draft_delete_requires_media_detach';
  end if;

  if exists (
    select 1
    from content.legacy_import_links as import_link
    where import_link.exhibition_id = p_exhibition_id
  ) then
    raise exception using
      errcode = '23503',
      message = 'imported_exhibitions_cannot_be_deleted';
  end if;

  if exists (
    select 1
    from content.exhibition_submissions as submission
    where submission.accepted_exhibition_id = p_exhibition_id
  ) then
    raise exception using
      errcode = '23503',
      message = 'draft_delete_has_submission_reference';
  end if;

  if exists (
    select 1
    from content.curation_placements as placement
    where placement.exhibition_id = p_exhibition_id
  ) then
    raise exception using
      errcode = '23503',
      message = 'draft_delete_has_curation_reference';
  end if;

  if exists (
    select 1
    from content.outbox_events as event
    where event.aggregate_type = 'exhibition'
      and event.aggregate_id = p_exhibition_id
  ) then
    raise exception using
      errcode = '23503',
      message = 'draft_delete_has_outbox_reference';
  end if;

  v_response := jsonb_build_object(
    'id', p_exhibition_id,
    'working_version_id', v_version.id,
    'revision', v_version.revision,
    'status', 'deleted'
  );

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'exhibition.draft_deleted',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object(
      'working_version_id', v_version.id,
      'version_number', v_version.version_number,
      'revision', v_version.revision,
      'name_ko', v_version.name_ko
    )
  );

  delete from content.exhibition_versions
  where id = v_version.id
    and exhibition_id = p_exhibition_id;

  delete from content.exhibitions
  where id = p_exhibition_id;

  return v_response;
end;
$$;

revoke all on function content_private.admin_delete_exhibition_draft_impl(text, uuid, integer)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_delete_exhibition_draft_idempotent_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor_user_id uuid;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored_response jsonb;
  v_response jsonb;
  v_parameters jsonb;
begin
  v_actor_user_id := content_private.admin_assert_staff(
    'admin'::content.staff_role
  );

  if p_request_id is null then
    raise exception using errcode = '22023', message = 'request_id_is_required';
  end if;

  v_parameters := jsonb_build_object(
    'exhibition_id', p_exhibition_id,
    'expected_version_id', p_expected_version_id,
    'expected_revision', p_expected_revision
  );
  v_fingerprint := content_private.command_request_fingerprint(v_parameters);

  select request.is_replay, request.stored_response
  into v_is_replay, v_stored_response
  from content_private.begin_command_request(
    v_actor_user_id,
    p_request_id,
    'admin_delete_exhibition_draft',
    v_fingerprint
  ) as request;

  if v_is_replay then
    return v_stored_response;
  end if;

  perform set_config('app.command_request_id', p_request_id::text, true);
  perform set_config('app.command_actor_id', v_actor_user_id::text, true);

  v_response := content_private.admin_delete_exhibition_draft_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );

  perform set_config('app.command_request_id', '', true);
  perform set_config('app.command_actor_id', '', true);

  return content_private.complete_command_request(
    v_actor_user_id,
    p_request_id,
    'admin_delete_exhibition_draft',
    v_fingerprint,
    v_response
  );
end;
$$;

revoke all on function content_private.admin_delete_exhibition_draft_idempotent_impl(text, uuid, integer, uuid)
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_delete_exhibition_draft_idempotent_impl(text, uuid, integer, uuid)
  to authenticated;

create or replace function public.admin_delete_exhibition_draft(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_delete_exhibition_draft_idempotent_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_request_id
  );
$$;

revoke all on function public.admin_delete_exhibition_draft(text, uuid, integer, uuid)
  from public, anon, authenticated, service_role;
grant execute on function public.admin_delete_exhibition_draft(text, uuid, integer, uuid)
  to authenticated;

comment on function public.admin_delete_exhibition_draft(text, uuid, integer, uuid) is
  'Admin-only idempotent permanent deletion for active identities that have never been published and have no retained relationships.';
