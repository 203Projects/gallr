-- Verified individuals submit to the existing private, staff-reviewed queue.
-- No Gallery membership or canonical publication is created by intake.
alter table content.exhibition_submissions
  add column if not exists submitter_user_id uuid references auth.users(id) on delete set null;
comment on column content.exhibition_submissions.submitter_user_id is
  'Verified individual Auth identity. Contact email stays private. Intake uses an account-derived rate bucket rather than trusting a caller-supplied IP.';

create or replace function content_private.submit_individual_exhibition_impl(
  p_payload jsonb, p_request_id uuid
) returns jsonb
language plpgsql volatile security definer set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_email text;
  v_fingerprint text;
  v_replay record;
  v_response jsonb;
  v_submission_id uuid := extensions.gen_random_uuid();
begin
  select email into v_email from auth.users
  where id = v_actor and email_confirmed_at is not null
    and not coalesce(is_anonymous, false);
  if v_actor is null or v_email is null then
    raise exception using errcode = '42501', message = 'verified_email_required';
  end if;
  if p_payload is null or jsonb_typeof(p_payload) <> 'object'
     or octet_length(p_payload::text) > 65536 then
    raise exception using errcode = '22023', message = 'individual_submission_payload_invalid';
  end if;
  if exists (
    select 1 from jsonb_each(p_payload) as field
    where field.key not in ('name_ko','venue_name_ko','address_ko','hours',
                           'opening_date','closing_date','description_ko')
       or jsonb_typeof(field.value) <> 'string'
  ) or length(coalesce(p_payload->>'description_ko','')) > 5000
     or coalesce(p_payload->>'opening_date','') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
     or coalesce(p_payload->>'closing_date','') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then
    raise exception using errcode = '22023', message = 'individual_submission_payload_invalid';
  end if;
  v_fingerprint := content_private.command_request_fingerprint(p_payload);
  select * into v_replay from content_private.begin_command_request(
    v_actor, p_request_id, 'individual.submit_exhibition', v_fingerprint
  );
  if v_replay.is_replay then return v_replay.stored_response; end if;
  v_response := content_private.create_exhibition_submission_impl(
    v_submission_id, v_email, p_payload,
    encode(extensions.digest(convert_to('individual:' || v_actor::text, 'UTF8'), 'sha256'), 'hex'),
    'verified-individual-form', '[]'::jsonb
  );
  update content.exhibition_submissions
    set submitter_user_id = v_actor where id = v_submission_id;
  insert into content.audit_log(actor_user_id,action,entity_type,entity_id,metadata)
    values (v_actor,'individual.exhibition_submitted','exhibition_submission',v_submission_id::text,'{}');
  return content_private.complete_command_request(
    v_actor,p_request_id,'individual.submit_exhibition',v_fingerprint,v_response
  );
end;
$$;
revoke all on function content_private.submit_individual_exhibition_impl(jsonb,uuid)
  from public,anon,authenticated,service_role;
grant execute on function content_private.submit_individual_exhibition_impl(jsonb,uuid) to authenticated;

create or replace function public.submit_individual_exhibition(p_payload jsonb,p_request_id uuid)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.submit_individual_exhibition_impl(p_payload,p_request_id); $$;
revoke all on function public.submit_individual_exhibition(jsonb,uuid)
  from public,anon,authenticated,service_role;
grant execute on function public.submit_individual_exhibition(jsonb,uuid) to authenticated;
