-- Legacy reader grants for public.events and public.editors.
--
-- Both tables predate explicit Data API grants in this lineage (013_create_events.sql and the
-- editor migrations only add row-level policies) and have relied on the production project's
-- historical public-schema default privileges. A clean replay of the lineage (local stack, CI,
-- staging) therefore leaves the Data API roles without table privileges, and the installed app,
-- which reads both tables through the REST endpoint (EventApiClient, EditorApiClient), shows no
-- events and no editor pages. The production project already holds these grants, so every
-- statement below is a no-op there.
--
-- Reader roles get SELECT only; the row-level policies keep deciding which rows they see.
-- The service role gets the same table-level privileges as public.exhibitions so the legacy mirror
-- and operator tooling behave the same on every project.
begin;

grant select on table public.events to anon, authenticated;
grant select on table public.editors to anon, authenticated;

grant select, insert, update, delete, truncate on table public.events to service_role;
grant select, insert, update, delete, truncate on table public.editors to service_role;

commit;
