import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { prepareReview, generateSql } from './artist-backfill.mjs';

// Execute only against a local disposable Supabase database. All fixtures,
// backfills, projection/outbox effects and assertions roll back together.
const container = process.env.SUPABASE_DB_CONTAINER;
assert.match(container ?? '', /^supabase_db_[a-z0-9_-]+$/);
const endpoint = process.env.DOCKER_HOST ?? execFileSync('docker', ['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'], { encoding: 'utf8' }).trim();
assert.ok(endpoint.startsWith('unix://'), 'Only a local Unix-socket Docker engine is allowed');
const dockerEnv = { ...process.env };
delete dockerEnv.DOCKER_CONTEXT;
const actor = '86000000-0000-4000-8000-000000000001';
const version = '86000000-0000-4000-8000-000000000002';
const nameEn = "Backfill O'Neil \\ $backfill$";
const source = {
  environment: 'staging', targetSha256: 'a'.repeat(64), snapshotAt: '2026-09-30T00:00:00Z',
  rows: [{ exhibitionId: 'artist-backfill-canonical-fixture', versionId: version, creditsKo: '검증회귀작가', creditsEn: nameEn }],
};
const review = prepareReview(source);
review.reviewedBy = actor;
review.reviewedAt = '2026-09-30T01:00:00Z';
review.candidates[0].decision = 'approved';
review.candidates[0].reviewNote = 'Disposable canonical-schema fixture review';
review.candidates[0].artists = [{ nameKo: '검증회귀작가', nameEn }];
const generated = generateSql(review);
// Remove only the generated outer transaction, so the exact body can be
// exercised twice inside the fixture transaction without ever committing it.
assert.equal((generated.match(/^begin;$/gm) ?? []).length, 1);
assert.equal((generated.match(/^commit;$/gm) ?? []).length, 1);
const body = generated.replace(/^begin;$/m, '').replace(/^commit;$/m, '');
const literal = (value) => "'" + value.replaceAll("'", "''") + "'";
const fixture = `begin;
  set local standard_conforming_strings = on;
  set local gallr.artist_backfill_environment = 'staging';
  set local gallr.artist_backfill_target_sha256 = '${source.targetSha256}';
  insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
  values ('${actor}', 'artist-backfill-fixture@example.invalid', now(), '{}'::jsonb);
  insert into content.staff_members (user_id, role, active) values ('${actor}', 'admin', true);
  insert into content.exhibitions (id, created_by, updated_by)
  values ('artist-backfill-canonical-fixture', '${actor}', '${actor}');
  insert into content.exhibition_versions (
    id, exhibition_id, version_number, status, published_at,
    name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en,
    region_ko, region_en, address_ko, address_en, opening_date, closing_date,
    latitude, longitude, description_ko, description_en, hours, country_code,
    credits_ko, credits_en, created_by, updated_by
  ) values (
    '${version}', 'artist-backfill-canonical-fixture', 1, 'published', now(),
    '검증 전시', 'Backfill fixture', '검증 공간', 'Fixture venue', '서울', 'Seoul',
    '종로구', 'Jongno-gu', '서울특별시 종로구 삼청로 10', '10 Samcheong-ro',
    '2026-09-01', '2026-12-31', 37.582, 126.981, '검증', 'Verification', '10:00-18:00', 'KR',
    '검증회귀작가', ${literal(nameEn)}, '${actor}', '${actor}'
  );
  update content.exhibitions set published_version_id = '${version}'
  where id = 'artist-backfill-canonical-fixture';
`;

function sql(query) {
  return execFileSync('docker', ['--host', endpoint, 'exec', '-i', container, 'psql', '-U', 'postgres', '-d', 'postgres', '-XAt', '-v', 'ON_ERROR_STOP=1'], {
    input: query, encoding: 'utf8', env: dockerEnv, stdio: ['pipe', 'pipe', 'pipe'],
  });
}

sql(`${fixture}${body}
  drop table artist_backfill_review;
  ${body}
  do $assertions$
  begin
    if (select count(*) from content.artists where created_by = '${actor}') <> 1 then
      raise exception 'backfill_retry_added_duplicate';
    end if;
    if (select count(*) from content.audit_log where actor_user_id = '${actor}' and action = 'artist.backfilled') <> 1 then
      raise exception 'backfill_audit_not_idempotent';
    end if;
    if not exists (select 1 from content.exhibition_versions where id = '${version}' and credits_en = ${literal(nameEn)}) then
      raise exception 'backfill_changed_published_credits';
    end if;
  end;
  $assertions$;
  set local role authenticated;
  select set_config('request.jwt.claims', '{"sub":"${actor}","role":"authenticated"}', true);
  do $search$
  begin
    if (select count(*) from public.admin_search_artists('검증회귀', 20)) <> 1
       or (select count(*) from public.admin_search_artists('Backfill O', 20)) <> 1 then
      raise exception 'backfill_artist_not_searchable';
    end if;
  end;
  $search$;
  reset role;
  rollback;`);
console.log('PASS: canonical schema insert, audit, search RPC, immutable credits and retry no-op');

for (const [mutation, expected] of [
  ["set local gallr.artist_backfill_target_sha256 = 'wrong';", 'artist_backfill_target_mismatch'],
  [`update content.staff_members set role = 'contributor' where user_id = '${actor}';`, 'artist_backfill_reviewer_not_active_admin'],
  ["update content.exhibitions set archived_at = now() where id = 'artist-backfill-canonical-fixture';", 'artist_backfill_source_changed'],
  ["insert into content.artists (name_ko, name_en) values ('검증회귀작가', 'Different Identity');", 'artist_backfill_identity_conflict'],
  [`insert into content.artists (name_ko, name_en, archived_at) values ('검증회귀작가', ${literal(nameEn)}, now());`, 'artist_backfill_identity_conflict'],
]) {
  assert.throws(() => sql(`${fixture}${mutation}${body}rollback;`), (error) => error.stderr.includes(expected));
}
assert.equal(sql(`select count(*) from content.artists where created_by = '${actor}';`).trim(), '0');
assert.equal(sql(`select count(*) from auth.users where id = '${actor}';`).trim(), '0');
console.log('PASS: canonical schema denial/conflict branches roll back without retained fixtures');

const exportQuery = readFileSync(new URL('./export-published-credits.sql', import.meta.url), 'utf8');
const response = sql(`${fixture}${exportQuery}rollback;`);
const exported = JSON.parse(response.split('\n').find(line => line.startsWith('{"rows":') || line.startsWith('{"environment":')));
assert.ok(prepareReview(exported).candidates.some(candidate => candidate.sources.some(pointer => pointer.versionId === version)));
console.log('PASS: canonical published-credit export prepares valid reviewed provenance');
