-- Spec 090 follow-up: a note's carousel can run to ten slides. Gallery pictures the insights do not use now get a
-- slide each between the cover and the closing slide, so an export is the cover, up to four insight slides, the
-- picture slides and the closing slide, ten at most. record_curator_note_export still refused more than six, which
-- failed the audit call after the archive had already downloaded. The accepted range is now 4 to 10.
--
-- The body is the one recorded by 20261012000000 with only that range changed. The signature is the same, so
-- create or replace keeps the owner and the execute grant to authenticated that migration set; nothing else moves.
-- Deploy this before the Admin build that exports picture slides.
begin;

create or replace function content_private.record_curator_note_export_impl(
  p_version_id uuid,
  p_language text,
  p_slide_count integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_version content.curator_note_versions%rowtype;
  v_actor uuid := auth.uid();
begin
  perform content_private.require_curator_note_staff();
  select * into v_version from content.curator_note_versions where id = p_version_id;
  if v_version.id is null then
    raise exception using errcode = 'P0002', message = 'curator_note_not_found';
  end if;
  if v_version.status <> 'approved' then
    raise sqlstate 'PT409' using message = 'curator_note_invalid_transition';
  end if;
  if p_language not in ('ko', 'en') or p_slide_count is null or p_slide_count not between 4 and 10 then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end if;
  perform content_private.record_curator_note_audit(
    v_actor, 'curator_note_exported', v_version.exhibition_id, p_version_id,
    jsonb_build_object('language', p_language, 'slide_count', p_slide_count)
  );
  return content_private.curator_note_version_json(p_version_id);
end;
$$;

commit;
