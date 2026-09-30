-- Read-only export. Run only in a verified environment-specific session.
-- Set gallr.artist_backfill_environment and gallr.artist_backfill_target_sha256
-- after the applicable target guard passes. Neither value is a credential.
select jsonb_build_object(
  'environment', current_setting('gallr.artist_backfill_environment'),
  'targetSha256', current_setting('gallr.artist_backfill_target_sha256'),
  'snapshotAt', to_char(now() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  'rows', coalesce(jsonb_agg(jsonb_build_object(
    'exhibitionId', exhibition.id,
    'versionId', version.id,
    'creditsKo', version.credits_ko,
    'creditsEn', version.credits_en
  ) order by exhibition.id), '[]'::jsonb)
)
from content.exhibitions exhibition
join content.exhibition_versions version on version.id = exhibition.published_version_id
where version.status = 'published'
  and exhibition.archived_at is null
  and (btrim(version.credits_ko) <> '' or btrim(version.credits_en) <> '');
