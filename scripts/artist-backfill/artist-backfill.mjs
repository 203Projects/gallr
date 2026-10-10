import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const hash = (value) => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const normalized = (value) => value.trim().toLowerCase();

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}

function text(value, label, maxLength) {
  requireValue(typeof value === 'string' && !value.includes('\0') && [...value].length <= maxLength, `Invalid ${label}`);
  return value;
}

function timestamp(value, label) {
  requireValue(typeof value === 'string' && /^\d{4}-\d\d-\d\dT.*Z$/.test(value) && Number.isFinite(Date.parse(value)), `Invalid ${label}`);
}

/** Prepare unpublished review decisions from a bounded, current-published export. */
export function prepareReview(source) {
  requireValue(source && ['staging', 'production'].includes(source.environment), 'Invalid source environment');
  requireValue(/^[a-f0-9]{64}$/.test(source.targetSha256 ?? ''), 'Invalid source target fingerprint');
  timestamp(source.snapshotAt, 'source snapshot');
  requireValue(Array.isArray(source.rows) && source.rows.length <= 10000, 'Invalid source rows');
  const pointers = new Set();
  const pairs = new Map();
  for (const row of source.rows) {
    requireValue(row && uuid.test(row.versionId), 'Invalid source version UUID');
    text(row.exhibitionId, 'source exhibition ID', 128);
    requireValue(row.exhibitionId.trim() === row.exhibitionId && row.exhibitionId.length > 0, 'Invalid source exhibition ID');
    requireValue(!pointers.has(row.exhibitionId), 'Duplicate source exhibition');
    pointers.add(row.exhibitionId);
    text(row.creditsKo, 'source Korean credits', 10000);
    text(row.creditsEn, 'source English credits', 10000);
    const nameKo = row.creditsKo.trim();
    const nameEn = row.creditsEn.trim();
    if (!nameKo && !nameEn) continue;
    const key = hash([nameKo, nameEn]);
    if (!pairs.has(key)) {
      const warnings = [];
      if (!nameKo || !nameEn) warnings.push('missing_translation');
      if (/[(),，、×/&;\n]|\band\b|\bet al\b/i.test(nameKo + ' ' + nameEn)) warnings.push('collective_or_multiple_names');
      pairs.set(key, { key, creditsKo: nameKo, creditsEn: nameEn, sources: [], warnings, decision: 'pending', reviewNote: '', artists: [] });
    }
    pairs.get(key).sources.push({
      exhibitionId: row.exhibitionId, versionId: row.versionId,
      creditsKo: row.creditsKo, creditsEn: row.creditsEn,
    });
  }
  return {
    formatVersion: 1, source: structuredClone(source), sourceSha256: hash(source),
    reviewedBy: '', reviewedAt: '', candidates: [...pairs.values()],
  };
}

/** Generate reviewable SQL; this utility has no connection or execution capability. */
export function generateSql(review) {
  requireValue(review?.formatVersion === 1 && uuid.test(review.reviewedBy), 'A staff review identity is required');
  timestamp(review.reviewedAt, 'review timestamp');
  const expected = prepareReview(review.source);
  requireValue(review.sourceSha256 === expected.sourceSha256, 'Changed source fingerprint');
  requireValue(Array.isArray(review.candidates) && review.candidates.length === expected.candidates.length, 'Changed source candidate set');
  const artists = new Map();
  const approvedSources = [];
  for (let index = 0; index < expected.candidates.length; index += 1) {
    const candidate = review.candidates[index];
    const original = expected.candidates[index];
    requireValue(candidate && candidate.key === original.key && candidate.creditsKo === original.creditsKo && candidate.creditsEn === original.creditsEn && hash(candidate.sources) === hash(original.sources), 'Changed source candidate evidence');
    requireValue(['approved', 'rejected'].includes(candidate.decision), 'Every review decision must be approved or rejected');
    if (candidate.decision === 'rejected') continue;
    requireValue(text(candidate.reviewNote, 'review note', 2000).trim(), 'A review note is required for each approval');
    requireValue(Array.isArray(candidate.artists) && candidate.artists.length > 0 && candidate.artists.length <= 32, 'An approval requires 1–32 reviewed artist names');
    approvedSources.push(...candidate.sources);
    for (const artist of candidate.artists) {
      const nameKo = text(artist?.nameKo, 'Korean artist name', 200).trim();
      const nameEn = text(artist?.nameEn, 'English artist name', 200).trim();
      requireValue(nameKo && nameEn, 'Both artist names are required');
      const ko = normalized(nameKo);
      const en = normalized(nameEn);
      for (const existing of artists.values()) {
        requireValue(!((normalized(existing.nameKo) === ko) !== (normalized(existing.nameEn) === en)), 'Approved artist identity conflict');
      }
      artists.set(JSON.stringify([ko, en]), { nameKo, nameEn });
    }
  }
  requireValue(artists.size > 0 && artists.size <= 10000, 'No approved artists or too many identities');
  const payload = {
    environment: review.source.environment, targetSha256: review.source.targetSha256,
    reviewedBy: review.reviewedBy, reviewedAt: review.reviewedAt,
    sourceSha256: review.sourceSha256, reviewSha256: hash(review),
    artists: [...artists.values()], sources: approvedSources,
  };
  const literal = JSON.stringify(payload).replaceAll("'", "''");
  return `-- Staff-reviewed canonical artist backfill. No published versions are modified.
-- Verify the target and set gallr.artist_backfill_target_sha256 in this session.
begin;
set local standard_conforming_strings = on;
set local lock_timeout = '5s';
set local statement_timeout = '30s';
lock table content.exhibitions in share mode;
lock table content.artists in share row exclusive mode;
create temporary table artist_backfill_review (payload jsonb not null) on commit drop;
insert into artist_backfill_review values ('${literal}'::jsonb);

do $backfill$
declare
  bundle jsonb := (select payload from artist_backfill_review);
  actor uuid := (bundle ->> 'reviewedBy')::uuid;
  candidate jsonb;
  new_id uuid;
begin
  if current_setting('gallr.artist_backfill_target_sha256', true) is distinct from bundle ->> 'targetSha256' then
    raise exception 'artist_backfill_target_mismatch';
  end if;
  if not exists (select 1 from content.staff_members where user_id = actor and active and role = 'admin') then
    raise exception 'artist_backfill_reviewer_not_active_admin';
  end if;
  if exists (
    select 1 from jsonb_array_elements(bundle -> 'sources') source
    where not exists (
      select 1 from content.exhibitions exhibition
      join content.exhibition_versions version on version.id = exhibition.published_version_id
      where exhibition.id = source ->> 'exhibitionId'
        and exhibition.archived_at is null
        and version.id = (source ->> 'versionId')::uuid
        and version.status = 'published'
        and version.credits_ko = source ->> 'creditsKo'
        and version.credits_en = source ->> 'creditsEn'
    )
  ) then
    raise exception 'artist_backfill_source_changed';
  end if;
  for candidate in select value from jsonb_array_elements(bundle -> 'artists') loop
    if exists (
      select 1 from content.artists existing
      where (lower(btrim(existing.name_ko)) = lower(candidate ->> 'nameKo')
          or lower(btrim(existing.name_en)) = lower(candidate ->> 'nameEn'))
        and (existing.archived_at is not null
          or lower(btrim(existing.name_ko)) <> lower(candidate ->> 'nameKo')
          or lower(btrim(existing.name_en)) <> lower(candidate ->> 'nameEn'))
    ) or (select count(*) from content.artists existing
          where lower(btrim(existing.name_ko)) = lower(candidate ->> 'nameKo')
            and lower(btrim(existing.name_en)) = lower(candidate ->> 'nameEn')) > 1 then
      raise exception 'artist_backfill_identity_conflict';
    end if;
    if not exists (
      select 1 from content.artists existing
      where lower(btrim(existing.name_ko)) = lower(candidate ->> 'nameKo')
        and lower(btrim(existing.name_en)) = lower(candidate ->> 'nameEn')
    ) then
      insert into content.artists (name_ko, name_en, created_by, updated_by)
      values (candidate ->> 'nameKo', candidate ->> 'nameEn', actor, actor)
      returning id into new_id;
      insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
      values (actor, 'artist.backfilled', 'artist', new_id::text,
        jsonb_build_object('source_sha256', bundle ->> 'sourceSha256',
          'review_sha256', bundle ->> 'reviewSha256', 'environment', bundle ->> 'environment',
          'reviewed_at', bundle ->> 'reviewedAt'));
    end if;
  end loop;
end;
$backfill$;

select count(*) as reviewed_identities_present
from content.artists artist
where exists (
  select 1 from artist_backfill_review review,
    jsonb_array_elements(review.payload -> 'artists') candidate
  where lower(btrim(artist.name_ko)) = lower(candidate ->> 'nameKo')
    and lower(btrim(artist.name_en)) = lower(candidate ->> 'nameEn')
    and artist.archived_at is null
);
commit;
`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [mode, inputPath, outputPath, ...extra] = process.argv.slice(2);
    requireValue(['prepare', 'sql'].includes(mode) && inputPath && outputPath && extra.length === 0, 'Usage: artist-backfill.mjs prepare|sql INPUT OUTPUT');
    const input = JSON.parse(readFileSync(inputPath, 'utf8'));
    const output = mode === 'prepare' ? JSON.stringify(prepareReview(input), null, 2) + '\n' : generateSql(input);
    // Retain every prior review/receipt; never silently replace an existing artifact.
    writeFileSync(outputPath, output, { flag: 'wx', mode: 0o600 });
    console.log('PASS: offline artist backfill artifact prepared; no database connection opened');
  } catch (error) {
    // Do not echo input paths, contents, or provider errors.
    console.error(error instanceof Error && !('code' in error) ? error.message : 'Artist backfill input/output failed');
    process.exitCode = 1;
  }
}
