-- Spec 090, roundup carousels (owner decision 2026-10-13): Admin builds one Instagram carousel from several
-- approved curator notes. Staff need the approved notes of exhibitions still running with the facts a roundup
-- slide prints, and the export is recorded against every note in it. Additive: no table or grant changes.
--
-- It also gives the single-note export its picture. The carousel draws the exhibition's cover, but the facts
-- packet deliberately leaves the cover out (it holds only text a note may cite, and its hash identifies that
-- text), so Admin never received an address to draw and every export was refused. The staff read now carries
-- the cover beside the packet, not in it, together with the published version's other images (Admin → Media,
-- role "gallery") so each insight slide can show its own picture with its credit.

-- Approved notes of exhibitions that have not closed (Seoul date), soonest closing first.
create or replace function content_private.list_approved_curator_notes_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform content_private.require_curator_note_staff();
  return (
    select coalesce(
      jsonb_agg(
        jsonb_build_object(
          'version_id', version.id,
          'exhibition_id', version.exhibition_id,
          'version_number', version.version_number,
          'hook_ko', version.hook_ko,
          'hook_en', version.hook_en,
          'approved_at', version.approved_at,
          'name_ko', coalesce(catalog.name_ko, ''),
          'name_en', coalesce(catalog.name_en, ''),
          'venue_name_ko', coalesce(catalog.venue_name_ko, ''),
          'venue_name_en', coalesce(catalog.venue_name_en, ''),
          'region_ko', coalesce(catalog.region_ko, ''),
          'region_en', coalesce(catalog.region_en, ''),
          'opening_date', catalog.opening_date,
          'closing_date', catalog.closing_date,
          'cover_image_url', catalog.cover_image_url
        )
        order by catalog.closing_date asc, version.approved_at desc, version.id
      ),
      '[]'::jsonb
    )
    from content.curator_notes as note
    join content.curator_note_versions as version on version.id = note.approved_version_id
    join public.exhibition_catalog_v2 as catalog on catalog.id = note.exhibition_id
    where version.status = 'approved'
      and catalog.closing_date >= (now() at time zone 'Asia/Seoul')::date
  );
end;
$$;

-- Records one roundup export: an audit row per note with its place in the roundup. All or nothing.
create or replace function content_private.record_curator_note_roundup_export_impl(
  p_version_ids uuid[],
  p_language text,
  p_slide_count integer
)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_size integer := coalesce(array_length(p_version_ids, 1), 0);
  v_version content.curator_note_versions%rowtype;
begin
  perform content_private.require_curator_note_staff();
  if v_size not between 2 and 10
    or (select count(distinct id) from unnest(p_version_ids) as picked(id)) <> v_size
    or p_language is null or p_language not in ('ko', 'en')
    or p_slide_count is null or p_slide_count not between v_size + 2 and v_size + 4 then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end if;
  for v_position in 1..v_size loop
    select * into v_version from content.curator_note_versions where id = p_version_ids[v_position];
    if v_version.id is null then
      raise exception using errcode = 'P0002', message = 'curator_note_not_found';
    end if;
    if v_version.status <> 'approved' then
      raise sqlstate 'PT409' using message = 'curator_note_invalid_transition';
    end if;
    perform content_private.record_curator_note_audit(
      v_actor, 'curator_note_roundup_exported', v_version.exhibition_id, v_version.id,
      jsonb_build_object('language', p_language, 'slide_count', p_slide_count, 'roundup_size', v_size, 'position', v_position)
    );
  end loop;
  return v_size;
end;
$$;

-- The published version's images as the export may draw them: the cover first, then the gallery in its order.
-- Only published, unpurged assets with an address; an exhibition whose cover is a legacy address has none here.
create or replace function content_private.curator_note_images(p_exhibition_id text)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform content_private.require_curator_note_staff();
  return (
    select coalesce(
      jsonb_agg(
        jsonb_build_object(
          'url', asset.public_url,
          'role', attachment.role,
          'credit', attachment.credit,
          'alt_ko', attachment.alt_ko,
          'alt_en', attachment.alt_en
        )
        order by (attachment.role = 'cover'::content.media_role) desc, attachment.sort_order, attachment.created_at, attachment.media_id
      ),
      '[]'::jsonb
    )
    from content.exhibitions as exhibition
    join content.exhibition_version_media as attachment on attachment.version_id = exhibition.published_version_id
    join content.media_assets as asset on asset.id = attachment.media_id
    where exhibition.id = p_exhibition_id
      and asset.status = 'published'::content.media_asset_status
      and asset.purged_at is null
      and nullif(btrim(asset.public_url), '') is not null
  );
end;
$$;

create or replace function public.get_curator_note(p_exhibition_id text)
returns jsonb language sql stable security invoker set search_path = ''
as $$
  select content_private.get_curator_note_impl(p_exhibition_id)
    || jsonb_build_object(
      'cover_image_url',
      (select catalog.cover_image_url from public.exhibition_catalog_v2 as catalog where catalog.id = p_exhibition_id),
      'images',
      content_private.curator_note_images(p_exhibition_id)
    );
$$;

create or replace function public.list_approved_curator_notes()
returns jsonb language sql stable security invoker set search_path = ''
as $$ select content_private.list_approved_curator_notes_impl(); $$;
create or replace function public.record_curator_note_roundup_export(p_version_ids uuid[], p_language text, p_slide_count integer)
returns integer language sql volatile security invoker set search_path = ''
as $$ select content_private.record_curator_note_roundup_export_impl(p_version_ids, p_language, p_slide_count); $$;

comment on function public.list_approved_curator_notes() is
  'Staff only: approved curator notes of exhibitions still running, with the facts a roundup carousel prints (spec 090).';
comment on function public.record_curator_note_roundup_export(uuid[], text, integer) is
  'Staff only: records a roundup carousel export against each approved note in it (spec 090).';

revoke all on function content_private.curator_note_images(text) from public, anon, service_role;
grant execute on function content_private.curator_note_images(text) to authenticated;
revoke all on function content_private.list_approved_curator_notes_impl() from public, anon, service_role;
revoke all on function content_private.record_curator_note_roundup_export_impl(uuid[], text, integer) from public, anon, service_role;
revoke all on function public.list_approved_curator_notes() from public, anon, service_role;
revoke all on function public.record_curator_note_roundup_export(uuid[], text, integer) from public, anon, service_role;
grant execute on function public.list_approved_curator_notes() to authenticated;
grant execute on function public.record_curator_note_roundup_export(uuid[], text, integer) to authenticated;
grant execute on function content_private.list_approved_curator_notes_impl() to authenticated;
grant execute on function content_private.record_curator_note_roundup_export_impl(uuid[], text, integer) to authenticated;
