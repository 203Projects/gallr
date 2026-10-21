// The screenshot set: six frames after the live listing (black field, heavy Korean headlines, an
// italic English line) with one core feature per frame and its real screen shown large. Frames 1 and
// 2 are one panorama: the Home screen tilts across the edge between them. Text names the feature only;
// exhibitions appear in the screens, never in the copy. Captions live in copy.json.
//
// Every canvas is laid out in 1320-wide units (targets.mjs) and rendered at zoom = width / 1320.
import { execFile } from 'node:child_process';
import { mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { openTemplate, capturePage } from './render.mjs';
import { LAYOUT_WIDTH } from './targets.mjs';

const run = promisify(execFile);
const ROOT = dirname(fileURLToPath(import.meta.url));
const CAPTURE_SIZE = { phone: { width: 1320, height: 2868 }, tablet: { width: 2064, height: 2752 } };

/**
 * Where each frame's screen goes, in layout units, by slot id. Fragment rectangles are in capture
 * pixels; a target that crops the status bar (Play) moves them up by the crop.
 */
const COMPOSITIONS = {
  phone: {
    '01_Home': [{ kind: 'phone', x: 520, y: 900, w: 1260, rotate: -14 }],
    '02_EditorsPicks': [],
    '03_ClosingThisWeek': [{ kind: 'phone', x: 300, y: 760, w: 1000, rotate: 7 }],
    '04_Map': [{ kind: 'fragment', x: 0, y: 760, w: 1320, sx: 0, sy: 530, sw: 1320, sh: 2160 }],
    '05_CuratorNote': [{ kind: 'phone', x: 250, y: 780, w: 1000, rotate: 6 }],
    '06_Saved': [{ kind: 'phone', x: 160, y: 820, w: 1000 }],
  },
  tablet: {
    '01_Home': [{ kind: 'phone', x: 560, y: 780, w: 1040, rotate: -12 }],
    '02_EditorsPicks': [],
    '03_ClosingThisWeek': [{ kind: 'phone', x: 280, y: 700, w: 880, rotate: 6 }],
    '04_Map': [{ kind: 'fragment', x: 0, y: 700, w: 1320, sx: 0, sy: 330, sw: 2064, sh: 2300 }],
    '05_CuratorNote': [{ kind: 'phone', x: 300, y: 720, w: 860, rotate: 5 }],
    '06_Saved': [{ kind: 'phone', x: 210, y: 720, w: 900 }],
  },
};
/** The straight device a preview shot plays in (video.mjs fills its screen). */
const PREVIEW_DEVICE = { phone: { x: 210, y: 820, w: 900 }, tablet: { x: 210, y: 700, w: 900 } };
const BEZEL = { phone: { padding: 22, radius: 150 }, tablet: { padding: 18, radius: 48 } };
const PANORAMA = ['01_Home', '02_EditorsPicks'];

/** The capture for a slot, with the status bar cut off when the target asks for it. */
export async function captureFor(target, locale, captureId) {
  const [platform, device] = target.captures;
  const path = join(ROOT, 'captures', platform, locale, device, `${captureId}.png`);
  if (!existsSync(path)) throw new Error(`${target.name} ${locale}: missing capture ${relative(ROOT, path)}; rerun capture-ios.sh`);
  if (!target.cropStatusBar) return path;
  const cropped = join(ROOT, 'dist/.work', locale, `${captureId}-no-status-bar.png`);
  await mkdir(dirname(cropped), { recursive: true });
  await run('ffmpeg', ['-v', 'error', '-y', '-i', path, '-vf', `crop=iw:ih-${target.cropStatusBar}:0:${target.cropStatusBar}`, cropped]);
  return cropped;
}

function frameFor({ slot, locale, target, src, offset }) {
  const tablet = target.layout === 'tablet';
  const cropTop = target.cropStatusBar ?? 0;
  const srcWidth = CAPTURE_SIZE[target.layout].width;
  const parts = COMPOSITIONS[target.layout][slot.id].map((part) =>
    part.kind === 'fragment' ? { ...part, src, srcWidth, sy: part.sy - cropTop } : { ...part, src, tablet },
  );
  return { lines: slot[locale].lines, aside: slot.aside, align: slot.id === '02_EditorsPicks' ? 'right' : 'left', offset, parts };
}

async function renderCanvas({ locale, frames, width, height, out }) {
  const layoutWidth = frames.length * LAYOUT_WIDTH;
  const page = await openTemplate({
    template: join(ROOT, 'templates/creative.html'),
    width,
    height,
    data: {
      lang: locale === 'ko' ? 'ko' : 'en',
      payload: JSON.stringify({ zoom: width / layoutWidth, width: layoutWidth, frames }),
    },
  });
  try {
    const overflowing = await page.evaluate(() => [...document.querySelectorAll('[data-overflow]')].map((h) => h.textContent));
    if (overflowing.length) throw new Error(`headline too wide for its frame: ${overflowing.join(' | ')}`);
    await capturePage(page, out);
  } finally {
    await page.close();
  }
}

/** Renders every screenshot slot for one target and locale; frames 1 and 2 come from one canvas. */
export async function buildTargetScreenshots({ copy, target, locale, record }) {
  const slots = copy.screenshots;
  const bySlot = Object.fromEntries(slots.map((slot) => [slot.id, slot]));
  const src = async (slot) => pathToFileURL(await captureFor(target, locale, slot.capture)).href;

  const panorama = join(ROOT, 'dist/.work', locale, `${target.name}-panorama.png`);
  await mkdir(dirname(panorama), { recursive: true });
  const pair = [];
  for (const [index, id] of PANORAMA.entries()) {
    pair.push(frameFor({ slot: bySlot[id], locale, target, src: await src(bySlot[id]), offset: index * LAYOUT_WIDTH }));
  }
  await renderCanvas({ locale, frames: pair, width: target.width * 2, height: target.height, out: panorama });
  for (const [index, id] of PANORAMA.entries()) {
    const out = target.out(locale, id);
    await mkdir(dirname(out), { recursive: true });
    await run('ffmpeg', ['-v', 'error', '-y', '-i', panorama, '-vf', `crop=${target.width}:${target.height}:${index * target.width}:0`, '-pix_fmt', 'rgb24', out]);
    record(out, target);
  }

  for (const slot of slots.filter((candidate) => !PANORAMA.includes(candidate.id))) {
    const out = target.out(locale, slot.id);
    await mkdir(dirname(out), { recursive: true });
    await renderCanvas({ locale, frames: [frameFor({ slot, locale, target, src: await src(slot), offset: 0 })], width: target.width, height: target.height, out });
    record(out, target);
  }
}

/**
 * The still behind one preview shot: the slot's caption over a straight device whose screen is left
 * black, and that screen's rectangle and corner radius in output pixels for video.mjs to fill.
 */
export async function renderPreviewBackground({ copy, slotId, locale, target, out }) {
  const slot = copy.screenshots.find((candidate) => candidate.id === slotId);
  if (!slot) throw new Error(`no screenshot copy for ${slotId}`);
  const device = PREVIEW_DEVICE[target.layout];
  const bezel = BEZEL[target.layout];
  const size = CAPTURE_SIZE[target.layout];
  const screenHeight = Math.round((device.w * (size.height - (target.cropStatusBar ?? 0))) / size.width);
  const frame = {
    lines: slot[locale].lines,
    aside: slot.aside,
    align: 'left',
    offset: 0,
    parts: [{ kind: 'phone', ...device, tablet: target.layout === 'tablet', screenHeight }],
  };
  await renderCanvas({ locale, frames: [frame], width: target.width, height: target.height, out });
  const zoom = target.width / LAYOUT_WIDTH;
  const even = (value) => Math.round(value / 2) * 2;
  return {
    x: Math.round((device.x + bezel.padding) * zoom),
    y: Math.round((device.y + bezel.padding) * zoom),
    width: even(device.w * zoom),
    height: even(screenHeight * zoom),
    radius: Math.round(bezel.radius * zoom),
  };
}
