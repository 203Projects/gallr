-- Spec 089 US8: an approval is made against the author name staff saw. Admin sends the display name it showed, and
-- decide_route_listing raises route_author_changed when the author renamed their profile since, so a name nobody
-- reviewed never reaches the public list. The approved name is also written to the approval's audit row.
--
-- The function gains a sixth argument, so the five-argument forms recorded by 20261010160000 are dropped first;
-- otherwise both overloads would stay callable and the old one would skip the check. Deploy the Admin that sends
-- the name together with this migration: the Admin before it calls the five-argument form.
begin;

drop function if exists public.decide_route_listing(uuid, text, text, text, timestamptz);
drop function if exists content_private.decide_route_listing_impl(uuid, text, text, text, timestamptz);

create or replace function content_private.decide_route_listing_impl(
  p_id uuid,
  p_decision text,
  p_reason text,
  p_note text,
  p_expected_revision timestamptz,
  p_expected_author_name text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_route public.personal_routes%rowtype := content_private.staff_route_for_update(p_id);
  v_actor uuid := auth.uid();
  v_now timestamptz := clock_timestamp();
  v_note text := nullif(btrim(coalesce(p_note, '')), '');
  v_author_name text := content_private.route_author_display_name(p_id);
begin
  if p_decision is null or p_decision not in ('approve', 'decline') then
    raise exception using errcode = '22023', message = 'route_listing_invalid_decision';
  end if;
  if v_route.listing_state <> 'requested' then
    raise sqlstate 'PT409' using message = 'route_listing_invalid_transition';
  end if;
  if p_expected_revision is null or v_route.updated_at <> p_expected_revision then
    raise sqlstate 'PT409' using message = 'route_listing_stale';
  end if;
  if p_decision = 'approve' then
    if coalesce(p_expected_author_name, '') <> v_author_name then
      raise sqlstate 'PT409' using message = 'route_author_changed';
    end if;
    update public.personal_routes
    set listing_state = 'approved', listing_decided_at = v_now, listing_decided_by = v_actor,
        listing_last_approved_at = v_now, listing_author_name = v_author_name,
        listing_decline_reason = null, listing_decline_note = null
    where id = p_id;
    perform content_private.record_route_audit(
      v_actor,
      'route_listing_approved',
      p_id,
      jsonb_build_object(
        'listing_state_before', v_route.listing_state,
        'listing_state_after', 'approved',
        'revision', p_expected_revision,
        'author_name', v_author_name
      )
    );
  else
    if p_reason is null or p_reason not in ('name_or_description', 'promotional', 'composition', 'other') then
      raise exception using errcode = '22023', message = 'route_listing_invalid_reason';
    end if;
    if char_length(coalesce(v_note, '')) > 500 then
      raise exception using errcode = '22023', message = 'route_listing_invalid_note';
    end if;
    update public.personal_routes
    set listing_state = 'declined', listing_decided_at = v_now, listing_decided_by = v_actor,
        listing_decline_reason = p_reason, listing_decline_note = v_note
    where id = p_id;
    perform content_private.record_route_audit(
      v_actor,
      'route_listing_declined',
      p_id,
      jsonb_build_object(
        'listing_state_before', v_route.listing_state,
        'listing_state_after', 'declined',
        'revision', p_expected_revision,
        'reason', p_reason
      )
    );
  end if;
  return content_private.get_route_for_moderation_impl(p_id);
end;
$$;

revoke all on function content_private.decide_route_listing_impl(uuid, text, text, text, timestamptz, text)
  from public, anon, authenticated;
grant execute on function content_private.decide_route_listing_impl(uuid, text, text, text, timestamptz, text) to authenticated;

create or replace function public.decide_route_listing(
  p_id uuid, p_decision text, p_reason text, p_note text, p_expected_revision timestamptz, p_expected_author_name text
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$
  select content_private.decide_route_listing_impl(p_id, p_decision, p_reason, p_note, p_expected_revision, p_expected_author_name);
$$;

revoke all on function public.decide_route_listing(uuid, text, text, text, timestamptz, text) from public, anon, authenticated;
grant execute on function public.decide_route_listing(uuid, text, text, text, timestamptz, text) to authenticated;

commit;
