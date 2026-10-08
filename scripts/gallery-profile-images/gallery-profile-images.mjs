import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const sha256Hex = /^[0-9a-f]{64}$/;
const projectRef = /^[a-z0-9]{20}$/;
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
const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, '..', '..');
const productionRefFingerprintPath = join(repositoryRoot, 'scripts', 'staging-rehearsal', 'production-project-ref.sha256');

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

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

/**
 * Network-free validation of the reviewed manifest. Returns the entries unchanged.
 * `source_sha256` pins the exact source bytes that were reviewed.
 */
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
    requireValue(sha256Hex.test(entry.source_sha256 ?? ''), `Invalid source_sha256 for ${entry.gallery_id}`);
    requireValue(httpUrl.test(entry.source_page ?? '') && entry.source_page.length <= 2000, `Invalid source_page for ${entry.gallery_id}`);
    text(entry.license, `license for ${entry.gallery_id}`, 200);
    if (requiresAttribution(entry.license)) {
      text(entry.credit, `credit for ${entry.gallery_id}`, 300);
    } else {
      requireValue(entry.credit === null || (typeof entry.credit === 'string' && entry.credit.trim().length > 0 && [...entry.credit].length <= 300), `Invalid credit for ${entry.gallery_id}`);
    }
  }
  return entries;
}

/**
 * sips arguments that turn a source image into a 512x512 JPEG. The format options
 * must precede the geometry options: sips otherwise keeps a PNG source's format.
 */
export function normalizationArguments(kind, { width, height }, inputPath, outputPath) {
  requireValue(Number.isInteger(width) && Number.isInteger(height) && width > 0 && height > 0, 'Invalid image dimensions');
  const format = ['-s', 'format', 'jpeg', '-s', 'formatOptions', String(jpegQuality)];
  const output = [inputPath, '--out', outputPath];
  if (kind === 'logo') {
    return [
      ...format,
      '--resampleHeightWidthMax', String(logoFitSize),
      '--padToHeightWidth', String(outputSize), String(outputSize), '--padColor', 'FFFFFF',
      ...output,
    ];
  }
  requireValue(kind === 'photo', 'Invalid kind');
  requireValue(Math.min(width, height) >= minimumPhotoSide, 'Photo is too small');
  const scale = width >= height ? ['--resampleHeight', String(outputSize)] : ['--resampleWidth', String(outputSize)];
  return [...format, ...scale, '--cropToHeightWidth', String(outputSize), String(outputSize), ...output];
}

/** Raster formats accepted before handing bytes to the system image parser. */
export function sourceImageFormat(bytes) {
  const at = (offset, signature) => signature.every((byte, index) => bytes[offset + index] === byte);
  if (at(0, [0xff, 0xd8, 0xff])) return 'jpeg';
  if (at(0, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) return 'png';
  if (at(0, [0x47, 0x49, 0x46, 0x38])) return 'gif';
  if (at(0, [0x52, 0x49, 0x46, 0x46]) && at(8, [0x57, 0x45, 0x42, 0x50])) return 'webp';
  if (at(0, [0x00, 0x00, 0x01, 0x00])) return 'ico';
  return null;
}

export function isJpeg(bytes) {
  return sourceImageFormat(bytes) === 'jpeg';
}

export function storagePath(galleryId, contentSha256) {
  requireValue(uuid.test(galleryId), 'Invalid gallery_id');
  requireValue(sha256Hex.test(contentSha256), 'Invalid sha256');
  return `${galleryId}/${contentSha256}.jpg`;
}

/**
 * Pure upload-target guard. The operator names the environment; production must match
 * the committed production fingerprint and staging must not.
 */
export function validateUploadTarget({ environment, ref, supabaseUrl, productionRefSha256 }) {
  requireValue(environment === 'staging' || environment === 'production', 'Set GALLR_TARGET_ENVIRONMENT to staging or production');
  requireValue(projectRef.test(ref ?? ''), 'Set GALLR_EXPECTED_PROJECT_REF to the 20-character project reference');
  requireValue(supabaseUrl === `https://${ref}.supabase.co`, 'GALLR_SUPABASE_URL does not match the expected project');
  requireValue(sha256Hex.test(productionRefSha256 ?? ''), 'Missing committed production fingerprint');
  const isProduction = sha256(ref) === productionRefSha256;
  requireValue(isProduction === (environment === 'production'), `Project reference is not the ${environment} project`);
  return { environment, ref };
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

/**
 * One transaction that upserts curated metadata; identity comes from gallery_id only.
 * It fails unless every gallery and every uploaded object already exists in the target,
 * so a bundle uploaded to one project cannot publish rows in another.
 */
export function generateSql(bundle) {
  validateBundle(bundle);
  const entries = [...bundle.entries].sort((left, right) => left.gallery_id.localeCompare(right.gallery_id));
  const wanted = entries
    .map((entry) => `(${sqlText(entry.gallery_id)}::uuid, ${sqlText(entry.storage_path)})`)
    .join(',\n    ');
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
    ${wanted}
    ) as wanted(id, object_name)
    where not exists (select 1 from content.galleries as gallery where gallery.id = wanted.id)
  ) then
    raise exception 'gallery_profile_images_missing_gallery';
  end if;
  if exists (
    select 1
    from (values
    ${wanted}
    ) as wanted(id, object_name)
    where not exists (
      select 1 from storage.objects as object
      where object.bucket_id = '${bucket}' and object.name = wanted.object_name
    )
  ) then
    raise exception 'gallery_profile_images_missing_object';
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
  requireValue(/^https?:/.test(response.url), 'redirected to a non-http scheme');
  const declared = Number(response.headers.get('content-length') ?? 0);
  requireValue(declared <= maxSourceBytes, 'source image too large');
  const chunks = [];
  let total = 0;
  for await (const chunk of response.body) {
    total += chunk.length;
    requireValue(total <= maxSourceBytes, 'source image too large');
    chunks.push(chunk);
  }
  const bytes = Buffer.concat(chunks);
  requireValue(bytes.length > 0, 'empty source image');
  return bytes;
}

async function prepare(manifestPath, outDir) {
  const manifestBytes = readFileSync(manifestPath);
  const manifest = validateManifest(JSON.parse(manifestBytes.toString('utf8')));
  const output = requireOutsideRepository(outDir);
  requireValue(!existsSync(output), 'Output directory must not exist yet');
  mkdirSync(output, { mode: 0o700 });
  const work = mkdtempSync(join(tmpdir(), 'gallr-gallery-images-'));
  const entries = [];
  const skipped = [];
  try {
    for (const entry of manifest) {
      try {
        const bytes = await download(entry.image_url);
        requireValue(sha256(bytes) === entry.source_sha256, 'source image differs from the reviewed bytes');
        requireValue(sourceImageFormat(bytes) !== null, 'unsupported source image format');
        const source = join(work, `${entry.gallery_id}.source`);
        const normalized = join(work, `${entry.gallery_id}.jpg`);
        writeFileSync(source, bytes, { mode: 0o600, flag: 'wx' });
        execFileSync('/usr/bin/sips', normalizationArguments(entry.kind, imageDimensions(source), source, normalized), { stdio: 'ignore' });
        const dimensions = imageDimensions(normalized);
        requireValue(dimensions.width === outputSize && dimensions.height === outputSize, 'normalized image is not square');
        const jpeg = readFileSync(normalized);
        requireValue(isJpeg(jpeg), 'normalized image is not JPEG');
        requireValue(jpeg.length <= maxObjectBytes, 'normalized image too large');
        const contentSha256 = sha256(jpeg);
        const path = storagePath(entry.gallery_id, contentSha256);
        mkdirSync(join(output, entry.gallery_id), { mode: 0o700 });
        writeFileSync(join(output, path), jpeg, { mode: 0o600, flag: 'wx' });
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
    manifestSha256: sha256(manifestBytes),
    preparedAt: new Date().toISOString(),
    entries,
    skipped,
  };
  writeFileSync(join(output, 'bundle.json'), `${JSON.stringify(bundle, null, 2)}\n`, { mode: 0o600, flag: 'wx' });
  console.log(`prepared ${entries.length}, skipped ${skipped.length}`);
}

async function upload(bundlePath) {
  const bundle = validateBundle(JSON.parse(readFileSync(bundlePath, 'utf8')));
  const { ref } = validateUploadTarget({
    environment: process.env.GALLR_TARGET_ENVIRONMENT,
    ref: process.env.GALLR_EXPECTED_PROJECT_REF,
    supabaseUrl: process.env.GALLR_SUPABASE_URL,
    productionRefSha256: readFileSync(productionRefFingerprintPath, 'utf8').trim(),
  });
  const serviceKey = process.env.GALLR_SUPABASE_SERVICE_ROLE_KEY ?? '';
  requireValue(serviceKey.length > 0, 'Inject GALLR_SUPABASE_SERVICE_ROLE_KEY from 1Password');
  const root = dirname(resolve(bundlePath));
  for (const entry of bundle.entries) {
    const body = readFileSync(join(root, entry.storage_path));
    requireValue(sha256(body) === entry.content_sha256, `Content changed for ${entry.gallery_id}`);
    requireValue(isJpeg(body), `Not a JPEG for ${entry.gallery_id}`);
    const response = await fetch(`https://${ref}.supabase.co/storage/v1/object/${bucket}/${entry.storage_path}`, {
      method: 'POST',
      signal: AbortSignal.timeout(30000),
      headers: {
        authorization: `Bearer ${serviceKey}`,
        apikey: serviceKey,
        'content-type': 'image/jpeg',
        'cache-control': 'max-age=86400',
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
