-- Translate the Admin draft save API's logical optimistic-lock conflict at the
-- public PostgREST boundary. The private implementation retains SQLSTATE 40001
-- for its internal transaction contract; every other 40001 and every
-- authorization/validation error passes through unchanged.

begin;

create or replace function public.admin_save_exhibition_draft(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_patch jsonb
)
returns jsonb
language plpgsql
volatile
security invoker
set search_path = ''
as $function$
declare
  v_result jsonb;
  v_message text;
  v_detail text;
begin
  perform set_config('content.admin_credits_active', 'true', true);
  perform set_config(
    'content.admin_credits_has_ko',
    (coalesce(p_patch, '{}'::jsonb) ? 'credits_ko')::text,
    true
  );
  perform set_config(
    'content.admin_credits_has_en',
    (coalesce(p_patch, '{}'::jsonb) ? 'credits_en')::text,
    true
  );
  perform set_config('content.admin_credits_ko', coalesce(p_patch ->> 'credits_ko', ''), true);
  perform set_config('content.admin_credits_en', coalesce(p_patch ->> 'credits_en', ''), true);
  perform set_config('content.admin_reception_end_active', 'true', true);
  perform set_config(
    'content.admin_reception_end_has_value',
    (coalesce(p_patch, '{}'::jsonb) ? 'reception_end_time')::text,
    true
  );
  perform set_config(
    'content.admin_reception_end_value',
    coalesce(p_patch ->> 'reception_end_time', ''),
    true
  );

  v_result := content_private.admin_save_exhibition_draft_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_patch
  );

  perform set_config('content.admin_credits_active', 'false', true);
  perform set_config('content.admin_reception_end_active', 'false', true);
  return v_result;
exception
  when sqlstate '40001' then
    get stacked diagnostics
      v_message = message_text,
      v_detail = pg_exception_detail;

    if v_message = 'revision_conflict' then
      raise sqlstate 'PT409' using
        message = v_message,
        detail = v_detail;
    end if;

    raise;
end;
$function$;

revoke all on function public.admin_save_exhibition_draft(
  text, uuid, integer, jsonb
) from public, anon, authenticated, service_role;
grant execute on function public.admin_save_exhibition_draft(
  text, uuid, integer, jsonb
) to authenticated;

comment on function public.admin_save_exhibition_draft(
  text, uuid, integer, jsonb
) is
  'Saves one revision-checked Admin draft and exposes logical revision conflicts as PostgREST HTTP 409 without reclassifying transaction or authorization failures.';

commit;
