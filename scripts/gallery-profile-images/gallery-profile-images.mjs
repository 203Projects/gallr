import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const sha256Hex = /^[0-9a-f]{64}$/;
const httpUrl = /^https?:\/\/[^\s]+$/;
const kinds = new Set(['logo', 'photo']);
const maxManifestEntries = 500;
const maxSourceBytes = 20 * 1024 * 1024;
const maxObjectBytes = 262144;
const outputSize = 512;
const logoFitSize = 416;
const minimumPhotoSide = 300;
const jpegQuality = 82;
const bucket = 'gallery-profile-images';
const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}

function text(value, label, maxLength) {
  requireValue(typeof value === 'string' && value.trim().length > 0 && [...value].length <= maxLength && !value.includes('\0'), `Invalid ${label}`);
}

/** CC0 and the gallery's own official material need no credit; every other license does. */
export function requiresAttribution(license) {
  return !/^official site$/i.test(license) && !/^CC0\b/i.test(license);
}

/** Network-free validation of the reviewed manifest. Returns the entries unchanged. */
export function validateManifest(entries) {
  requireValue(Array.isArray(entries), 'Manifest must be an array');
  requireValue(entries.length > 0, 'Manifest is empty');
  requireValue(entries.length <= maxManifestEntries, 'Manifest is too large');
  const seen = new Set();
  for (const entry of entries) {
    requireValue(entry && uuid.test(entry.gallery_id ?? ''), 'Invalid gallery_id');
    requireValue(!seen.has(entry.gallery_id), `Duplicate gallery ${entry.gallery_id}`);
    seen.add(entry.gallery_id);
    text(entry.name_ko, `name_ko for ${entry.gallery_id}`, 300);
    requireValue(typeof entry.name_en === 'string', `Invalid name_en for ${entry.gallery_id}`);
    requireValue(kinds.has(entry.kind), `Invalid kind for ${entry.gallery_id}`);
    requireValue(httpUrl.test(entry.image_url ?? ''), `Invalid image_url for ${entry.gallery_id}`);
    requireValue(httpUrl.test(entry.source_page ?? '') && entry.source_page.length <= 2000, `Invalid source_page for ${entry.gallery_id}`);
    text(entry.license, `license for ${entry.gallery_id}`, 200);
    if (requiresAttribution(entry.license)) {
      text(entry.credit, `credit for ${entry.gallery_id}`, 300);
    } else {
      requireValue(entry.credit === null, `Unexpected credit for ${entry.gallery_id}`);
    }
  }
  return entries;
}

/** sips arguments that turn a source image into a 512x512 JPEG. */
export function normalizationArguments(kind, { width, height }, inputPath, outputPath) {
  requireValue(Number.isInteger(width) && Number.isInteger(height) && width > 0 && height > 0, 'Invalid image dimensions');
  const output = ['-s', 'format', 'jpeg', '-s', 'formatOptions', String(jpegQuality), inputPath, '--out', outputPath];
  if (kind === 'logo') {
    return [
      '--resampleHeightWidthMax', String(logoFitSize),
      '--padToHeightWidth', String(outputSize), String(outputSize), '--padColor', 'FFFFFF',
      ...output,
    ];
  }
  requireValue(kind === 'photo', 'Invalid kind');
  requireValue(Math.min(width, height) >= minimumPhotoSide, 'Photo is too small');
  const scale = width >= height ? ['--resampleHeight', String(outputSize)] : ['--resampleWidth', String(outputSize)];
  return [...scale, '--cropToHeightWidth', String(outputSize), String(outputSize), ...output];
}

export function storagePath(galleryId, contentSha256) {
  requireValue(uuid.test(galleryId), 'Invalid gallery_id');
  requireValue(sha256Hex.test(contentSha256), 'Invalid sha256');
  return `${galleryId}/${contentSha256}.jpg`;
}

/** Validates a prepared bundle before upload or SQL generation. */
export function validateBundle(bundle) {
  requireValue(bundle && sha256Hex.test(bundle.manifestSha256 ?? ''), 'Invalid bundle manifest fingerprint');
  requireValue(Array.isArray(bundle.entries) && bundle.entries.length > 0, 'Bundle is empty');
  requireValue(Array.isArray(bundle.skipped), 'Invalid bundle skipped list');
  validateManifest(bundle.entries.map(({ content_sha256, storage_path, bytes, ...entry }) => entry));
  for (const entry of bundle.entries) {
    requireValue(entry.storage_path === storagePath(entry.gallery_id, entry.content_sha256), `Invalid storage_path for ${entry.gallery_id}`);
    requireValue(Number.isInteger(entry.bytes) && entry.bytes > 0 && entry.bytes <= maxObjectBytes, `Invalid bytes for ${entry.gallery_id}`);
  }
  return bundle;
}

function sqlText(value) {
  return value === null ? 'null' : `'${value.replaceAll("'", "''")}'`;
}

/** One transaction that upserts curated metadata; identity comes from gallery_id only. */
export function generateSql(bundle) {
  validateBundle(bundle);
  const entries = [...bundle.entries].sort((left, right) => left.gallery_id.localeCompare(right.gallery_id));
  const ids = entries.map((entry) => `(${sqlText(entry.gallery_id)}::uuid)`).join(',\n    ');
  const rows = entries
    .map((entry) => `  (${[
      `${sqlText(entry.gallery_id)}::uuid`,
      sqlText(entry.kind),
      sqlText(entry.storage_path),
      sqlText(entry.source_page),
      sqlText(entry.license),
      sqlText(entry.credit),
      String(requiresAttribution(entry.license)),
      sqlText(entry.content_sha256),
    ].join(', ')})`)
    .join(',\n');
  return `begin;
-- Gallery profile images bundle ${bundle.manifestSha256}
do $guard$
begin
  if exists (
    select 1
    from (values
    ${ids}
    ) as wanted(id)
    where not exists (select 1 from content.galleries as gallery where gallery.id = wanted.id)
  ) then
    raise exception 'gallery_profile_images_missing_gallery';
  end if;
end
$guard$;
insert into content.gallery_profile_images (
  gallery_id, kind, storage_path, source_page_url, license, credit, requires_attribution, content_sha256
)
values
${rows}
on conflict (gallery_id) do update set
  kind = excluded.kind,
  storage_path = excluded.storage_path,
  source_page_url = excluded.source_page_url,
  license = excluded.license,
  credit = excluded.credit,
  requires_attribution = excluded.requires_attribution,
  content_sha256 = excluded.content_sha256,
  updated_at = now();
commit;
`;
}

function requireOutsideRepository(path) {
  const absolute = resolve(path);
  requireValue(absolute !== repositoryRoot && !absolute.startsWith(repositoryRoot + sep), 'Output must be outside the checkout');
  return absolute;
}

function imageDimensions(path) {
  const output = execFileSync('/usr/bin/sips', ['-g', 'pixelWidth', '-g', 'pixelHeight', path], { encoding: 'utf8' });
  const width = Number(/pixelWidth: (\d+)/.exec(output)?.[1]);
  const height = Number(/pixelHeight: (\d+)/.exec(output)?.[1]);
  return { width, height };
}

async function download(url) {
  const response = await fetch(url, {
    redirect: 'follow',
    signal: AbortSignal.timeout(30000),
    headers: { 'user-agent': 'gallr-gallery-profile-images/1.0 (+https://gallrmap.com)' },
  });
  requireValue(response.ok, `HTTP ${response.status}`);
  const type = (response.headers.get('content-type') ?? '').split(';')[0].trim().toLowerCase();
  requireValue(type.startsWith('image/') && type !== 'image/svg+xml', `unsupported image type ${type || 'unknown'}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  requireValue(bytes.length > 0 && bytes.length <= maxSourceBytes, 'source image size out of range');
  return { bytes, type };
}

async function prepare(manifestPath, outDir) {
  const manifestBytes = readFileSync(manifestPath);
  const manifest = validateManifest(JSON.parse(manifestBytes.toString('utf8')));
  const output = requireOutsideRepository(outDir);
  if (existsSync(output)) {
    requireValue(!lstatSync(output).isSymbolicLink() && lstatSync(output).isDirectory(), 'Output must be a real directory');
    requireValue(readdirSync(output).length === 0, 'Output directory must be empty');
  }
  mkdirSync(output, { recursive: true, mode: 0o700 });
  const work = mkdtempSync(join(tmpdir(), 'gallr-gallery-images-'));
  const entries = [];
  const skipped = [];
  try {
    for (const entry of manifest) {
      try {
        const { bytes } = await download(entry.image_url);
        const source = join(work, `${entry.gallery_id}.source`);
        const normalized = join(work, `${entry.gallery_id}.jpg`);
        writeFileSync(source, bytes, { mode: 0o600 });
        execFileSync('/usr/bin/sips', normalizationArguments(entry.kind, imageDimensions(source), source, normalized), { stdio: 'ignore' });
        const dimensions = imageDimensions(normalized);
        requireValue(dimensions.width === outputSize && dimensions.height === outputSize, 'normalized image is not square');
        const jpeg = readFileSync(normalized);
        requireValue(jpeg.length <= maxObjectBytes, 'normalized image too large');
        const contentSha256 = createHash('sha256').update(jpeg).digest('hex');
        const path = storagePath(entry.gallery_id, contentSha256);
        mkdirSync(join(output, entry.gallery_id), { recursive: true, mode: 0o700 });
        writeFileSync(join(output, path), jpeg, { mode: 0o600 });
        entries.push({ ...entry, content_sha256: contentSha256, storage_path: path, bytes: jpeg.length });
        console.log(`ok ${entry.gallery_id} ${entry.kind}`);
      } catch (error) {
        skipped.push({ gallery_id: entry.gallery_id, reason: error.message });
        console.log(`skip ${entry.gallery_id}: ${error.message}`);
      }
    }
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
  const bundle = {
    manifestSha256: createHash('sha256').update(manifestBytes).digest('hex'),
    preparedAt: new Date().toISOString(),
    entries,
    skipped,
  };
  writeFileSync(join(output, 'bundle.json'), `${JSON.stringify(bundle, null, 2)}\n`, { mode: 0o600 });
  console.log(`prepared ${entries.length}, skipped ${skipped.length}`);
}

async function upload(bundlePath) {
  const bundle = validateBundle(JSON.parse(readFileSync(bundlePath, 'utf8')));
  const supabaseUrl = process.env.GALLR_SUPABASE_URL ?? '';
  const serviceKey = process.env.GALLR_SUPABASE_SERVICE_ROLE_KEY ?? '';
  const expectedRef = process.env.GALLR_EXPECTED_PROJECT_REF ?? '';
  requireValue(/^[a-z0-9]{20}$/.test(expectedRef), 'Set GALLR_EXPECTED_PROJECT_REF');
  requireValue(supabaseUrl === `https://${expectedRef}.supabase.co`, 'GALLR_SUPABASE_URL does not match the expected project');
  requireValue(serviceKey.length > 0, 'Inject GALLR_SUPABASE_SERVICE_ROLE_KEY from 1Password');
  const root = dirname(resolve(bundlePath));
  for (const entry of bundle.entries) {
    const body = readFileSync(join(root, entry.storage_path));
    requireValue(createHash('sha256').update(body).digest('hex') === entry.content_sha256, `Content changed for ${entry.gallery_id}`);
    const response = await fetch(`${supabaseUrl}/storage/v1/object/${bucket}/${entry.storage_path}`, {
      method: 'POST',
      signal: AbortSignal.timeout(30000),
      headers: {
        authorization: `Bearer ${serviceKey}`,
        apikey: serviceKey,
        'content-type': 'image/jpeg',
        'cache-control': 'max-age=31536000',
        'x-upsert': 'false',
      },
      body,
    });
    const alreadyStored = response.status === 409 || (response.status === 400 && /already exists/i.test(await response.clone().text()));
    requireValue(response.ok || alreadyStored, `Upload failed for ${entry.gallery_id} with HTTP ${response.status}`);
    console.log(`${alreadyStored ? 'exists' : 'uploaded'} ${entry.storage_path}`);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [mode, first, second, ...extra] = process.argv.slice(2);
    requireValue(extra.length === 0, 'Too many arguments');
    if (mode === 'validate' && first && !second) {
      const count = validateManifest(JSON.parse(readFileSync(first, 'utf8'))).length;
      console.log(`manifest valid: ${count} entries`);
    } else if (mode === 'prepare' && first && second) {
      await prepare(first, second);
    } else if (mode === 'upload' && first && !second) {
      await upload(first);
    } else if (mode === 'sql' && first && !second) {
      process.stdout.write(generateSql(JSON.parse(readFileSync(first, 'utf8'))));
    } else {
      throw new Error('Usage: validate <manifest> | prepare <manifest> <outDir> | upload <bundle.json> | sql <bundle.json>');
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
