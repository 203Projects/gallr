-- Align the editor catalogue with the mobile app's visible window and expose
-- the authenticated editor's immutable curation-request history.

create or replace function content_private.editor_pick_json(
  p_exhibition_id text,
  p_version_id uuid,
  p_editor_id text
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  select jsonb_build_object(
    'id', exhibition.id,
    'working_version_id', version.id,
    'published_version_id', exhibition.published_version_id,
    'revision', version.revision,
    'name_ko', version.name_ko,
    'name_en', version.name_en,
    'venue_name_ko', version.venue_name_ko,
    'venue_name_en', version.venue_name_en,
    'opening_date', coalesce(to_char(version.opening_date, 'YYYY-MM-DD'), ''),
    'closing_date', coalesce(to_char(version.closing_date, 'YYYY-MM-DD'), ''),
    'selected', coalesce(version.editor_id = p_editor_id, false),
    'live', coalesce(published.editor_id = p_editor_id, false),
    'available', (
      (version.editor_id is null or version.editor_id = p_editor_id)
      and (published.editor_id is null or published.editor_id = p_editor_id)
    ),
    'assigned_editor_name', coalesce((
      select coalesce(nullif(editor.name_en, ''), editor.name_ko)
      from public.editors as editor
      where editor.id = coalesce(
        nullif(version.editor_id, p_editor_id),
        nullif(published.editor_id, p_editor_id)
      )
    ), '')
  )
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.exhibition_id = exhibition.id
   and version.id = p_version_id
  join content.exhibition_versions as published
    on published.exhibition_id = exhibition.id
   and published.id = exhibition.published_version_id
  where exhibition.id = p_exhibition_id;
$function$;

revoke all on function content_private.editor_pick_json(text, uuid, text)
  from public, anon, authenticated, service_role;

-- Match the app catalogue: ongoing exhibitions plus those opening within the
-- next 14 Seoul calendar days. Assigned work stays visible but is marked as
-- unavailable by editor_pick_json and remains protected by editor_set_pick.
create or replace function content_private.editor_list_pick_candidates_impl(
  p_search text default ''
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_editor_id text := content_private.editor_assert_membership();
  v_search text := lower(btrim(coalesce(p_search, '')));
  v_today date := (current_timestamp at time zone 'Asia/Seoul')::date;
begin
  return query
  select content_private.editor_pick_json(exhibition.id, chosen.id, v_editor_id)
  from content.exhibitions as exhibition
  join content.exhibition_versions as published
    on published.exhibition_id = exhibition.id
   and published.id = exhibition.published_version_id
  join lateral (
    select candidate.*
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
  ) as chosen on true
  where exhibition.published_version_id is not null
    and exhibition.archived_at is null
    and published.opening_date <= v_today + 14
    and published.closing_date >= v_today
    and (
      v_search = ''
      or position(v_search in lower(concat_ws(
        ' ', exhibition.id, chosen.name_ko, chosen.name_en,
        chosen.venue_name_ko, chosen.venue_name_en
      ))) > 0
    )
  order by
    (chosen.editor_id = v_editor_id) desc,
    (
      (chosen.editor_id is null or chosen.editor_id = v_editor_id)
      and (published.editor_id is null or published.editor_id = v_editor_id)
    ) desc,
    chosen.opening_date,
    chosen.closing_date,
    chosen.name_ko,
    exhibition.id;
end;
$function$;

revoke all on function content_private.editor_list_pick_candidates_impl(text)
  from public, anon, authenticated, service_role;
grant execute on function content_private.editor_list_pick_candidates_impl(text)
  to authenticated;

create or replace function content_private.editor_list_curation_history_impl()
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_editor_id text := content_private.editor_assert_membership();
begin
  return query
  select jsonb_build_object(
    'id', request.id,
    'status', request.status,
    'submitted_at', request.created_at,
    'reviewed_at', request.reviewed_at,
    'review_notes', coalesce(request.review_notes, ''),
    'curation_description_ko', coalesce(
      request.payload ->> 'curation_description_ko',
      ''
    ),
    'curation_description_en', coalesce(
      request.payload ->> 'curation_description_en',
      ''
    ),
    'changes', coalesce((
      select jsonb_agg(
        jsonb_build_object(
          'id', change_item.value ->> 'id',
          'name_ko', coalesce(change_item.value ->> 'name_ko', ''),
          'name_en', coalesce(change_item.value ->> 'name_en', ''),
          'venue_name_ko', coalesce(change_item.value ->> 'venue_name_ko', ''),
          'venue_name_en', coalesce(change_item.value ->> 'venue_name_en', ''),
          'opening_date', coalesce(change_item.value ->> 'opening_date', ''),
          'closing_date', coalesce(change_item.value ->> 'closing_date', ''),
          'selected', coalesce((change_item.value ->> 'selected')::boolean, false)
        )
        order by change_item.ordinality
      )
      from jsonb_array_elements(
        coalesce(request.payload -> 'changes', '[]'::jsonb)
      ) with ordinality as change_item(value, ordinality)
    ), '[]'::jsonb)
  )
  from content.editor_requests as request
  where request.editor_id = v_editor_id
    and request.kind = 'curation'
  order by request.created_at desc, request.id desc;
end;
$function$;

revoke all on function content_private.editor_list_curation_history_impl()
  from public, anon, authenticated, service_role;
grant execute on function content_private.editor_list_curation_history_impl()
  to authenticated;

create or replace function public.editor_list_curation_history()
returns setof jsonb
language sql
stable
security invoker
set search_path = ''
as $function$
  select * from content_private.editor_list_curation_history_impl();
$function$;

revoke all on function public.editor_list_curation_history()
  from public, anon, authenticated, service_role;
grant execute on function public.editor_list_curation_history()
  to authenticated;
