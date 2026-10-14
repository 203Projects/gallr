// Cuts the store videos with ffmpeg:
//  - App previews: shots from record-preview.sh's recording, each placed in the screenshot layout under
//    its caption (real UI, captions per locale), then H.264 High ≤ L4.0 at 30 fps with the silent stereo
//    AAC track App Store Connect requires. The Play promo uses the same cut at 1080×1920.
//  - Header video: a pan of exactly one wall period over 30 s, so the loop has no seam.
// Usage: node video.mjs [preview|header|all]
import { execFile } from 'node:child_process';
import { mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { renderTemplate, closeBrowser } from './render.mjs';
import { renderWall, HEADER } from './creatives.mjs';
import { SCREENSHOT_TARGETS } from './targets.mjs';

const run = promisify(execFile);
const ROOT = dirname(fileURLToPath(import.meta.url));
const LOCALES = ['ko', 'en-US'];
const FPS = 30;

/** The storyboard: the test's marks, how long each shot runs, and whose caption it carries. */
export const SHOTS = [
  { mark: 'home', seconds: 3, copy: '01_Home' },
  { mark: 'note', seconds: 3.5, copy: '05_CuratorNote' },
  { mark: 'list', seconds: 4, copy: '02_ClosingThisWeek' },
  { mark: 'map', seconds: 4, copy: '03_Map' },
  // The preview ends on the composer and loops back to Home with a cut, like the cuts between shots.
  // Returning to Home on camera sent the app to the background during recording on iPhone.
  { mark: 'route', seconds: 6, copy: '04_Route' },
];

const PREVIEWS = [
  { name: 'iPhone', width: 886, height: 1920, device: 'Gallr Store iPhone 17 Pro Max', layoutOf: 'iPhone 6.9', out: (locale) => `dist/video/${locale}/app-preview-iphone.mp4` },
  { name: 'iPad', width: 1200, height: 1600, device: 'Gallr Store iPad Pro 13 M5', layoutOf: 'iPad 13', out: (locale) => `dist/video/${locale}/app-preview-ipad.mp4` },
  // Play shows the iOS recording without the iOS status bar, as the Play stills do (targets.mjs).
  { name: 'Play promo', width: 1080, height: 1920, device: 'Gallr Store iPhone 17 Pro Max', layoutOf: 'Play phone', cropTop: 186, out: (locale) => `dist/video/${locale}/play-promo.mp4` },
];

const ffmpeg = (args) => run('ffmpeg', ['-v', 'error', '-y', ...args], { maxBuffer: 1 << 24 });
const even = (value) => Math.round(value / 2) * 2;

/** Where the screen sits on a canvas, matching templates/screenshot.html (vw-based, border inside). */
export function screenRect({ width }, layout) {
  const vw = width / 100;
  const border = Math.max(2, 0.23 * vw);
  const outer = layout.captureWidth * vw;
  const left = (width - outer) / 2;
  const top = layout.captureTop * vw;
  return { x: Math.round(left + border), y: Math.round(top + border), width: even(outer - 2 * border) };
}

async function buildPreview(preview, locale, copy, manifest) {
  const source = join(ROOT, 'captures/preview', locale, preview.device);
  const recording = join(source, 'recording.mov');
  const marksPath = join(source, 'marks.json');
  if (!existsSync(recording) || !existsSync(marksPath)) {
    console.warn(`skip ${preview.name} ${locale}: run record-preview.sh first`);
    return;
  }
  const marks = JSON.parse(await readFile(marksPath, 'utf8'));
  const target = SCREENSHOT_TARGETS.find((candidate) => candidate.name === preview.layoutOf);
  const layout = target.layout;
  const rect = screenRect(preview, layout);
  const work = join(ROOT, 'build/video', locale, preview.name);
  await mkdir(work, { recursive: true });

  const segments = [];
  for (const [index, shot] of SHOTS.entries()) {
    const at = marks[shot.mark];
    if (at === undefined) throw new Error(`${preview.name} ${locale}: marker ${shot.mark} missing in ${relative(ROOT, marksPath)}`);
    const slot = copy.screenshots.find((candidate) => candidate.id === shot.copy);
    const position = copy.screenshots.indexOf(slot);
    // The shot's first frame stands in the background so the caption layout matches the stills.
    const still = join(work, `${index}-first.png`);
    const crop = preview.cropTop ? `crop=iw:ih-${preview.cropTop}:0:${preview.cropTop}` : 'null';
    await ffmpeg(['-ss', String(at), '-i', recording, '-frames:v', '1', '-vf', crop, still]);
    const background = join(work, `${index}-background.png`);
    await renderTemplate({
      template: join(ROOT, 'templates/screenshot.html'),
      width: preview.width,
      height: preview.height,
      out: background,
      data: {
        lang: locale === 'ko' ? 'ko' : 'en',
        index: String(position + 1).padStart(2, '0'),
        eyebrow: slot.eyebrow,
        headline: slot[locale].headline,
        sub: slot[locale].sub,
        capture: pathToFileURL(still).href,
        connector: 'none',
        textTop: layout.textTop,
        headlineSize: layout.headlineSize[locale],
        captureWidth: layout.captureWidth,
        captureTop: layout.captureTop,
        connectorTop: layout.connectorTop,
      },
    });
    const segment = join(work, `${index}.mp4`);
    await ffmpeg([
      '-loop', '1', '-framerate', String(FPS), '-i', background,
      '-ss', String(at), '-t', String(shot.seconds), '-i', recording,
      '-filter_complex',
      `[1:v]fps=${FPS},${crop},scale=${rect.width}:-2,setsar=1,tpad=stop_mode=clone:stop_duration=${shot.seconds}[ui];` +
        `[0:v][ui]overlay=${rect.x}:${rect.y},trim=duration=${shot.seconds},setpts=PTS-STARTPTS,format=yuv420p[out]`,
      '-map', '[out]', '-r', String(FPS), '-c:v', 'libx264', '-crf', '10', '-preset', 'medium', segment,
    ]);
    segments.push(segment);
  }

  const list = join(work, 'segments.txt');
  await writeFile(list, segments.map((path) => `file '${path}'`).join('\n'));
  const out = join(ROOT, preview.out(locale));
  await mkdir(dirname(out), { recursive: true });
  const seconds = SHOTS.reduce((sum, shot) => sum + shot.seconds, 0);
  await ffmpeg([
    '-f', 'concat', '-safe', '0', '-i', list,
    '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo',
    '-t', String(seconds), '-map', '0:v', '-map', '1:a',
    '-c:v', 'libx264', '-profile:v', 'high', '-level:v', '4.0', '-pix_fmt', 'yuv420p', '-r', String(FPS),
    '-b:v', '11M', '-maxrate', '12M', '-bufsize', '24M',
    '-c:a', 'aac', '-b:a', '256k', '-ar', '48000', '-ac', '2',
    '-movflags', '+faststart', out,
  ]);
  manifest.push({
    kind: 'video',
    file: relative(ROOT, out),
    spec: { width: preview.width, height: preview.height, minSeconds: 15, maxSeconds: 30, maxFps: 30, audio: true },
  });
  console.log(`ok ${relative(ROOT, out)}`);
}

async function buildHeaderVideo(locale, manifest) {
  const catalogue = JSON.parse(await readFile(join(ROOT, 'data/catalogue.json'), 'utf8'));
  const work = join(ROOT, 'build/video', locale);
  await mkdir(work, { recursive: true });
  const strip = join(work, 'wall-strip.png');
  // Probe the period first, then render a strip one period plus one canvas wide.
  const probe = await renderWall({ root: ROOT, catalogue, locale, width: HEADER.width, copies: 2, offset: 0, out: strip });
  const period = probe.period;
  if (period < HEADER.width) throw new Error(`wall period ${period}px is shorter than the canvas; add works to WALL_WORKS`);
  await renderWall({ root: ROOT, catalogue, locale, width: period + HEADER.width, copies: 2, offset: 0, out: strip });
  // Kept inside [0, period) so the crop's x never goes negative.
  const start = (((Math.round(probe.heroCentre - HEADER.width / 2)) % period) + period) % period;
  const seconds = 30;
  const out = join(ROOT, `dist/video/${locale}/header-21x9.mp4`);
  await mkdir(dirname(out), { recursive: true });
  await ffmpeg([
    '-loop', '1', '-framerate', String(FPS), '-t', String(seconds), '-i', strip,
    '-vf', `crop=${HEADER.width}:${HEADER.height}:x='mod(${start}+t*${period / seconds}\\,${period})':y=0,format=yuv420p`,
    '-r', String(FPS), '-c:v', 'libx264', '-profile:v', 'high', '-crf', '14', '-preset', 'slow',
    '-movflags', '+faststart', out,
  ]);
  manifest.push({
    kind: 'video',
    file: relative(ROOT, out),
    spec: { width: HEADER.width, height: HEADER.height, minSeconds: 5, maxSeconds: 30, maxFps: 60 },
  });
  console.log(`ok ${relative(ROOT, out)} (period ${period}px, ${(period / seconds).toFixed(0)} px/s)`);
}

async function main(which) {
  const copy = JSON.parse(await readFile(join(ROOT, 'copy.json'), 'utf8'));
  const manifestPath = join(ROOT, 'dist/manifest.json');
  const previous = existsSync(manifestPath) ? JSON.parse(await readFile(manifestPath, 'utf8')) : [];
  const manifest = [];
  try {
    for (const locale of LOCALES) {
      if (which === 'all' || which === 'header') await buildHeaderVideo(locale, manifest);
      if (which === 'all' || which === 'preview') {
        for (const preview of PREVIEWS) await buildPreview(preview, locale, copy, manifest);
      }
    }
  } finally {
    await closeBrowser();
  }
  const rebuilt = new Set(manifest.map((entry) => entry.file));
  await mkdir(dirname(manifestPath), { recursive: true });
  await writeFile(manifestPath, `${JSON.stringify([...previous.filter((entry) => !rebuilt.has(entry.file)), ...manifest], null, 2)}\n`);
  await rm(join(ROOT, 'build'), { recursive: true, force: true });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) await main(process.argv[2] ?? 'all');
