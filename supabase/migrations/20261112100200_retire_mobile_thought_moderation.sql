-- Spec 103 M3: retire the 1.15 in-app staff queue on the server (owner decision 8, FR-027).
--
-- Apply right after Admin's 감상 section is deployed, on staging and then on production; not when the minimum app
-- version reaches 1.16. From here an active admin reaches another person's 감상 only through the M2 staff functions
-- (security definer, 20261112100100); through the table every signed-in account reads approved 감상 and its own, and
-- updates or deletes its own only. Anon read and the insert policy are unchanged.
--
-- A 1.15 staff account then sees only its own pending 감상 in the in-app queue; approving one there is an author
-- write the M1 trigger ignores, and deleting one deletes the staff member's own 감상, as for any author.
--
-- Left for M5 (once the minimum app version is 1.16.0 on both platforms): the trigger's legacy 1.15 staff branch,
-- now unreachable, and public.profiles.is_admin with its mirror triggers once no supported client reads it.

drop policy if exists "authenticated users can read permitted thoughts" on public.thoughts;
drop policy if exists "authenticated users can read approved or own thoughts" on public.thoughts;
create policy "authenticated users can read approved or own thoughts"
  on public.thoughts
  for select
  to authenticated
  using (is_approved = true or (select auth.uid()) = user_id);

drop policy if exists "owners or admins can update thoughts" on public.thoughts;
drop policy if exists "owners can update their thoughts" on public.thoughts;
create policy "owners can update their thoughts"
  on public.thoughts
  for update
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "owners or admins can delete thoughts" on public.thoughts;
drop policy if exists "owners can delete their thoughts" on public.thoughts;
create policy "owners can delete their thoughts"
  on public.thoughts
  for delete
  to authenticated
  using ((select auth.uid()) = user_id);
