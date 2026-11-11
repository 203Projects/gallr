-- Spec 103 M1: a moderation status on public.thoughts, kept in step with is_approved, and a trigger that returns any
-- edited text to review.
--
-- Until now the trigger kept is_approved but let an author change the text of an approved 감상, which stayed public
-- (research §1). From here an author's change of content, by any app version, moves the row back to pending at once.
--
-- The 1.15 app keeps working against this schema: it inserts, edits `content`, deletes, filters on `is_approved`, and
-- its in-app staff queue approves by setting `is_approved = true` on another person's row. That legacy staff path is
-- kept here and becomes unreachable when M3 (20261112100200) removes the staff clauses from the RLS policies.
-- The 1.16 staff path is the M2 functions, which set `gallr.thought_moderation` locally right before their single
-- write to another person's row and clear it right after (`set_config('gallr.thought_moderation', '', true)`).

alter table public.thoughts
  add column if not exists moderation_status text,
  add column if not exists rejection_reason text,
  add column if not exists requested_at timestamptz,
  add column if not exists decided_at timestamptz,
  add column if not exists decided_by uuid references auth.users (id) on delete set null,
  add column if not exists approved_at timestamptz,
  add column if not exists approval_seen_at timestamptz;

-- Backfill only rows that have no status yet, so a re-run never touches a later decision.
update public.thoughts as thought
set is_approved = coalesce(thought.is_approved, false),
    moderation_status = case when coalesce(thought.is_approved, false) then 'approved' else 'pending' end,
    approved_at = case
      when coalesce(thought.is_approved, false)
        then coalesce(thought.approved_at, thought.updated_at, thought.created_at, now())
    end,
    requested_at = coalesce(thought.requested_at, thought.created_at, now())
where thought.moderation_status is null;

alter table public.thoughts
  alter column is_approved set default false,
  alter column is_approved set not null,
  alter column moderation_status set default 'pending',
  alter column moderation_status set not null,
  alter column requested_at set default now(),
  alter column requested_at set not null;

alter table public.thoughts drop constraint if exists thoughts_status_matches_is_approved;
alter table public.thoughts
  add constraint thoughts_status_matches_is_approved
  check (is_approved = (moderation_status = 'approved'));

alter table public.thoughts drop constraint if exists thoughts_moderation_status_valid;
alter table public.thoughts
  add constraint thoughts_moderation_status_valid
  check (moderation_status in ('pending', 'approved', 'rejected'));

alter table public.thoughts drop constraint if exists thoughts_rejection_reason_valid;
alter table public.thoughts
  add constraint thoughts_rejection_reason_valid
  check (
    (moderation_status = 'rejected') = (rejection_reason is not null)
    and (
      rejection_reason is null
      or rejection_reason in (
        'not_about_exhibition',
        'promotional',
        'inappropriate_language',
        'personal_information',
        'other'
      )
    )
  );

create index if not exists thoughts_pending_queue_idx
  on public.thoughts (requested_at)
  where moderation_status = 'pending';

create index if not exists thoughts_public_by_exhibition_idx
  on public.thoughts (exhibition_id, created_at desc)
  where moderation_status = 'approved';

comment on column public.thoughts.is_approved is
  'Kept for the 1.15 app. Always equals moderation_status = ''approved'' (thoughts_status_matches_is_approved).';
comment on column public.thoughts.moderation_status is
  'pending, approved or rejected. Only content_private staff functions (gallr.thought_moderation) decide.';
comment on column public.thoughts.requested_at is
  'When the current text was asked for review: the review queue order and its staleness token.';
comment on column public.thoughts.approved_at is
  'When the current text was approved; identifies the approved text that a report refers to.';
comment on column public.thoughts.approval_seen_at is
  'Set once by the author''s app after it showed that the 감상 was published.';

-- Check order: moderating staff function, service role, legacy 1.15 staff path, author. A staff member's own 감상
-- always goes through the author path, flag or not, so nobody can approve their own text. An INSERT is never a
-- decision. A decision cannot change what was reviewed (text, owner, exhibition, creation and request times) and is
-- attributed to the caller.
create or replace function content_private.enforce_thought_moderation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := (select auth.uid());
  only_approval_changed boolean;
begin
  -- The M2 staff functions set this flag with set_config(..., true) around their single write and write the
  -- decision themselves; only the decision columns may change.
  if tg_op = 'UPDATE'
    and caller_id is not null
    and old.user_id is distinct from caller_id
    and coalesce(current_setting('gallr.thought_moderation', true), '') = 'on'
    and content_private.has_staff_role('publisher'::content.staff_role)
  then
    new.user_id := old.user_id;
    new.exhibition_id := old.exhibition_id;
    new.content := old.content;
    new.created_at := old.created_at;
    new.requested_at := old.requested_at;
    if new.moderation_status is distinct from old.moderation_status then
      new.decided_by := caller_id;
      new.decided_at := now();
    else
      new.decided_by := old.decided_by;
      new.decided_at := old.decided_at;
    end if;
    new.is_approved := new.moderation_status = 'approved';
    return new;
  end if;

  -- Service role and migrations: the status as written decides.
  if caller_id is null then
    new.is_approved := coalesce(new.moderation_status = 'approved', false);
    return new;
  end if;

  if tg_op = 'INSERT' then
    new.moderation_status := 'pending';
    new.is_approved := false;
    new.rejection_reason := null;
    new.requested_at := now();
    new.decided_at := null;
    new.decided_by := null;
    new.approved_at := null;
    new.approval_seen_at := null;
    return new;
  end if;

  only_approval_changed := (to_jsonb(new) - 'is_approved') = (to_jsonb(old) - 'is_approved');
  new.user_id := old.user_id;
  new.exhibition_id := old.exhibition_id;

  -- 1.15 in-app queue: an active admin sets is_approved on another person's row and changes nothing else.
  if only_approval_changed
    and old.user_id is distinct from caller_id
    and content_private.has_staff_role('admin'::content.staff_role)
  then
    if old.moderation_status = 'pending' and new.is_approved is true then
      new.moderation_status := 'approved';
      new.is_approved := true;
      new.rejection_reason := null;
      new.approved_at := now();
      new.decided_at := now();
      new.decided_by := caller_id;
      new.approval_seen_at := null;
    else
      new.moderation_status := old.moderation_status;
      new.is_approved := old.is_approved;
      new.rejection_reason := old.rejection_reason;
      new.requested_at := old.requested_at;
      new.decided_at := old.decided_at;
      new.decided_by := old.decided_by;
      new.approved_at := old.approved_at;
      new.approval_seen_at := old.approval_seen_at;
    end if;
    return new;
  end if;

  -- Author: new text, or asking again after a rejection, goes back to review.
  if new.content is distinct from old.content
    or (new.moderation_status = 'pending' and old.moderation_status <> 'pending')
  then
    new.moderation_status := 'pending';
    new.is_approved := false;
    new.rejection_reason := null;
    new.requested_at := now();
    new.decided_at := null;
    new.decided_by := null;
    new.approved_at := null;
    new.approval_seen_at := null;
    return new;
  end if;

  new.moderation_status := old.moderation_status;
  new.is_approved := old.is_approved;
  new.rejection_reason := old.rejection_reason;
  new.requested_at := old.requested_at;
  new.decided_at := old.decided_at;
  new.decided_by := old.decided_by;
  new.approved_at := old.approved_at;
  if old.moderation_status = 'approved'
    and old.approval_seen_at is null
    and new.approval_seen_at is not null
  then
    new.approval_seen_at := now();
  else
    new.approval_seen_at := old.approval_seen_at;
  end if;
  return new;
end;
$$;

revoke all on function content_private.enforce_thought_moderation()
  from public, anon, authenticated;

drop trigger if exists thoughts_enforce_moderation on public.thoughts;
create trigger thoughts_enforce_moderation
  before insert or update on public.thoughts
  for each row
  execute function content_private.enforce_thought_moderation();
