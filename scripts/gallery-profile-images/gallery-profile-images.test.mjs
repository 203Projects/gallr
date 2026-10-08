import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  generateSql,
  normalizationArguments,
  requiresAttribution,
  storagePath,
  validateBundle,
  validateManifest,
} from './gallery-profile-images.mjs';

const galleryA = '11111111-1111-4111-8111-111111111111';
const galleryB = '22222222-2222-4222-8222-222222222222';
const sha = 'a'.repeat(64);

function entry(overrides = {}) {
  return {
    gallery_id: galleryA,
    name_ko: '국제갤러리',
    name_en: 'Kukje Gallery',
    kind: 'logo',
    image_url: 'https://www.kukjegallery.com/apple-icon-180x180.png',
    source_page: 'https://www.kukjegallery.com/',
    license: 'official site',
    credit: null,
    ...overrides,
  };
}

function photo(overrides = {}) {
  return entry({
    gallery_id: galleryB,
    name_ko: '일민미술관',
    name_en: 'Ilmin Museum of Art',
    kind: 'photo',
    image_url: 'https://upload.wikimedia.org/wikipedia/commons/b/b0/Ilmin_Museum_of_Art.jpg',
    source_page: 'https://commons.wikimedia.org/wiki/File:Ilmin_Museum_of_Art.jpg',
    license: 'CC BY-SA 4.0 (Wikimedia Commons)',
    credit: "Lawinc82 / Wikimedia Commons, CC BY-SA 4.0",
    ...overrides,
  });
}

test('committed manifest is valid', () => {
  const manifest = JSON.parse(readFileSync(new URL('./approved-manifest.json', import.meta.url), 'utf8'));
  assert.equal(validateManifest(manifest).length, manifest.length);
});

test('valid manifest passes unchanged', () => {
  assert.deepEqual(validateManifest([entry(), photo()]), [entry(), photo()]);
});

test('manifest rejects empty, duplicate and malformed entries', () => {
  assert.throws(() => validateManifest([]), /empty/);
  assert.throws(() => validateManifest({}), /array/);
  assert.throws(() => validateManifest([entry(), entry()]), /Duplicate gallery/);
  assert.throws(() => validateManifest([entry({ gallery_id: 'not-a-uuid' })]), /gallery_id/);
  assert.throws(() => validateManifest([entry({ kind: 'banner' })]), /kind/);
  assert.throws(() => validateManifest([entry({ image_url: 'javascript:alert(1)' })]), /image_url/);
  assert.throws(() => validateManifest([entry({ source_page: 'ftp://x' })]), /source_page/);
  assert.throws(() => validateManifest([entry({ license: ' ' })]), /license/);
  assert.throws(() => validateManifest([entry({ name_ko: '' })]), /name_ko/);
});

test('credit is required exactly when the license requires attribution', () => {
  assert.equal(requiresAttribution('official site'), false);
  assert.equal(requiresAttribution('CC0 (Wikimedia Commons)'), false);
  assert.equal(requiresAttribution('CC BY 3.0 (Wikimedia Commons)'), true);
  assert.equal(requiresAttribution('KOGL Type 1 (Wikimedia Commons)'), true);
  assert.throws(() => validateManifest([photo({ credit: null })]), /credit/);
  assert.throws(() => validateManifest([entry({ credit: 'Someone' })]), /credit/);
  assert.doesNotThrow(() => validateManifest([photo({ license: 'CC0 (Wikimedia Commons)', credit: null })]));
});

test('logos are fitted and padded on white, photos are scaled and centre-cropped', () => {
  assert.deepEqual(normalizationArguments('logo', { width: 180, height: 180 }, '/in.png', '/out.jpg'), [
    '--resampleHeightWidthMax', '416',
    '--padToHeightWidth', '512', '512', '--padColor', 'FFFFFF',
    '-s', 'format', 'jpeg', '-s', 'formatOptions', '82',
    '/in.png', '--out', '/out.jpg',
  ]);
  assert.deepEqual(normalizationArguments('photo', { width: 1600, height: 900 }, '/in.jpg', '/out.jpg').slice(0, 5), [
    '--resampleHeight', '512', '--cropToHeightWidth', '512', '512',
  ]);
  assert.deepEqual(normalizationArguments('photo', { width: 900, height: 1600 }, '/in.jpg', '/out.jpg').slice(0, 2), [
    '--resampleWidth', '512',
  ]);
  assert.throws(() => normalizationArguments('photo', { width: 200, height: 900 }, '/i', '/o'), /too small/);
  assert.throws(() => normalizationArguments('logo', { width: 0, height: 10 }, '/i', '/o'), /dimensions/);
});

test('storage path is gallery scoped and content addressed', () => {
  assert.equal(storagePath(galleryA, sha), `${galleryA}/${sha}.jpg`);
  assert.throws(() => storagePath(galleryA, 'xyz'), /sha256/);
});

function bundle(overrides = {}) {
  return {
    manifestSha256: 'b'.repeat(64),
    entries: [
      { ...photo(), content_sha256: sha, storage_path: storagePath(galleryB, sha), bytes: 40000 },
      { ...entry({ name_ko: "O'Neil 갤러리" }), content_sha256: 'c'.repeat(64), storage_path: storagePath(galleryA, 'c'.repeat(64)), bytes: 20000 },
    ],
    skipped: [{ gallery_id: '33333333-3333-4333-8333-333333333333', reason: 'unsupported image type image/svg+xml' }],
    ...overrides,
  };
}

test('bundle validation rejects tampered paths and oversize objects', () => {
  assert.doesNotThrow(() => validateBundle(bundle()));
  const [first, second] = bundle().entries;
  assert.throws(() => validateBundle(bundle({ entries: [{ ...first, storage_path: `${galleryA}/${sha}.jpg` }, second] })), /storage_path/);
  assert.throws(() => validateBundle(bundle({ entries: [{ ...first, bytes: 262145 }, second] })), /bytes/);
  assert.throws(() => validateBundle(bundle({ entries: [] })), /empty/);
});

test('generated SQL is one idempotent transaction in gallery order with escaped text', () => {
  const sql = generateSql(bundle());
  assert.match(sql, /^begin;/);
  assert.match(sql, /commit;\n$/);
  assert.equal((sql.match(/insert into content\.gallery_profile_images/g) ?? []).length, 1);
  assert.match(sql, /on conflict \(gallery_id\) do update set/);
  assert.match(sql, /updated_at = now\(\)/);
  assert.ok(sql.indexOf(galleryA) < sql.indexOf(galleryB), 'rows are sorted by gallery id');
  assert.match(sql, /gallery_profile_images_missing_gallery/);
  assert.ok(!sql.includes("O'Neil 갤러리"), 'gallery names are not written; identity comes from gallery_id');
  assert.match(sql, /'Lawinc82 \/ Wikimedia Commons, CC BY-SA 4\.0'/);
  assert.match(sql, /, true, '/);
  assert.equal(generateSql(bundle()), sql, 'deterministic output');
});

test('generated SQL escapes single quotes in credits and source pages', () => {
  const [first, second] = bundle().entries;
  const sql = generateSql(bundle({ entries: [{ ...first, credit: "D'Arcy / Wikimedia Commons, CC BY 4.0", license: 'CC BY 4.0' }, second] }));
  assert.match(sql, /'D''Arcy \/ Wikimedia Commons, CC BY 4\.0'/);
});
