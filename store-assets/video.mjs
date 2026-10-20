// Cuts the store videos with ffmpeg:
//  - App previews: shots from record-preview.sh's recording, each playing inside a straight device on
//    the screenshot set's black field under its caption (real UI, captions per locale), then H.264
//    High ≤ L4.0 at 30 fps with the silent stereo AAC track App Store Connect requires. The Play promo
//    uses the same cut at 1080×1920.
//  - Header video: a pan of exactly one wall period over 30 s, so the loop has no seam.
// Usage: node video.mjs [preview|header|all]
import { execFile } from 'node:child_process';
import { mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { closeBrowser } from './render.mjs';
import { renderWall, HEADER } from './creatives.mjs';
import { IPAD, IPHONE } from './targets.mjs';
import { renderPreviewBackground } from './creative.mjs';

const run = promisify(execFile);
const ROOT = dirname(fileURLToPath(import.meta.url));
const LOCALES = ['ko', 'en-US'];
const FPS = 30;
/** How far before a shot's mark the cut starts reading, so a still screen has a frame to carry. */
const SEEK_LEAD_SECONDS = 10;

/** The storyboard: the test's marks, how long each shot runs, and whose caption it carries. */
export const SHOTS = [
  { mark: 'home', seconds: 4, copy: '01_Home' },
  { mark: 'note', seconds: 4, copy: '05_CuratorNote' },
  { mark: 'list', seconds: 4, copy: '03_ClosingThisWeek' },
  { mark: 'map', seconds: 4, copy: '04_Map' },
];

const PREVIEWS = [
  { name: 'iPhone', width: 886, height: 1920, layout: 'phone', device: IPHONE, out: (locale) => `dist/video/${locale}/app-preview-iphone.mp4` },
  { name: 'iPad', width: 1200, height: 1600, layout: 'tablet', device: IPAD, out: (locale) => `dist/video/${locale}/app-preview-ipad.mp4` },
  // Play shows the iOS recording without the iOS status bar, as the Play stills do (targets.mjs).
  { name: 'Play promo', width: 1080, height: 1920, layout: 'phone', device: IPHONE, cropStatusBar: 186, out: (locale) => `dist/video/${locale}/play-promo.mp4` },
];

const ffmpeg = (args) => run('ffmpeg', ['-v', 'error', '-y', ...args], { maxBuffer: 1 << 24 });

async function buildPreview(preview, locale, copy, manifest) {
  const source = join(ROOT, 'captures/preview', locale, preview.device);
  const recording = join(source, 'recording.mov');
  const marksPath = join(source, 'marks.json');
  if (!existsSync(recording) || !existsSync(marksPath)) {
    console.warn(`skip ${preview.name} ${locale}: run record-preview.sh first`);
    return;
  }
  const marks = JSON.parse(await readFile(marksPath, 'utf8'));
  const work = join(ROOT, 'build/video', locale, preview.name);
  await mkdir(work, { recursive: true });
  const crop = preview.cropStatusBar ? `crop=iw:ih-${preview.cropStatusBar}:0:${preview.cropStatusBar}` : 'null';

  const segments = [];
  for (const [index, shot] of SHOTS.entries()) {
    const at = marks[shot.mark];
    if (at === undefined) throw new Error(`${preview.name} ${locale}: marker ${shot.mark} missing in ${relative(ROOT, marksPath)}`);
    const background = join(work, `${index}-background.png`);
    const screen = await renderPreviewBackground({ copy, slotId: shot.copy, locale, target: preview, out: background });
    // The screen's rounded corners, as an alpha mask the video is cut to.
    const mask = join(work, `${index}-mask.png`);
    const r = screen.radius;
    await ffmpeg([
      '-f', 'lavfi', '-i', `color=black:s=${screen.width}x${screen.height}`, '-frames:v', '1',
      '-vf', `format=gray,geq=lum='255*lte(hypot(max(0,max(${r}-X,X-(W-1-${r}))),max(0,max(${r}-Y,Y-(H-1-${r})))),${r})'`,
      mask,
    ]);
    const segment = join(work, `${index}.mp4`);
    // The simulator records a frame only when the screen changes, so a shot that starts on a still screen
    // has no frame at its mark. Seek to a lead before the mark (input seeking keeps the marks' timeline;
    // decoding the whole recording drifts), resample to a constant rate, then trim the lead away: the fps
    // filter carries the last frame forward, so the shot opens on the screen as it stood.
    const lead = Math.min(at, SEEK_LEAD_SECONDS);
    await ffmpeg([
      '-loop', '1', '-framerate', String(FPS), '-i', background,
      '-ss', String(at - lead), '-t', String(lead + shot.seconds), '-i', recording,
      '-loop', '1', '-i', mask,
      '-filter_complex',
      `[1:v]fps=${FPS},trim=start=${lead}:duration=${shot.seconds},setpts=PTS-STARTPTS,${crop},scale=${screen.width}:${screen.height},setsar=1,format=yuva420p,tpad=stop_mode=clone:stop_duration=${shot.seconds}[ui];` +
        `[2:v]format=gray,scale=${screen.width}:${screen.height}[m];[ui][m]alphamerge[cut];` +
        `[0:v][cut]overlay=${screen.x}:${screen.y}:shortest=1,trim=duration=${shot.seconds},setpts=PTS-STARTPTS,format=yuv420p[out]`,
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
