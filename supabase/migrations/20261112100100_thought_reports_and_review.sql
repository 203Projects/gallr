-- Spec 103 M2: reader reports on 감상, the 1.16 reader list, and the staff review behind Admin's 감상 section
-- (US4, US5; FR-023, FR-026; owner decisions 6, 8 and 9).
--
-- Reports: one per signed-in account per approved text, fixed reasons, never on the reporter's own 감상. A report
-- hides the 감상 from its reporter only, and only while it is open and about the text that is public now: a report on
-- text the author has since replaced no longer describes anything public, so it neither hides the new text nor puts it
-- on the reported list (it is closed with the next resolution of that 감상).
--
-- Staff: every function requires the publisher tier (admins included) and raises the token Admin maps
-- (admin/src/repositories/SupabaseAdminThoughtRepository.ts) as the exception message. Each decision is one write to
-- public.thoughts made with gallr.thought_moderation set right before it and cleared right after, so the M1 trigger
-- (20261112100000) accepts it only as a decision on another person's 감상; a staff member's own 감상 is refused with
-- thought_review_invalid_transition. Every decision writes a content.audit_log row without the 감상's text. No function
-- here reads content_private.my_gallr_visits, so no feeling, tag, visit date or private note reaches staff (FR-026).

-- ── Approval history for Admin's "edited, asked again" label ────────────────────────────────────────────────────
-- M1 clears approved_at when the text changes; last_approved_at keeps the last approval so the review queue can say
-- the 감상 was approved before. A second BEFORE trigger, firing after thoughts_enforce_moderation (alphabetical
-- order), keeps it: nobody writes it directly.

alter table public.thoughts add column if not exists last_approved_at timestamptz;

comment on column public.thoughts.last_approved_at is
  'The last time any text of this 감상 was approved; kept by thoughts_track_last_approval, never written directly.';

drop trigger if exists thoughts_track_last_approval on public.thoughts;

update public.thoughts
set last_approved_at = approved_at
where moderation_status = 'approved'
  and approved_at is not null
  and last_approved_at is null;

create or replace function content_private.track_thought_last_approval()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.moderation_status = 'approved'
    and (
      tg_op = 'INSERT'
      or old.moderation_status is distinct from 'approved'
      or new.approved_at is distinct from old.approved_at
    )
  then
    new.last_approved_at := coalesce(new.approved_at, now());
  elsif tg_op = 'INSERT' then
    new.last_approved_at := null;
  else
    new.last_approved_at := old.last_approved_at;
  end if;
  return new;
end;
$$;

revoke all on function content_private.track_thought_last_approval() from public, anon, authenticated;

create trigger thoughts_track_last_approval
  before insert or update on public.thoughts
  for each row
  execute function content_private.track_thought_last_approval();

-- ── Reports ──────────────────────────────────────────────────────────────────────────────────────────────────────

create table if not exists public.thought_reports (
  id uuid primary key default gen_random_uuid(),
  thought_id uuid not null references public.thoughts (id) on delete cascade,
  account_id uuid not null references auth.users (id) on delete cascade,
  approved_at timestamptz not null,
  reason text not null,
  created_at timestamptz not null default now(),
  resolved_at timestamptz,
  resolution text,
  resolved_by uuid references auth.users (id) on delete set null,
  constraint thought_reports_reason_valid check (
    reason in ('inappropriate', 'promotional', 'not_about_exhibition', 'personal_information', 'other')
  ),
  constraint thought_reports_resolution_valid check (
    (resolved_at is null) = (resolution is null)
    and (resolution is null or resolution in ('dismissed', 'upheld'))
  )
);

comment on table public.thought_reports is
  'Spec 103 reader reports on public 감상, one per account per approved text. An open report hides the 감상 from its '
  'reporter only; staff keep (dismissed) or take down (upheld).';
comment on column public.thought_reports.approved_at is
  'The approved_at of the text reported; a report counts only while it equals the 감상''s current approved_at.';
comment on column public.thought_reports.created_at is
  'When the report was sent; the 48-hour decision clock starts here (owner decision 6).';

create unique index if not exists thought_reports_one_per_account_text
  on public.thought_reports (thought_id, account_id, approved_at);
create index if not exists thought_reports_open_idx
  on public.thought_reports (thought_id, created_at)
  where resolved_at is null;
create index if not exists thought_reports_account_idx on public.thought_reports (account_id);
create index if not exists thought_reports_resolved_by_idx
  on public.thought_reports (resolved_by)
  where resolved_by is not null;

alter table public.thought_reports enable row level security;
revoke all on public.thought_reports from public, anon, authenticated;
grant select on public.thought_reports to service_role;

-- ── Shared checks ────────────────────────────────────────────────────────────────────────────────────────────────

create or replace function content_private.require_thought_staff()
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not content_private.has_staff_role('publisher'::content.staff_role) then
    raise exception using errcode = '42501', message = 'thought_not_staff';
  end if;
end;
$$;

create or replace function content_private.is_thought_rejection_reason(p_reason text)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select coalesce(
    p_reason in (
      'not_about_exhibition', 'promotional', 'inappropriate_language', 'personal_information', 'other'
    ),
    false
  );
$$;

revoke all on function content_private.require_thought_staff() from public, anon, authenticated;
revoke all on function content_private.is_thought_rejection_reason(text) from public, anon, authenticated;

-- ── Reader functions ─────────────────────────────────────────────────────────────────────────────────────────────

create or replace function content_private.list_exhibition_thoughts_impl(p_exhibition_id text, p_limit integer)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_caller uuid := (select auth.uid());
  v_limit integer := least(greatest(coalesce(p_limit, 20), 1), 50);
begin
  if p_exhibition_id is null or btrim(p_exhibition_id) = '' then
    raise exception using errcode = '22023', message = 'thought_list_invalid';
  end if;
  return (
    select coalesce(jsonb_agg(listed.item order by listed.ordinal), '[]'::jsonb)
    from (
      select
        row_number() over (order by thought.created_at desc nulls last, thought.id) as ordinal,
        jsonb_build_object(
          'id', thought.id,
          'user_id', thought.user_id,
          'content', thought.content,
          'created_at', coalesce(thought.created_at, thought.requested_at),
          'updated_at', coalesce(thought.updated_at, thought.created_at, thought.requested_at),
          'author_display_name', coalesce(nullif(btrim(profile.display_name), ''), ''),
          'author_avatar_url', nullif(btrim(profile.avatar_url), ''),
          'reported_by_me', v_caller is not null and exists (
            select 1
            from public.thought_reports as report
            where report.thought_id = thought.id
              and report.account_id = v_caller
              and report.approved_at = thought.approved_at
              and report.resolved_at is not null
          )
        ) as item
      from public.thoughts as thought
      left join public.profiles as profile on profile.id = thought.user_id
      where thought.exhibition_id = p_exhibition_id
        and thought.moderation_status = 'approved'
        and not (
          v_caller is not null and exists (
            select 1
            from public.thought_reports as report
            where report.thought_id = thought.id
              and report.account_id = v_caller
              and report.approved_at = thought.approved_at
              and report.resolved_at is null
          )
        )
      order by thought.created_at desc nulls last, thought.id
      limit v_limit
    ) as listed
  );
end;
$$;

create or replace function content_private.report_thought_impl(p_id uuid, p_reason text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor uuid := (select auth.uid());
  v_thought public.thoughts%rowtype;
begin
  if v_actor is null or coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then
    raise exception using errcode = '42501', message = 'thought_report_unauthenticated';
  end if;
  if p_reason is null
    or p_reason not in ('inappropriate', 'promotional', 'not_about_exhibition', 'personal_information', 'other')
  then
    raise exception using errcode = '22023', message = 'thought_report_invalid_reason';
  end if;
  select * into v_thought from public.thoughts where id = p_id;
  if not found then
    raise sqlstate 'PT404' using message = 'thought_not_found';
  end if;
  if v_thought.moderation_status <> 'approved' then
    raise sqlstate 'PT409' using message = 'thought_not_public';
  end if;
  if v_thought.user_id = v_actor then
    raise exception using errcode = '42501', message = 'thought_report_own';
  end if;
  insert into public.thought_reports (thought_id, account_id, approved_at, reason)
  values (p_id, v_actor, v_thought.approved_at, p_reason);
exception
  when unique_violation then
    raise sqlstate 'PT409' using message = 'thought_report_exists';
end;
$$;

-- ── Staff functions ──────────────────────────────────────────────────────────────────────────────────────────────

create or replace function content_private.list_thought_review_queue_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform content_private.require_thought_staff();
  return (
    select coalesce(jsonb_agg(queued.item order by queued.requested_at, queued.id), '[]'::jsonb)
    from (
      select
        thought.id,
        thought.requested_at,
        jsonb_build_object(
          'id', thought.id,
          'content', thought.content,
          'exhibition_id', thought.exhibition_id,
          'exhibition_name_ko', catalogue.name_ko,
          'exhibition_name_en', catalogue.name_en,
          'author_display_name', nullif(btrim(profile.display_name), ''),
          'requested_at', thought.requested_at,
          'created_at', coalesce(thought.created_at, thought.requested_at),
          'was_approved_before', thought.last_approved_at is not null
        ) as item
      from public.thoughts as thought
      left join public.profiles as profile on profile.id = thought.user_id
      left join public.exhibition_catalog_v2 as catalogue on catalogue.id = thought.exhibition_id
      where thought.moderation_status = 'pending'
    ) as queued
  );
end;
$$;

create or replace function content_private.decide_thought_impl(
  p_id uuid,
  p_decision text,
  p_reason text,
  p_expected_requested_at timestamptz
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor uuid := (select auth.uid());
  v_thought public.thoughts%rowtype;
  v_status text;
  v_reason text;
begin
  perform content_private.require_thought_staff();
  if p_decision is null or p_decision not in ('approve', 'reject') then
    raise exception using errcode = '22023', message = 'thought_decision_invalid';
  end if;
  if p_decision = 'reject' and not content_private.is_thought_rejection_reason(p_reason) then
    raise exception using errcode = '22023', message = 'thought_rejection_reason_required';
  end if;
  select * into v_thought from public.thoughts where id = p_id for update;
  if not found then
    raise sqlstate 'PT404' using message = 'thought_not_found';
  end if;
  -- Nobody decides their own 감상; the M1 trigger would ignore the write, so refuse it plainly.
  if v_thought.user_id = v_actor then
    raise sqlstate 'PT409' using message = 'thought_review_invalid_transition';
  end if;
  if p_expected_requested_at is null or v_thought.requested_at <> p_expected_requested_at then
    raise sqlstate 'PT409' using message = 'thought_review_stale';
  end if;
  if v_thought.moderation_status <> 'pending' then
    raise sqlstate 'PT409' using message = 'thought_review_invalid_transition';
  end if;

  v_status := case p_decision when 'approve' then 'approved' else 'rejected' end;
  v_reason := case p_decision when 'reject' then p_reason end;

  perform set_config('gallr.thought_moderation', 'on', true);
  update public.thoughts
  set moderation_status = v_status,
      rejection_reason = v_reason,
      approved_at = case p_decision when 'approve' then now() end,
      approval_seen_at = null,
      decided_by = v_actor,
      decided_at = now()
  where id = p_id;
  perform set_config('gallr.thought_moderation', '', true);

  insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
  values (
    v_actor,
    case p_decision when 'approve' then 'thought.approved' else 'thought.rejected' end,
    'thought',
    p_id::text,
    jsonb_build_object('status_before', v_thought.moderation_status, 'status_after', v_status)
      || case when v_reason is null then '{}'::jsonb else jsonb_build_object('reason', v_reason) end
  );

  return jsonb_build_object('id', p_id, 'status', v_status, 'rejection_reason', v_reason, 'decided_at', now());
end;
$$;

create or replace function content_private.list_reported_thoughts_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform content_private.require_thought_staff();
  return (
    with open_report as (
      select report.thought_id, report.reason, report.created_at
      from public.thought_reports as report
      join public.thoughts as thought on thought.id = report.thought_id
      where report.resolved_at is null
        and thought.moderation_status = 'approved'
        and report.approved_at = thought.approved_at
    ),
    reported as (
      select
        thought.id,
        min(open_report.created_at) as first_reported_at,
        jsonb_build_object(
          'id', thought.id,
          'content', thought.content,
          'exhibition_id', thought.exhibition_id,
          'exhibition_name_ko', catalogue.name_ko,
          'exhibition_name_en', catalogue.name_en,
          'author_display_name', nullif(btrim(profile.display_name), ''),
          'approved_at', thought.approved_at,
          'open_count', count(*),
          'first_reported_at', min(open_report.created_at),
          'reasons', (
            select jsonb_object_agg(tally.reason, tally.total)
            from (
              select counted.reason, count(*) as total
              from open_report as counted
              where counted.thought_id = thought.id
              group by counted.reason
            ) as tally
          )
        ) as item
      from open_report
      join public.thoughts as thought on thought.id = open_report.thought_id
      left join public.profiles as profile on profile.id = thought.user_id
      left join public.exhibition_catalog_v2 as catalogue on catalogue.id = thought.exhibition_id
      group by thought.id, profile.display_name, catalogue.name_ko, catalogue.name_en
    )
    select coalesce(jsonb_agg(reported.item order by reported.first_reported_at, reported.id), '[]'::jsonb)
    from reported
  );
end;
$$;

create or replace function content_private.resolve_thought_reports_impl(
  p_id uuid,
  p_resolution text,
  p_reason text,
  p_expected_approved_at timestamptz
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor uuid := (select auth.uid());
  v_thought public.thoughts%rowtype;
  v_status_after text;
  v_resolved integer;
begin
  perform content_private.require_thought_staff();
  if p_resolution is null or p_resolution not in ('dismissed', 'upheld') then
    raise exception using errcode = '22023', message = 'thought_report_invalid_resolution';
  end if;
  if p_resolution = 'upheld' and not content_private.is_thought_rejection_reason(p_reason) then
    raise exception using errcode = '22023', message = 'thought_rejection_reason_required';
  end if;
  select * into v_thought from public.thoughts where id = p_id for update;
  if not found then
    raise sqlstate 'PT404' using message = 'thought_not_found';
  end if;
  if v_thought.user_id = v_actor then
    raise sqlstate 'PT409' using message = 'thought_review_invalid_transition';
  end if;
  if v_thought.moderation_status <> 'approved'
    or p_expected_approved_at is null
    or v_thought.approved_at is distinct from p_expected_approved_at
  then
    raise sqlstate 'PT409' using message = 'thought_reports_stale';
  end if;
  if not exists (
    select 1
    from public.thought_reports as report
    where report.thought_id = p_id
      and report.resolved_at is null
      and report.approved_at = v_thought.approved_at
  ) then
    raise sqlstate 'PT409' using message = 'thought_no_open_reports';
  end if;

  v_status_after := v_thought.moderation_status;
  if p_resolution = 'upheld' then
    v_status_after := 'rejected';
    perform set_config('gallr.thought_moderation', 'on', true);
    update public.thoughts
    set moderation_status = 'rejected',
        rejection_reason = p_reason,
        decided_by = v_actor,
        decided_at = now()
    where id = p_id;
    perform set_config('gallr.thought_moderation', '', true);
  end if;

  -- Every open report of this 감상 closes, including any left on text the author has since replaced.
  update public.thought_reports
  set resolved_at = now(), resolution = p_resolution, resolved_by = v_actor
  where thought_id = p_id and resolved_at is null;
  get diagnostics v_resolved = row_count;

  insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
  values (
    v_actor,
    case p_resolution when 'upheld' then 'thought.taken_down' else 'thought.reports_dismissed' end,
    'thought',
    p_id::text,
    jsonb_build_object(
      'status_before', v_thought.moderation_status,
      'status_after', v_status_after,
      'resolved_count', v_resolved
    ) || case when p_resolution = 'upheld' then jsonb_build_object('reason', p_reason) else '{}'::jsonb end
  );

  return jsonb_build_object('id', p_id, 'status', v_status_after, 'resolved_count', v_resolved);
end;
$$;

revoke all on function content_private.list_exhibition_thoughts_impl(text, integer) from public, anon, authenticated;
revoke all on function content_private.report_thought_impl(uuid, text) from public, anon, authenticated;
revoke all on function content_private.list_thought_review_queue_impl() from public, anon, authenticated;
revoke all on function content_private.decide_thought_impl(uuid, text, text, timestamptz)
  from public, anon, authenticated;
revoke all on function content_private.list_reported_thoughts_impl() from public, anon, authenticated;
revoke all on function content_private.resolve_thought_reports_impl(uuid, text, text, timestamptz)
  from public, anon, authenticated;
grant execute on function content_private.report_thought_impl(uuid, text) to authenticated;
grant execute on function content_private.list_thought_review_queue_impl() to authenticated;
grant execute on function content_private.decide_thought_impl(uuid, text, text, timestamptz) to authenticated;
grant execute on function content_private.list_reported_thoughts_impl() to authenticated;
grant execute on function content_private.resolve_thought_reports_impl(uuid, text, text, timestamptz)
  to authenticated;

-- ── Public wrappers ──────────────────────────────────────────────────────────────────────────────────────────────
-- The reader list is also for anon, who has no usage on content_private, so its wrapper runs as definer (as
-- public.list_public_routes does); the impl reads auth.uid() from the request either way.

create or replace function public.list_exhibition_thoughts(p_exhibition_id text, p_limit integer default 20)
returns jsonb language sql stable security definer set search_path = ''
as $$ select content_private.list_exhibition_thoughts_impl(p_exhibition_id, p_limit); $$;

create or replace function public.report_thought(p_id uuid, p_reason text)
returns void language sql volatile security invoker set search_path = ''
as $$ select content_private.report_thought_impl(p_id, p_reason); $$;

create or replace function public.list_thought_review_queue()
returns jsonb language sql stable security invoker set search_path = ''
as $$ select content_private.list_thought_review_queue_impl(); $$;

create or replace function public.decide_thought(
  p_id uuid, p_decision text, p_reason text, p_expected_requested_at timestamptz
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.decide_thought_impl(p_id, p_decision, p_reason, p_expected_requested_at); $$;

create or replace function public.list_reported_thoughts()
returns jsonb language sql stable security invoker set search_path = ''
as $$ select content_private.list_reported_thoughts_impl(); $$;

create or replace function public.resolve_thought_reports(
  p_id uuid, p_resolution text, p_reason text, p_expected_approved_at timestamptz
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.resolve_thought_reports_impl(p_id, p_resolution, p_reason, p_expected_approved_at); $$;

revoke all on function public.list_exhibition_thoughts(text, integer) from public, anon, authenticated;
revoke all on function public.report_thought(uuid, text) from public, anon, authenticated;
revoke all on function public.list_thought_review_queue() from public, anon, authenticated;
revoke all on function public.decide_thought(uuid, text, text, timestamptz) from public, anon, authenticated;
revoke all on function public.list_reported_thoughts() from public, anon, authenticated;
revoke all on function public.resolve_thought_reports(uuid, text, text, timestamptz) from public, anon, authenticated;
grant execute on function public.list_exhibition_thoughts(text, integer) to anon, authenticated;
grant execute on function public.report_thought(uuid, text) to authenticated;
grant execute on function public.list_thought_review_queue() to authenticated;
grant execute on function public.decide_thought(uuid, text, text, timestamptz) to authenticated;
grant execute on function public.list_reported_thoughts() to authenticated;
grant execute on function public.resolve_thought_reports(uuid, text, text, timestamptz) to authenticated;
