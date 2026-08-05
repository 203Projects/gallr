-- Let publishers abandon an accidental working draft without changing the
-- public snapshot. The command deletes only the expected draft version and
-- returns the already-published version to the Admin editor.

create or replace function content_private.admin_discard_exhibition_draft_impl(
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
  v_draft content.exhibition_versions%rowtype;
  v_attachment_count integer;
begin
  v_user_id := content_private.admin_assert_staff(
    'publisher'::content.staff_role
  );

  if p_expected_version_id is null then
    raise exception using
      errcode = '22023',
      message = 'expected_version_id_is_required';
  end if;
  if p_expected_revision is null or p_expected_revision < 1 then
    raise exception using
      errcode = '22023',
      message = 'expected_revision_must_be_positive';
  end if;

  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'exhibition_not_found';
  end if;
  if v_exhibition.archived_at is not null then
    raise exception using
      errcode = '22023',
      message = 'restore_exhibition_before_discarding_draft';
  end if;
  if v_exhibition.published_version_id is null then
    raise exception using
      errcode = '22023',
      message = 'published_version_is_required_for_draft_discard';
  end if;
  if v_exhibition.published_version_id = p_expected_version_id then
    raise exception using
      errcode = '22023',
      message = 'no_unpublished_draft_to_discard';
  end if;

  select version.*
  into v_draft
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and version.id = p_expected_version_id
    and version.status = 'draft'::content.exhibition_version_status
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'draft_not_found';
  end if;
  if v_draft.revision <> p_expected_revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_draft.revision::text;
  end if;
  if exists (
    select 1
    from content.legacy_import_links as import_link
    where import_link.last_imported_version_id = v_draft.id
  ) then
    raise exception using
      errcode = '23503',
      message = 'imported_version_cannot_be_discarded';
  end if;

  select count(*)::integer
  into v_attachment_count
  from content.exhibition_version_media as attachment
  where attachment.version_id = v_draft.id;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'exhibition.draft_discarded',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object(
      'discarded_version_id', v_draft.id,
      'discarded_version_number', v_draft.version_number,
      'discarded_revision', v_draft.revision,
      'restored_version_id', v_exhibition.published_version_id,
      'detached_media_count', v_attachment_count
    )
  );

  delete from content.exhibition_versions
  where id = v_draft.id
    and exhibition_id = p_exhibition_id;

  return content_private.admin_exhibition_json(
    p_exhibition_id,
    v_exhibition.published_version_id
  );
end;
$$;

revoke all on function content_private.admin_discard_exhibition_draft_impl(
  text, uuid, integer
) from public, anon, authenticated, service_role;

create or replace function content_private.admin_discard_exhibition_draft_idempotent_impl(
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
    'publisher'::content.staff_role
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
    'admin_discard_exhibition_draft',
    v_fingerprint
  ) as request;

  if v_is_replay then
    return v_stored_response;
  end if;

  perform set_config('app.command_request_id', p_request_id::text, true);
  perform set_config('app.command_actor_id', v_actor_user_id::text, true);

  v_response := content_private.admin_discard_exhibition_draft_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );

  perform set_config('app.command_request_id', '', true);
  perform set_config('app.command_actor_id', '', true);

  return content_private.complete_command_request(
    v_actor_user_id,
    p_request_id,
    'admin_discard_exhibition_draft',
    v_fingerprint,
    v_response
  );
end;
$$;

revoke all on function content_private.admin_discard_exhibition_draft_idempotent_impl(
  text, uuid, integer, uuid
) from public, anon, authenticated, service_role;
grant execute on function content_private.admin_discard_exhibition_draft_idempotent_impl(
  text, uuid, integer, uuid
) to authenticated;

create or replace function public.admin_discard_exhibition_draft(
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
  select content_private.admin_discard_exhibition_draft_idempotent_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_request_id
  );
$$;

revoke all on function public.admin_discard_exhibition_draft(
  text, uuid, integer, uuid
) from public, anon, authenticated, service_role;
grant execute on function public.admin_discard_exhibition_draft(
  text, uuid, integer, uuid
) to authenticated;

comment on function public.admin_discard_exhibition_draft(
  text, uuid, integer, uuid
) is
  'Publisher-only idempotent removal of an unpublished working version when a distinct published snapshot exists.';
