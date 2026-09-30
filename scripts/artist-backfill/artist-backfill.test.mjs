import { test } from 'node:test';
import assert from 'node:assert/strict';
import { prepareReview, generateSql } from './artist-backfill.mjs';

const actor = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
const source = {
  environment: 'staging',
  targetSha256: 'a'.repeat(64),
  snapshotAt: '2026-09-30T00:00:00Z',
  rows: [
    { exhibitionId: '11111111-1111-4111-8111-111111111111', versionId: '22222222-2222-4222-8222-222222222222', creditsKo: '윤홍일', creditsEn: 'Hongil Yoon' },
    { exhibitionId: '33333333-3333-4333-8333-333333333333', versionId: '44444444-4444-4444-8444-444444444444', creditsKo: '윤홍일', creditsEn: 'Hongil Yoon' },
    { exhibitionId: '55555555-5555-4555-8555-555555555555', versionId: '66666666-6666-4666-8666-666666666666', creditsKo: 'BBK(장종완 × 장준호)', creditsEn: 'BBK' },
  ],
};

function approved() {
  const review = prepareReview(source);
  review.reviewedBy = actor;
  review.reviewedAt = '2026-09-30T01:00:00Z';
  review.candidates[0].decision = 'approved';
  review.candidates[0].reviewNote = 'Verified bilingual identity against published poster.';
  review.candidates[0].artists = [{ nameKo: '윤홍일', nameEn: 'Hongil Yoon' }];
  review.candidates[1].decision = 'rejected';
  return review;
}

test('deduplicates published credit pairs and retains every source pointer', () => {
  const review = prepareReview(source);
  assert.equal(review.candidates.length, 2);
  assert.equal(review.candidates[0].sources.length, 2);
  assert.equal(review.candidates[0].decision, 'pending');
  assert.deepEqual(review.candidates[1].artists, []);
  assert.ok(review.candidates[1].warnings.includes('collective_or_multiple_names'));
});

test('rejects invalid environment, snapshot, source UUIDs, duplicates and NULs', () => {
  for (const input of [
    { ...source, environment: 'unknown' },
    { ...source, snapshotAt: 'invalid' },
    { ...source, targetSha256: '' },
    { ...source, rows: [source.rows[0], source.rows[0]] },
    { ...source, rows: [{ ...source.rows[0], versionId: 'not-a-uuid' }] },
    { ...source, rows: [{ ...source.rows[0], creditsKo: '\0' }] },
  ]) assert.throws(() => prepareReview(input));
});

test('requires complete explicit review and rejects altered source evidence', () => {
  assert.throws(() => generateSql(prepareReview(source)), /review/i);
  const noNote = approved();
  noNote.candidates[0].reviewNote = '';
  assert.throws(() => generateSql(noNote), /note/i);
  const pending = approved();
  pending.candidates[1].decision = 'pending';
  assert.throws(() => generateSql(pending), /decision/i);
  const altered = approved();
  altered.candidates[0].sources[0].versionId = source.rows[2].versionId;
  assert.throws(() => generateSql(altered), /source/i);
});

test('retains legacy text exhibition IDs while validating UUID version pointers', () => {
  const review = prepareReview({ ...source, rows: [{ ...source.rows[0], exhibitionId: 'a05df2e502291128' }] });
  assert.equal(review.candidates[0].sources[0].exhibitionId, 'a05df2e502291128');
});

test('rejects empty or overlong bilingual identities and conflicting approved names', () => {
  for (const nameEn of ['', 'x'.repeat(201)]) {
    const review = approved();
    review.candidates[0].artists[0].nameEn = nameEn;
    assert.throws(() => generateSql(review), /name/i);
  }
  const review = approved();
  review.candidates[0].artists.push({ nameKo: '윤홍일', nameEn: 'Different Identity' });
  assert.throws(() => generateSql(review), /conflict/i);
});

test('deduplicates approved identities across separately reviewed source pairs', () => {
  const review = approved();
  review.candidates[1].decision = 'approved';
  review.candidates[1].reviewNote = 'Reviewed collective; this artist is a credited member.';
  review.candidates[1].artists = [...review.candidates[0].artists];
  const sql = generateSql(review);
  assert.equal((sql.match(/"nameKo":"윤홍일"/g) ?? []).length, 1);
});

test('generates bounded transactional, provenance-checked, idempotent SQL with audit', () => {
  const sql = generateSql(approved());
  assert.match(sql, /begin;/i);
  assert.match(sql, /lock table content\.artists in share row exclusive mode/i);
  assert.match(sql, /published_version_id/);
  assert.match(sql, /artist_backfill_source_changed/);
  assert.match(sql, /archived_at/);
  assert.match(sql, /artist_backfill_identity_conflict/);
  assert.match(sql, /not exists/);
  assert.match(sql, /content\.audit_log/);
  assert.match(sql, /artist_backfill_target_sha256/);
  assert.doesNotMatch(sql, /update content\./i);
  assert.match(sql, /commit;\s*$/i);
});

test('escapes SQL literals, including quote/backslash and DO delimiters', () => {
  const review = approved();
  review.candidates[0].artists[0].nameEn = "O'Neil \\ $backfill$";
  const sql = generateSql(review);
  assert.match(sql, /O''Neil/);
  assert.match(sql, /set local standard_conforming_strings = on/i);
  assert.equal((sql.match(/do \$backfill\$/g) ?? []).length, 1);
});
