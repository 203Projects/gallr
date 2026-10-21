// Checks rendered store assets against the App Store Connect and Google Play specs
// (docs/superpowers/specs/2026-10-14-store-creatives-refresh-design.md).
//
// CLI: `node validate.mjs` checks every entry in dist/manifest.json, which build.mjs and video.mjs write;
// entry paths are relative to store-assets/.
import { execFile } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const run = promisify(execFile);
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
// PNG colour types that carry an alpha channel: grey + alpha (4) and RGBA (6).
const ALPHA_COLOUR_TYPES = new Set([4, 6]);

export async function checkImage(path, { width, height }) {
  const problems = [];
  const bytes = await readFile(path);
  if (!bytes.subarray(0, 8).equals(PNG_SIGNATURE)) {
    return { ok: false, problems: [`${path}: not a PNG`] };
  }
  const actualWidth = bytes.readUInt32BE(16);
  const actualHeight = bytes.readUInt32BE(20);
  const colourType = bytes[25];
  if (actualWidth !== width || actualHeight !== height) {
    problems.push(`expected ${width}x${height}, got ${actualWidth}x${actualHeight}`);
  }
  if (ALPHA_COLOUR_TYPES.has(colourType) || chunkTypes(bytes).has('tRNS')) {
    problems.push('alpha channel present (App Store Connect rejects transparency)');
  }
  return { ok: problems.length === 0, problems };
}

// Chunk types in file order; searching the raw bytes for "tRNS" could match compressed image data.
function chunkTypes(bytes) {
  const types = new Set();
  for (let offset = 8; offset + 8 <= bytes.length; ) {
    const length = bytes.readUInt32BE(offset);
    const type = bytes.toString('latin1', offset + 4, offset + 8);
    types.add(type);
    if (type === 'IEND') break;
    offset += 12 + length;
  }
  return types;
}

export async function checkVideo(path, { width, height, minSeconds, maxSeconds, maxFps, audio = false }) {
  const problems = [];
  const { stdout } = await run('ffprobe', ['-v', 'error', '-show_streams', '-show_format', '-of', 'json', path]);
  const probe = JSON.parse(stdout);
  const video = probe.streams.find((stream) => stream.codec_type === 'video');
  if (!video) return { ok: false, problems: ['no video stream'] };

  if (video.width !== width || video.height !== height) {
    problems.push(`expected ${width}x${height}, got ${video.width}x${video.height}`);
  }
  if (video.codec_name !== 'h264') problems.push(`codec ${video.codec_name}, expected h264`);
  if (video.profile && !/High/.test(video.profile)) problems.push(`profile ${video.profile}, expected High`);
  if (video.level > 40 && maxFps <= 30) problems.push(`level ${video.level / 10} exceeds 4.0`);
  const [num, den] = video.avg_frame_rate.split('/').map(Number);
  const fps = Math.round(num / (den || 1));
  if (fps > maxFps) problems.push(`${fps} fps exceeds ${maxFps}`);
  const seconds = Number(probe.format.duration);
  if (seconds < minSeconds - 0.05 || seconds > maxSeconds + 0.05) {
    problems.push(`duration ${seconds.toFixed(1)} s outside ${minSeconds}–${maxSeconds} s`);
  }
  if (audio) {
    const track = probe.streams.find((stream) => stream.codec_type === 'audio');
    if (!track || track.channels !== 2) problems.push('no stereo audio track (App Store Connect requires one)');
    else if (track.codec_name !== 'aac') problems.push(`audio codec ${track.codec_name}, expected aac`);
  }
  return { ok: problems.length === 0, problems };
}

/**
 * Every composed still the targets should hold for each locale and slot that is not on disk. The
 * manifest only lists what a run produced, so a slot a run skipped would otherwise pass unnoticed.
 */
export function missingStills({ targets, locales, slots }) {
  const missing = [];
  for (const target of targets) {
    for (const locale of locales) {
      for (const slot of slots) {
        const path = target.out(locale, slot);
        if (!existsSync(path)) missing.push(path);
      }
    }
  }
  return missing;
}

async function validateManifest(rootDir) {
  const manifestPath = join(rootDir, 'dist', 'manifest.json');
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  let failures = 0;
  for (const entry of manifest) {
    const path = join(rootDir, entry.file);
    const result = !existsSync(path)
      ? { ok: false, problems: ['missing'] }
      : entry.kind === 'video'
        ? await checkVideo(path, entry.spec)
        : await checkImage(path, entry.spec);
    if (!result.ok) {
      failures += 1;
      console.log(`FAIL ${relative(rootDir, path)}: ${result.problems.join('; ')}`);
    }
  }
  console.log(`${manifest.length - failures}/${manifest.length} assets pass`);

  const { SCREENSHOT_TARGETS } = await import('./targets.mjs');
  const copy = JSON.parse(await readFile(join(rootDir, 'copy.json'), 'utf8'));
  const missing = missingStills({
    targets: SCREENSHOT_TARGETS,
    locales: ['ko', 'en-US'],
    slots: copy.screenshots.map((slot) => slot.id),
  });
  for (const path of missing) console.log(`MISSING ${relative(rootDir, path)}`);
  console.log(missing.length === 0 ? 'screenshot sets complete' : `${missing.length} screenshots missing`);
  return failures === 0 && missing.length === 0;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exitCode = (await validateManifest(dirname(fileURLToPath(import.meta.url)))) ? 0 : 1;
}
