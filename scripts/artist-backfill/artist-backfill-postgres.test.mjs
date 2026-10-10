import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { prepareReview, generateSql } from './artist-backfill.mjs';

// This suite accepts only an explicitly named disposable, network-isolated container.
const container = process.env.GALLR_ARTIST_BACKFILL_TEST_CONTAINER;
assert.match(container ?? '', /^gallr-artist-backfill-qa-[a-z0-9-]+$/);
const endpoint = process.env.DOCKER_HOST ?? execFileSync('docker', ['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'], { encoding: 'utf8' }).trim();
assert.ok(endpoint.startsWith('unix://'), 'Only a local Unix-socket Docker engine is allowed');
const dockerEnv = { ...process.env };
delete dockerEnv.DOCKER_CONTEXT;
assert.equal(execFileSync('docker', ['--host', endpoint, 'inspect', '--format', '{{.HostConfig.NetworkMode}}', container], { encoding: 'utf8', env: dockerEnv }).trim(), 'none');
const actor = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
const version = 'bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb';
const target = 'a'.repeat(64);
const source = {
  environment: 'staging', targetSha256: target, snapshotAt: '2026-09-30T00:00:00Z',
  rows: [{ exhibitionId: 'legacy-text-id', versionId: version, creditsKo: '윤홍일', creditsEn: "O'Neil \\ $backfill$" }],
};

function sql(query) {
  return execFileSync('docker', ['--host', endpoint, 'exec', '-i', container, 'psql', '-X', '-U', 'postgres', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-At'], {
    input: query, encoding: 'utf8', env: dockerEnv, stdio: ['pipe', 'pipe', 'pipe'],
  });
}

function reset() {
  sql(`drop schema if exists content cascade;
    create schema content;
    create table content.staff_members (user_id uuid primary key, active boolean, role text);
    create table content.exhibitions (id text primary key, published_version_id uuid, archived_at timestamptz);
    create table content.exhibition_versions (id uuid primary key, status text, credits_ko text, credits_en text);
    create table content.artists (id uuid primary key default gen_random_uuid(), name_ko text not null,
      name_en text not null, archived_at timestamptz, created_by uuid, updated_by uuid,
      check (length(btrim(name_ko)) between 1 and 200), check (length(btrim(name_en)) between 1 and 200));
    create table content.audit_log (actor_user_id uuid, action text, entity_type text, entity_id text, metadata jsonb);
    insert into content.staff_members values ('${actor}', true, 'admin');
    insert into content.exhibitions values ('legacy-text-id', '${version}', null);
    insert into content.exhibition_versions values ('${version}', 'published', '윤홍일', 'O''Neil \\ $backfill$');`);
}

function run(review = prepareReview(source), fingerprint = target) {
  review.reviewedBy = actor;
  review.reviewedAt = '2026-09-30T01:00:00Z';
  for (const candidate of review.candidates) {
    candidate.decision = 'approved';
    candidate.reviewNote = 'Verified identity on published source.';
    candidate.artists = [{ nameKo: candidate.creditsKo, nameEn: candidate.creditsEn }];
  }
  return sql(`set gallr.artist_backfill_target_sha256 = '${fingerprint}';\n${generateSql(review)}`);
}

test('Postgres executes escaped identities, audit, search query and retry no-op', () => {
  reset();
  run();
  run();
  assert.equal(sql('select count(*) from content.artists;').trim(), '1');
  assert.equal(sql('select count(*) from content.audit_log;').trim(), '1');
  assert.equal(sql("select name_ko from content.artists where position(lower('윤홍') in lower(name_ko)) > 0;").trim(), '윤홍일');
  assert.equal(sql('select credits_en from content.exhibition_versions;').trim(), source.rows[0].creditsEn);
});

test('Postgres rejects target mismatch, non-admin reviewer, stale or archived sources', () => {
  for (const [mutation, fingerprint, expected] of [
    ['', 'b'.repeat(64), 'artist_backfill_target_mismatch'],
    ["update content.staff_members set role = 'contributor';", target, 'artist_backfill_reviewer_not_active_admin'],
    ["update content.exhibition_versions set credits_en = 'changed';", target, 'artist_backfill_source_changed'],
    ["update content.exhibitions set archived_at = now();", target, 'artist_backfill_source_changed'],
  ]) {
    reset();
    if (mutation) sql(mutation);
    assert.throws(() => run(undefined, fingerprint), (error) => error.stderr.includes(expected));
    assert.equal(sql('select count(*) from content.artists;').trim(), '0');
    assert.equal(sql('select count(*) from content.audit_log;').trim(), '0');
  }
});

test('Postgres rejects archived, conflicting and duplicate directory identities', () => {
  for (const mutation of [
    "insert into content.artists (name_ko, name_en) values ('윤홍일', 'Different Person');",
    "insert into content.artists (name_ko, name_en, archived_at) select credits_ko, credits_en, now() from content.exhibition_versions;",
    "insert into content.artists (name_ko, name_en) select credits_ko, credits_en from content.exhibition_versions; insert into content.artists (name_ko, name_en) select credits_ko, credits_en from content.exhibition_versions;",
  ]) {
    reset();
    sql(mutation);
    const before = sql('select count(*) from content.artists;');
    assert.throws(() => run(), (error) => error.stderr.includes('artist_backfill_identity_conflict'));
    assert.equal(sql('select count(*) from content.artists;'), before);
    assert.equal(sql('select count(*) from content.audit_log;').trim(), '0');
  }
});

test('Postgres rolls back the full batch when a later identity conflicts', () => {
  reset();
  const review = prepareReview(source);
  review.reviewedBy = actor;
  review.reviewedAt = '2026-09-30T01:00:00Z';
  review.candidates[0].decision = 'approved';
  review.candidates[0].reviewNote = 'Reviewed split into both artists.';
  review.candidates[0].artists = [
    { nameKo: '새 작가', nameEn: 'New Artist' },
    { nameKo: '윤홍일', nameEn: source.rows[0].creditsEn },
  ];
  sql("insert into content.artists (name_ko, name_en) values ('윤홍일', 'Different Person');");
  assert.throws(() => sql(`set gallr.artist_backfill_target_sha256 = '${target}';\n${generateSql(review)}`));
  assert.equal(sql('select count(*) from content.artists;').trim(), '1');
  assert.equal(sql('select count(*) from content.audit_log;').trim(), '0');
});

test('read-only export yields a valid review and excludes draft/archived exhibitions', () => {
  reset();
  const query = readFileSync(new URL('./export-published-credits.sql', import.meta.url), 'utf8');
  const response = sql(`set gallr.artist_backfill_environment = 'staging';\nset gallr.artist_backfill_target_sha256 = '${target}';\n${query}`);
  const exported = JSON.parse(response.trim().split('\n').at(-1));
  assert.equal(prepareReview(exported).candidates.length, 1);
  sql("update content.exhibitions set archived_at = now();");
  const archived = sql(`set gallr.artist_backfill_environment = 'staging';\nset gallr.artist_backfill_target_sha256 = '${target}';\n${query}`);
  assert.deepEqual(JSON.parse(archived.trim().split('\n').at(-1)).rows, []);
});
