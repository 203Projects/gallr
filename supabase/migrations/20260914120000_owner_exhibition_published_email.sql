-- Owner exhibition publication email.
--
-- When an owner-workspace exhibition is published for the first time, the
-- gallery's active owners with a well-formed account email receive one
-- bilingual email with the public page link. The trigger sits on the version
-- status transition itself (the same transition the owner-status sync
-- watches), because the publish command updates the version before it points
-- the exhibition at the new published version. First publication is detected
-- from version history: any earlier superseded or published version means
-- this is a republication after an edit, which stays silent. The
-- deduplication key is also per exhibition as a second guard. The helper is
-- SECURITY DEFINER with an empty search path and callable only from the
-- trigger.

create or replace function content_private.queue_owner_exhibition_published()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_exhibition content.exhibitions%rowtype;
  v_recipients text[];
  v_name_en text := left(coalesce(new.name_en, ''), 500);
  v_name_ko text := left(coalesce(new.name_ko, ''), 500);
begin
  if content_private.admin_notifications_suppressed() then
    return new;
  end if;
  if new.status is distinct from 'published'::content.exhibition_version_status
     or old.status is not distinct from new.status then
    return new;
  end if;

  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = new.exhibition_id;
  if not found
     or v_exhibition.gallery_id is null
     or v_exhibition.owner_status is null
     or v_exhibition.archived_at is not null then
    return new;
  end if;

  if exists (
    select 1
    from content.exhibition_versions as earlier
    where earlier.exhibition_id = new.exhibition_id
      and earlier.id <> new.id
      and (
        earlier.status = 'superseded'::content.exhibition_version_status
        or earlier.published_at is not null
      )
  ) then
    return new;
  end if;

  if nullif(btrim(v_name_en), '') is null and nullif(btrim(v_name_ko), '') is null then
    return new;
  end if;

  select coalesce(
    array_agg(lower(btrim(account.email)) order by lower(btrim(account.email))),
    array[]::text[]
  )
  into v_recipients
  from content.gallery_memberships as membership
  join auth.users as account on account.id = membership.user_id
  where membership.gallery_id = v_exhibition.gallery_id
    and membership.status = 'active'::content.gallery_membership_status
    and membership.role = 'owner'::content.gallery_member_role
    and btrim(account.email) ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$';
  if coalesce(array_length(v_recipients, 1), 0) = 0 then
    return new;
  end if;

  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key,
    max_attempts
  ) values (
    'exhibition',
    new.exhibition_id,
    'owner_exhibition.published',
    jsonb_build_object(
      'source', 'owner_workspace',
      'recipient_emails', to_jsonb(v_recipients),
      'exhibition_id', new.exhibition_id,
      'exhibition_name_en', v_name_en,
      'exhibition_name_ko', v_name_ko
    ),
    format('owner_exhibition:%s:published', new.exhibition_id),
    12
  ) on conflict (deduplication_key) do nothing;

  return new;
end;
$$;

revoke all on function content_private.queue_owner_exhibition_published()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_versions_owner_published_outbox
  on content.exhibition_versions;
create trigger exhibition_versions_owner_published_outbox
after update of status on content.exhibition_versions
for each row
when (new.status = 'published'::content.exhibition_version_status)
execute function content_private.queue_owner_exhibition_published();
