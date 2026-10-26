-- Report decisions bind both the report version and the route staff reviewed.
begin;
-- Keep legacy signatures present for explicit compatibility failure, never for mutations.
create or replace function content_private.resolve_route_reports_impl(p_id uuid, p_resolution text)
returns jsonb language plpgsql volatile security definer set search_path = '' as $$
begin
  perform content_private.require_route_staff('publisher'::content.staff_role);
  raise exception using errcode = '40001', message = 'route_listing_stale';
end;
$$;
revoke all on function content_private.resolve_route_reports_impl(uuid,text) from public,anon,authenticated;
revoke all on function public.resolve_route_reports(uuid,text) from public,anon,authenticated;

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
          'approved_at', report.approved_at,
          'revision', route.updated_at,
          'open_count', count(*),
          'first_reported_at', min(report.created_at),
          'reasons', (
            select jsonb_object_agg(tally.reason, tally.total)
            from (
              select open_report.reason, count(*) as total
              from public.route_reports as open_report
              where open_report.route_id = route.id and open_report.approved_at = report.approved_at and open_report.resolved_at is null
              group by open_report.reason
            ) as tally
          )
        ) as route_json
      from public.route_reports as report
      join public.personal_routes as route on route.id = report.route_id
      where report.resolved_at is null
      group by route.id, report.approved_at
    ) as reported
  );
end;
$$;

create or replace function content_private.resolve_route_reports_impl(p_id uuid, p_resolution text, p_expected_revision timestamptz, p_approved_at timestamptz)
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
  if p_expected_revision is null or p_approved_at is null
     or v_route.updated_at is distinct from p_expected_revision
     or (p_resolution = 'upheld' and (
       v_route.listing_last_approved_at is distinct from p_approved_at
       or v_route.listing_state not in ('approved', 'unlisted', 'removed')
     )) then
    raise exception using errcode = '40001', message = 'route_listing_stale';
  end if;
  update public.route_reports
  set resolved_at = clock_timestamp(), resolution = p_resolution
  where route_id = p_id and approved_at = p_approved_at and resolved_at is null;
  get diagnostics v_resolved = row_count;
  -- An upheld report removes the route from any state but removed, withdrawn (unlisted) included.
  if v_resolved > 0 and p_resolution = 'upheld' and v_route.listing_state <> 'removed' then
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
      'approved_at', p_approved_at,
      'listing_state_before', v_route.listing_state,
      'listing_state_after', v_state_after
    )
  );
  return content_private.get_route_for_moderation_impl(p_id);
end;
$$;

revoke all on function content_private.resolve_route_reports_impl(uuid,text,timestamptz,timestamptz) from public,anon,authenticated;
grant execute on function content_private.resolve_route_reports_impl(uuid,text,timestamptz,timestamptz) to authenticated;
create or replace function public.resolve_route_reports(p_id uuid,p_resolution text,p_expected_revision timestamptz,p_approved_at timestamptz)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.resolve_route_reports_impl(p_id,p_resolution,p_expected_revision,p_approved_at); $$;
revoke all on function public.resolve_route_reports(uuid,text,timestamptz,timestamptz) from public,anon,authenticated;
grant execute on function public.resolve_route_reports(uuid,text,timestamptz,timestamptz) to authenticated;
commit;
