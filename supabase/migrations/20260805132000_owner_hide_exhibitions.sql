-- Owner workspace soft-delete: retain canonical/review/public state while
-- removing a record from the gallery-facing My exhibitions list.

alter table content.exhibitions
  add column owner_hidden_at timestamptz,
  add column owner_hidden_by uuid references auth.users(id) on delete set null;

comment on column content.exhibitions.owner_hidden_at is
  'When set, omits this canonical exhibition from the owner My exhibitions list without deleting workflow or publication state.';
comment on column content.exhibitions.owner_hidden_by is
  'Authenticated eligible gallery owner who hid the exhibition from My exhibitions.';

create index exhibitions_owner_visible_idx
  on content.exhibitions (gallery_id, updated_at desc, id)
  where owner_status is not null and owner_hidden_at is null;

create index exhibitions_owner_hidden_by_idx
  on content.exhibitions (owner_hidden_by)
  where owner_hidden_by is not null;

create or replace function content_private.owner_list_exhibitions_impl()
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_gallery_id uuid := content_private.owner_assert_gallery_membership(false);
begin
  return query
  select content_private.owner_exhibition_json(exhibition.id, chosen.id)
  from content.exhibitions as exhibition
  join lateral (
    select version.id
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
  where exhibition.gallery_id = v_gallery_id
    and exhibition.owner_status is not null
    and exhibition.owner_hidden_at is null
  order by exhibition.updated_at desc, exhibition.id;
end;
$$;

create or replace function content_private.owner_hide_exhibition_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := content_private.owner_assert_authenticated();
  v_gallery_id uuid := content_private.owner_assert_gallery_info_access();
  v_exhibition content.exhibitions%rowtype;
  v_current_version_id uuid;
  v_current_revision integer;
begin
  if p_exhibition_id is null or btrim(p_exhibition_id) = ''
     or p_expected_version_id is null or p_expected_revision is null then
    raise exception using errcode = '22023', message = 'owner_hide_arguments_invalid';
  end if;

  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
  for update;

  if not found or v_exhibition.gallery_id is distinct from v_gallery_id
     or v_exhibition.owner_status is null then
    raise exception using errcode = '42501', message = 'owner_exhibition_access_denied';
  end if;

  -- A successfully hidden record remains idempotently hidden even if later
  -- staff workflow work advances its canonical version.
  if v_exhibition.owner_hidden_at is not null then
    return jsonb_build_object('id', p_exhibition_id, 'hidden', true);
  end if;

  select version.id, version.revision
  into v_current_version_id, v_current_revision
  from content.exhibition_versions as version
  where version.exhibition_id = v_exhibition.id
    and (
      version.status = 'draft'::content.exhibition_version_status
      or version.id = v_exhibition.published_version_id
    )
  order by
    (version.status = 'draft'::content.exhibition_version_status) desc,
    version.version_number desc
  limit 1;

  if v_current_version_id is null
     or v_current_version_id <> p_expected_version_id
     or v_current_revision <> p_expected_revision then
    raise exception using errcode = '40001', message = 'revision_conflict';
  end if;

  update content.exhibitions
  set owner_hidden_at = now(), owner_hidden_by = v_user_id, updated_by = v_user_id
  where id = v_exhibition.id and owner_hidden_at is null;

  if not found then
    return jsonb_build_object('id', p_exhibition_id, 'hidden', true);
  end if;

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  ) values (
    v_user_id,
    'owner_exhibition.hidden',
    'exhibition',
    v_exhibition.id,
    jsonb_build_object(
      'version_id', v_current_version_id,
      'revision', v_current_revision,
      'owner_status', v_exhibition.owner_status::text
    )
  );

  return jsonb_build_object('id', p_exhibition_id, 'hidden', true);
end;
$$;

create or replace function public.owner_hide_exhibition(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.owner_hide_exhibition_impl(
    p_exhibition_id, p_expected_version_id, p_expected_revision
  );
$$;

revoke all on function content_private.owner_hide_exhibition_impl(text, uuid, integer)
  from public, anon, authenticated, service_role;
grant execute on function content_private.owner_hide_exhibition_impl(text, uuid, integer)
  to authenticated;

revoke all on function public.owner_hide_exhibition(text, uuid, integer)
  from public, anon, authenticated, service_role;
grant execute on function public.owner_hide_exhibition(text, uuid, integer)
  to authenticated;

comment on function public.owner_hide_exhibition(text, uuid, integer) is
  'Hides one caller-gallery exhibition from My exhibitions without deleting canonical, review, media, metric, or publication state.';
