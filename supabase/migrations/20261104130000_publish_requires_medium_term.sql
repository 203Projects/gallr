-- Spec 101 FR-019a (owner decision 2026-11-04): terms are set when an exhibition is added, not in a later pass.
-- Admin's publish command now refuses a version without a medium term. The rule sits on the staff publish command
-- only: approving an editor's curation republishes through admin_publish_exhibition_impl directly and is not
-- blocked by an exhibition tagged before this rule. Accepted gallery-owner submissions are published through this
-- command, so the terms an owner proposed are confirmed in the same review.
begin;

create or replace function content_private.admin_lifecycle_idempotent_impl(
  p_command_name text,
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
  if p_command_name not in (
    'admin_publish_exhibition',
    'admin_archive_exhibition',
    'admin_restore_exhibition'
  ) then
    raise exception using errcode = '22023', message = 'unsupported_lifecycle_command';
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
    p_command_name,
    v_fingerprint
  ) as request;

  if v_is_replay then
    return v_stored_response;
  end if;

  perform set_config('app.command_request_id', p_request_id::text, true);
  perform set_config('app.command_actor_id', v_actor_user_id::text, true);

  case p_command_name
    when 'admin_publish_exhibition' then
      v_response := content_private.admin_publish_exhibition_impl(
        p_exhibition_id,
        p_expected_version_id,
        p_expected_revision
      );
    when 'admin_archive_exhibition' then
      v_response := content_private.admin_archive_exhibition_impl(
        p_exhibition_id,
        p_expected_version_id,
        p_expected_revision
      );
    when 'admin_restore_exhibition' then
      v_response := content_private.admin_restore_exhibition_impl(
        p_exhibition_id,
        p_expected_version_id,
        p_expected_revision
      );
  end case;

  -- Spec 101 FR-019a: an exhibition is published with at least one medium term. The check runs after the
  -- publish so the existing refusals (missing draft, revision conflict, required fields) keep their order; raising
  -- here rolls the publish and its command record back, so the same request id can be retried once a term is set.
  if p_command_name = 'admin_publish_exhibition'
     and not exists (
       select 1
       from content.exhibition_version_terms as link
       join content.art_taxonomy_terms as term on term.id = link.term_id
       where link.version_id = p_expected_version_id
         and term.category = 'medium'::content.art_taxonomy_category
     ) then
    raise exception using
      errcode = '23514',
      message = 'medium_term_is_required';
  end if;

  perform set_config('app.command_request_id', '', true);
  perform set_config('app.command_actor_id', '', true);

  return content_private.complete_command_request(
    v_actor_user_id,
    p_request_id,
    p_command_name,
    v_fingerprint,
    v_response
  );
end;
$$;

revoke all on function content_private.admin_lifecycle_idempotent_impl(text, text, uuid, integer, uuid)
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_lifecycle_idempotent_impl(text, text, uuid, integer, uuid)
  to authenticated;

commit;
