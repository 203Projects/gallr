-- Owner exhibition publication email.
--
-- When an owner-workspace exhibition is published for the first time, every
-- active owner of its gallery with a well-formed account email receives one
-- bilingual email with the public page link. The trigger hooks the owner
-- status transition that the existing publication sync performs, so staff
-- publishing through any command is covered. The deduplication key has no
-- per-decision discriminator on purpose: later publications after edits do
-- not email again. The helper is SECURITY DEFINER with an empty search path
-- and is callable only from the trigger.

create or replace function content_private.queue_owner_exhibition_published()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_recipients text[];
  v_name_en text;
  v_name_ko text;
begin
  if content_private.admin_notifications_suppressed() then
    return new;
  end if;
  if new.gallery_id is null
     or new.owner_status is distinct from 'published'::content.owner_exhibition_status
     or old.owner_status is not distinct from 'published'::content.owner_exhibition_status then
    return new;
  end if;

  select coalesce(
    array_agg(lower(btrim(account.email)) order by lower(btrim(account.email))),
    array[]::text[]
  )
  into v_recipients
  from content.gallery_memberships as membership
  join auth.users as account on account.id = membership.user_id
  where membership.gallery_id = new.gallery_id
    and membership.status = 'active'::content.gallery_membership_status
    and membership.role = 'owner'::content.gallery_member_role
    and btrim(account.email) ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$';
  if coalesce(array_length(v_recipients, 1), 0) = 0 then
    return new;
  end if;

  select
    left(btrim(coalesce(version.name_en, '')), 500),
    left(btrim(coalesce(version.name_ko, '')), 500)
  into v_name_en, v_name_ko
  from content.exhibition_versions as version
  where version.id = new.published_version_id;
  if coalesce(v_name_en, '') = '' and coalesce(v_name_ko, '') = '' then
    return new;
  end if;

  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key,
    max_attempts
  ) values (
    'exhibition',
    new.id,
    'owner_exhibition.published',
    jsonb_build_object(
      'source', 'owner_workspace',
      'recipient_emails', to_jsonb(v_recipients),
      'exhibition_id', new.id,
      'exhibition_name_en', v_name_en,
      'exhibition_name_ko', v_name_ko
    ),
    format('owner_exhibition:%s:published', new.id),
    12
  ) on conflict (deduplication_key) do nothing;

  return new;
end;
$$;

revoke all on function content_private.queue_owner_exhibition_published()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibitions_owner_published_outbox on content.exhibitions;
create trigger exhibitions_owner_published_outbox
after update of owner_status on content.exhibitions
for each row
when (new.owner_status = 'published'::content.owner_exhibition_status)
execute function content_private.queue_owner_exhibition_published();
