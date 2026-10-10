import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import {
  generateSql,
  isJpeg,
  normalizationArguments,
  requiresAttribution,
  sourceImageFormat,
  storagePath,
  validateBundle,
  validateManifest,
  validateUploadTarget,
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
    source_sha256: 'd'.repeat(64),
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
    source_sha256: 'e'.repeat(64),
    source_page: 'https://commons.wikimedia.org/wiki/File:Ilmin_Museum_of_Art.jpg',
    license: 'CC BY-SA 4.0 (Wikimedia Commons)',
    credit: 'Lawinc82 / Wikimedia Commons, CC BY-SA 4.0 (cropped)',
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
  assert.throws(() => validateManifest([entry({ source_sha256: 'abc' })]), /source_sha256/);
  assert.throws(() => validateManifest([entry({ source_page: 'ftp://x' })]), /source_page/);
  assert.throws(() => validateManifest([entry({ license: ' ' })]), /license/);
  assert.throws(() => validateManifest([entry({ name_ko: '' })]), /name_ko/);
});

test('licensed images require a credit; official material may name its photographer', () => {
  assert.equal(requiresAttribution('official site'), false);
  assert.equal(requiresAttribution('CC0 (Wikimedia Commons)'), false);
  assert.equal(requiresAttribution('CC BY 3.0 (Wikimedia Commons)'), true);
  assert.equal(requiresAttribution('KOGL Type 1 (Wikimedia Commons)'), true);
  assert.throws(() => validateManifest([photo({ credit: null })]), /credit/);
  assert.throws(() => validateManifest([entry({ credit: ' ' })]), /credit/);
  assert.doesNotThrow(() => validateManifest([entry({ credit: 'Yoon Joonhwan / Johyun Gallery' })]));
  assert.doesNotThrow(() => validateManifest([photo({ license: 'CC0 (Wikimedia Commons)', credit: null })]));
});

test('format options precede geometry so PNG logos become JPEG', () => {
  assert.deepEqual(normalizationArguments('logo', { width: 180, height: 180 }, '/in.png', '/out.jpg'), [
    '-s', 'format', 'jpeg', '-s', 'formatOptions', '82',
    '--resampleHeightWidthMax', '416',
    '--padToHeightWidth', '512', '512', '--padColor', 'FFFFFF',
    '/in.png', '--out', '/out.jpg',
  ]);
  assert.deepEqual(normalizationArguments('photo', { width: 1600, height: 900 }, '/in.jpg', '/out.jpg').slice(0, 11), [
    '-s', 'format', 'jpeg', '-s', 'formatOptions', '82',
    '--resampleHeight', '512', '--cropToHeightWidth', '512', '512',
  ]);
  assert.deepEqual(normalizationArguments('photo', { width: 900, height: 1600 }, '/in.jpg', '/out.jpg').slice(6, 8), [
    '--resampleWidth', '512',
  ]);
  assert.throws(() => normalizationArguments('photo', { width: 200, height: 900 }, '/i', '/o'), /too small/);
  assert.throws(() => normalizationArguments('logo', { width: 0, height: 10 }, '/i', '/o'), /dimensions/);
});

test('only known raster formats reach the image parser and only JPEG is published', () => {
  const bytes = (...values) => Buffer.from(values);
  assert.equal(sourceImageFormat(bytes(0xff, 0xd8, 0xff, 0xe0)), 'jpeg');
  assert.equal(sourceImageFormat(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)), 'png');
  assert.equal(sourceImageFormat(Buffer.from('RIFF\0\0\0\0WEBPVP8 ')), 'webp');
  assert.equal(sourceImageFormat(bytes(0x00, 0x00, 0x01, 0x00, 0x01)), 'ico');
  assert.equal(sourceImageFormat(Buffer.from('<svg xmlns="http://www.w3.org/2000/svg">')), null);
  assert.equal(isJpeg(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)), false);
  assert.equal(isJpeg(bytes(0xff, 0xd8, 0xff, 0xdb)), true);
});

test('storage path is gallery scoped and content addressed', () => {
  assert.equal(storagePath(galleryA, sha), `${galleryA}/${sha}.jpg`);
  assert.throws(() => storagePath(galleryA, 'xyz'), /sha256/);
});

test('upload target must match the named environment and the committed production fingerprint', () => {
  const productionRef = 'p'.repeat(20);
  const stagingRef = 's'.repeat(20);
  const productionRefSha256 = createHash('sha256').update(productionRef).digest('hex');
  const target = (environment, ref, supabaseUrl = `https://${ref}.supabase.co`) =>
    validateUploadTarget({ environment, ref, supabaseUrl, productionRefSha256 });
  assert.deepEqual(target('production', productionRef), { environment: 'production', ref: productionRef });
  assert.deepEqual(target('staging', stagingRef), { environment: 'staging', ref: stagingRef });
  assert.throws(() => target('staging', productionRef), /not the staging project/);
  assert.throws(() => target('production', stagingRef), /not the production project/);
  assert.throws(() => target('production', productionRef, `https://${stagingRef}.supabase.co`), /does not match/);
  assert.throws(() => target(undefined, stagingRef), /GALLR_TARGET_ENVIRONMENT/);
  assert.throws(() => target('staging', 'short'), /20-character/);
  assert.throws(() => validateUploadTarget({ environment: 'staging', ref: stagingRef, supabaseUrl: `https://${stagingRef}.supabase.co`, productionRefSha256: '' }), /fingerprint/);
});

function bundle(overrides = {}) {
  return {
    manifestSha256: 'b'.repeat(64),
    entries: [
      { ...photo(), content_sha256: sha, storage_path: storagePath(galleryB, sha), bytes: 40000 },
      { ...entry({ name_ko: "O'Neil 갤러리" }), content_sha256: 'c'.repeat(64), storage_path: storagePath(galleryA, 'c'.repeat(64)), bytes: 20000 },
    ],
    skipped: [{ gallery_id: '33333333-3333-4333-8333-333333333333', reason: 'unsupported source image format' }],
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

test('generated SQL is one idempotent transaction bound to uploaded objects', () => {
  const sql = generateSql(bundle());
  assert.match(sql, /^begin;/);
  assert.match(sql, /commit;\n$/);
  assert.equal((sql.match(/insert into content\.gallery_profile_images/g) ?? []).length, 1);
  assert.match(sql, /on conflict \(gallery_id\) do update set/);
  assert.match(sql, /updated_at = now\(\)/);
  assert.ok(sql.indexOf(galleryA) < sql.indexOf(galleryB), 'rows are sorted by gallery id');
  assert.match(sql, /gallery_profile_images_missing_gallery/);
  assert.match(sql, /gallery_profile_images_missing_object/);
  assert.match(sql, /object\.bucket_id = 'gallery-profile-images'/);
  assert.ok(!sql.includes("O'Neil 갤러리"), 'gallery names are not written; identity comes from gallery_id');
  assert.match(sql, /'Lawinc82 \/ Wikimedia Commons, CC BY-SA 4\.0 \(cropped\)', true, '/);
  assert.equal(generateSql(bundle()), sql, 'deterministic output');
});

test('generated SQL escapes single quotes in credits', () => {
  const [first, second] = bundle().entries;
  const sql = generateSql(bundle({ entries: [{ ...first, credit: "D'Arcy / Wikimedia Commons, CC BY 4.0", license: 'CC BY 4.0' }, second] }));
  assert.match(sql, /'D''Arcy \/ Wikimedia Commons, CC BY 4\.0'/);
});
