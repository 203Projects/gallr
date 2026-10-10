-- Spec 089 public routes (US8): staff review of listing requests and reader reports.
--
-- A decision applies only to the version staff reviewed: decide_route_listing takes the revision (updated_at) Admin
-- displayed and raises route_listing_stale when the author changed the route since (R11). Upholding reports takes the
-- route off the list (removed), also when the author withdrew it first; dismissing keeps it. All functions require
-- the publisher staff tier, and every decision writes a content.audit_log row (route_listing_approved,
-- route_listing_declined, route_reports_resolved). An approval records the author name staff saw.
begin;

-- 089 moderation read, extended with listing, copy and report fields.
create or replace function content_private.get_route_for_moderation_impl(p_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_route jsonb;
begin
  perform content_private.require_route_staff('publisher'::content.staff_role);
  v_route := content_private.personal_route_json(p_id);
  if v_route is null then
    return null;
  end if;
  return v_route || (
    select jsonb_build_object(
      'author_display_name', coalesce(nullif(btrim(profile.display_name), ''), ''),
      'listing_state', route.listing_state,
      'listing_requested_at', route.listing_requested_at,
      'listing_decided_at', route.listing_decided_at,
      'listing_last_approved_at', route.listing_last_approved_at,
      'listing_decline_reason', route.listing_decline_reason,
      'listing_decline_note', route.listing_decline_note,
      'copy_count', (select count(*) from public.route_saves as copy where copy.route_id = route.id),
      'open_report_count', (
        select count(*) from public.route_reports as report
        where report.route_id = route.id and report.resolved_at is null
      )
    )
    from public.personal_routes as route
    left join public.profiles as profile on profile.id = route.owner
    where route.id = p_id
  );
end;
$$;

create or replace function content_private.list_route_listing_queue_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform content_private.require_route_staff('publisher'::content.staff_role);
  return (
    select coalesce(
      jsonb_agg(
        jsonb_build_object(
          'id', route.id,
          'name', route.name,
          'author_display_name', coalesce(nullif(btrim(profile.display_name), ''), ''),
          'requested_at', route.listing_requested_at,
          'stop_count', (select count(*) from public.personal_route_stops as stop where stop.route_id = route.id),
          'was_approved_before', route.listing_last_approved_at is not null,
          'revision', route.updated_at
        )
        order by route.listing_requested_at asc nulls last, route.id
      ),
      '[]'::jsonb
    )
    from public.personal_routes as route
    left join public.profiles as profile on profile.id = route.owner
    where route.listing_state = 'requested'
  );
end;
$$;

create or replace function content_private.decide_route_listing_impl(
  p_id uuid,
  p_decision text,
  p_reason text,
  p_note text,
  p_expected_revision timestamptz
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
    update public.personal_routes
    set listing_state = 'approved', listing_decided_at = v_now, listing_decided_by = v_actor,
        listing_last_approved_at = v_now, listing_author_name = content_private.route_author_display_name(p_id),
        listing_decline_reason = null, listing_decline_note = null
    where id = p_id;
    perform content_private.record_route_audit(
      v_actor,
      'route_listing_approved',
      p_id,
      jsonb_build_object(
        'listing_state_before', v_route.listing_state,
        'listing_state_after', 'approved',
        'revision', p_expected_revision
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

create or replace function content_private.list_reported_routes_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform content_private.require_route_staff('publisher'::content.staff_role);
  return (
    select coalesce(jsonb_agg(reported.route_json order by reported.open_count desc, reported.first_reported_at), '[]'::jsonb)
    from (
      select
        count(*) as open_count,
        min(report.created_at) as first_reported_at,
        jsonb_build_object(
          'id', route.id,
          'name', route.name,
          'listing_state', route.listing_state,
          'open_count', count(*),
          'first_reported_at', min(report.created_at),
          'reasons', (
            select jsonb_object_agg(tally.reason, tally.total)
            from (
              select open_report.reason, count(*) as total
              from public.route_reports as open_report
              where open_report.route_id = route.id and open_report.resolved_at is null
              group by open_report.reason
            ) as tally
          )
        ) as route_json
      from public.route_reports as report
      join public.personal_routes as route on route.id = report.route_id
      where report.resolved_at is null
      group by route.id
    ) as reported
  );
end;
$$;

create or replace function content_private.resolve_route_reports_impl(p_id uuid, p_resolution text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_route public.personal_routes%rowtype := content_private.staff_route_for_update(p_id);
  v_actor uuid := auth.uid();
  v_resolved integer;
  v_state_after text := v_route.listing_state;
begin
  if p_resolution is null or p_resolution not in ('dismissed', 'upheld') then
    raise exception using errcode = '22023', message = 'route_report_invalid_resolution';
  end if;
  update public.route_reports
  set resolved_at = clock_timestamp(), resolution = p_resolution
  where route_id = p_id and resolved_at is null;
  get diagnostics v_resolved = row_count;
  -- An upheld report removes the route from any state but removed, withdrawn (unlisted) included.
  if p_resolution = 'upheld' and v_route.listing_state <> 'removed' then
    v_state_after := 'removed';
    update public.personal_routes
    set listing_state = 'removed', listing_decided_by = v_actor,
        listing_decline_reason = null, listing_decline_note = null
    where id = p_id;
  end if;
  perform content_private.record_route_audit(
    v_actor,
    'route_reports_resolved',
    p_id,
    jsonb_build_object(
      'resolution', p_resolution,
      'resolved_count', v_resolved,
      'listing_state_before', v_route.listing_state,
      'listing_state_after', v_state_after
    )
  );
  return content_private.get_route_for_moderation_impl(p_id);
end;
$$;

revoke all on function content_private.get_route_for_moderation_impl(uuid) from public, anon, authenticated;
revoke all on function content_private.list_route_listing_queue_impl() from public, anon, authenticated;
revoke all on function content_private.decide_route_listing_impl(uuid, text, text, text, timestamptz)
  from public, anon, authenticated;
revoke all on function content_private.list_reported_routes_impl() from public, anon, authenticated;
revoke all on function content_private.resolve_route_reports_impl(uuid, text) from public, anon, authenticated;
grant execute on function content_private.get_route_for_moderation_impl(uuid) to authenticated;
grant execute on function content_private.list_route_listing_queue_impl() to authenticated;
grant execute on function content_private.decide_route_listing_impl(uuid, text, text, text, timestamptz) to authenticated;
grant execute on function content_private.list_reported_routes_impl() to authenticated;
grant execute on function content_private.resolve_route_reports_impl(uuid, text) to authenticated;

create or replace function public.list_route_listing_queue()
returns jsonb language sql stable security invoker set search_path = ''
as $$ select content_private.list_route_listing_queue_impl(); $$;

create or replace function public.decide_route_listing(
  p_id uuid, p_decision text, p_reason text, p_note text, p_expected_revision timestamptz
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.decide_route_listing_impl(p_id, p_decision, p_reason, p_note, p_expected_revision); $$;

create or replace function public.list_reported_routes()
returns jsonb language sql stable security invoker set search_path = ''
as $$ select content_private.list_reported_routes_impl(); $$;

create or replace function public.resolve_route_reports(p_id uuid, p_resolution text)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.resolve_route_reports_impl(p_id, p_resolution); $$;

revoke all on function public.list_route_listing_queue() from public, anon, authenticated;
revoke all on function public.decide_route_listing(uuid, text, text, text, timestamptz) from public, anon, authenticated;
revoke all on function public.list_reported_routes() from public, anon, authenticated;
revoke all on function public.resolve_route_reports(uuid, text) from public, anon, authenticated;
grant execute on function public.list_route_listing_queue() to authenticated;
grant execute on function public.decide_route_listing(uuid, text, text, text, timestamptz) to authenticated;
grant execute on function public.list_reported_routes() to authenticated;
grant execute on function public.resolve_route_reports(uuid, text) to authenticated;

commit;
