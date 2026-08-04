-- Snapshot an owner's attached draft media into each review round so the
-- staff submission queue reflects exactly what was submitted at that time.

create or replace function content_private.snapshot_owner_submission_media()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_version_id uuid;
  v_snapshot_count integer;
begin
  if new.source <> 'owner_workspace' then
    return new;
  end if;

  begin
    v_version_id := nullif(new.payload ->> 'version_id', '')::uuid;
  exception
    when invalid_text_representation then
      raise exception using
        errcode = '23514',
        message = 'owner_submission_version_snapshot_invalid';
  end;

  if v_version_id is null then
    raise exception using
      errcode = '23514',
      message = 'owner_submission_version_snapshot_missing';
  end if;

  insert into content.submission_media (
    submission_id,
    media_id,
    sort_order
  )
  select
    new.id,
    ranked.media_id,
    ranked.snapshot_order
  from (
    select
      attachment.media_id,
      (
        row_number() over (
          order by
            case
              when attachment.role = 'cover'::content.media_role then 0
              else 1
            end,
            attachment.sort_order,
            attachment.media_id
        ) - 1
      )::integer as snapshot_order
    from content.exhibition_version_media as attachment
    where attachment.version_id = v_version_id
  ) as ranked;

  get diagnostics v_snapshot_count = row_count;
  if v_snapshot_count = 0 then
    raise exception using
      errcode = '23514',
      message = 'owner_submission_media_snapshot_missing';
  end if;

  return new;
end;
$$;

revoke all on function content_private.snapshot_owner_submission_media()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_submission_snapshot_owner_media
  on content.exhibition_submissions;
create trigger exhibition_submission_snapshot_owner_media
  after insert on content.exhibition_submissions
  for each row
  when (new.source = 'owner_workspace')
  execute function content_private.snapshot_owner_submission_media();
