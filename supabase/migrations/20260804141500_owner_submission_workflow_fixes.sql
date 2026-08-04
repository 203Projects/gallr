-- Keep private owner drafts out of the staff exhibition catalogue and notify
-- gallery owners when staff decides a completed submission.

create or replace function content_private.admin_owner_exhibition_visible(
  p_exhibition_id text
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    (
      select exhibition.owner_status is null
        or exhibition.owner_status in (
          'published'::content.owner_exhibition_status,
          'archived'::content.owner_exhibition_status
        )
        or exists (
          select 1
          from content.exhibition_submissions as submission
          where submission.owner_exhibition_id = exhibition.id
            and submission.source = 'owner_workspace'
            and submission.status = 'accepted'::content.submission_status
        )
      from content.exhibitions as exhibition
      where exhibition.id = p_exhibition_id
    ),
    false
  );
$$;

revoke all on function content_private.admin_owner_exhibition_visible(text)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_list_exhibitions_impl(
  p_search text default '',
  p_status text default null
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_search text := lower(btrim(coalesce(p_search, '')));
begin
  perform content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );

  if p_status is not null
     and p_status not in ('draft', 'published', 'archived') then
    raise exception using
      errcode = '22023',
      message = 'invalid_exhibition_status_filter';
  end if;

  return query
  select content_private.admin_exhibition_json(exhibition.id, chosen.id)
  from content.exhibitions as exhibition
  join lateral (
    select version.id, version.status, version.name_ko, version.name_en,
      version.venue_name_ko, version.venue_name_en, version.updated_at
    from content.exhibition_versions as version
    where version.exhibition_id = exhibition.id
      and (
        version.status = 'draft'::content.exhibition_version_status
        or version.id = exhibition.published_version_id
      )
    order by
      (version.status = 'draft'::content.exhibition_version_status) desc,
      version.version_number desc
    limit 1
  ) as chosen on true
  cross join lateral (
    select case
      when exhibition.archived_at is not null then 'archived'
      when chosen.status = 'draft'::content.exhibition_version_status then 'draft'
      else 'published'
    end as value
  ) as resolved_status
  where content_private.admin_owner_exhibition_visible(exhibition.id)
    and (
      v_search = ''
      or position(
        v_search in lower(concat_ws(
          ' ',
          exhibition.id,
          chosen.name_ko,
          chosen.name_en,
          chosen.venue_name_ko,
          chosen.venue_name_en
        ))
      ) > 0
    )
    and (p_status is null or resolved_status.value = p_status)
  order by greatest(exhibition.updated_at, chosen.updated_at) desc,
    exhibition.id;
end;
$$;

create or replace function content_private.admin_get_exhibition_impl(
  p_exhibition_id text
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_version_id uuid;
  v_result jsonb;
begin
  perform content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );

  select version.id
  into v_version_id
  from content.exhibitions as exhibition
  join lateral (
    select candidate.id, candidate.status, candidate.version_number
    from content.exhibition_versions as candidate
    where candidate.exhibition_id = exhibition.id
      and (
        candidate.status = 'draft'::content.exhibition_version_status
        or candidate.id = exhibition.published_version_id
      )
    order by
      (candidate.status = 'draft'::content.exhibition_version_status) desc,
      candidate.version_number desc
    limit 1
  ) as version on true
  where exhibition.id = p_exhibition_id
    and content_private.admin_owner_exhibition_visible(exhibition.id);

  if v_version_id is null then
    raise exception using
      errcode = 'P0002',
      message = 'exhibition_not_found';
  end if;

  v_result := content_private.admin_exhibition_json(
    p_exhibition_id,
    v_version_id
  );
  return v_result;
end;
$$;

create or replace function content_private.queue_owner_submission_decision()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_event_type text;
  v_exhibition_name text;
begin
  if new.source <> 'owner_workspace'
     or old.status is not distinct from new.status
     or new.status not in (
       'accepted'::content.submission_status,
       'rejected'::content.submission_status
     ) then
    return new;
  end if;

  v_event_type := case
    when new.status = 'accepted'::content.submission_status
      then 'submission.accepted'
    else 'submission.rejected'
  end;
  v_exhibition_name := coalesce(
    nullif(btrim(new.payload ->> 'name_en'), ''),
    nullif(btrim(new.payload ->> 'name_ko'), ''),
    'Your exhibition'
  );

  insert into content.outbox_events (
    aggregate_type,
    aggregate_id,
    event_type,
    payload,
    deduplication_key
  ) values (
    'exhibition_submission',
    new.id::text,
    v_event_type,
    jsonb_build_object(
      'source', 'owner_workspace',
      'submission_id', new.id,
      'exhibition_id', new.owner_exhibition_id,
      'recipient_email', lower(new.submitter_email),
      'exhibition_name', v_exhibition_name,
      'review_notes', coalesce(new.review_notes, '')
    ),
    format('owner_submission:%s:%s', new.id, new.status::text)
  ) on conflict (deduplication_key) do nothing;

  return new;
end;
$$;

revoke all on function content_private.queue_owner_submission_decision()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_submission_owner_decision_outbox
  on content.exhibition_submissions;
create trigger exhibition_submission_owner_decision_outbox
after update of status on content.exhibition_submissions
for each row
execute function content_private.queue_owner_submission_decision();

-- Owner submissions must be complete in both catalogue languages. The form
-- checks this field by field; this trigger is the server-side guard against
-- bypassing that validation or submitting from an older client.
create or replace function content_private.require_owner_submission_bilingual_content()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_version content.exhibition_versions%rowtype;
begin
  if new.source <> 'owner_workspace'
     or new.status <> 'submitted'::content.submission_status then
    return new;
  end if;

  select version.*
  into v_version
  from content.exhibition_versions as version
  where version.exhibition_id = new.owner_exhibition_id
    and version.id::text = new.payload ->> 'version_id';

  if not found then
    raise exception using
      errcode = 'P0002',
      message = 'owner_submission_version_not_found';
  end if;

  if nullif(btrim(v_version.name_ko), '') is null
     or nullif(btrim(v_version.name_en), '') is null
     or nullif(btrim(v_version.venue_name_ko), '') is null
     or nullif(btrim(v_version.venue_name_en), '') is null
     or nullif(btrim(v_version.city_ko), '') is null
     or nullif(btrim(v_version.city_en), '') is null
     or nullif(btrim(v_version.region_ko), '') is null
     or nullif(btrim(v_version.region_en), '') is null
     or nullif(btrim(v_version.address_ko), '') is null
     or nullif(btrim(v_version.address_en), '') is null then
    raise exception using
      errcode = '23514',
      message = 'owner_submission_bilingual_incomplete';
  end if;

  return new;
end;
$$;

revoke all on function content_private.require_owner_submission_bilingual_content()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_submission_require_bilingual_content
  on content.exhibition_submissions;
create trigger exhibition_submission_require_bilingual_content
before insert on content.exhibition_submissions
for each row
execute function content_private.require_owner_submission_bilingual_content();
