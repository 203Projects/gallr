// Composes every store still from the raw captures and copy.json, then writes dist/manifest.json for
// validate.mjs. Usage: node build.mjs [screenshots|creatives|all]
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { existsSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { renderTemplate, closeBrowser } from './render.mjs';
import { buildCreatives } from './creatives.mjs';
import { SCREENSHOT_TARGETS } from './targets.mjs';

const run = promisify(execFile);
const ROOT = dirname(fileURLToPath(import.meta.url));
const REPO = join(ROOT, '..');
const LOCALES = ['ko', 'en-US'];

const CONNECTORS = { '03_Map': 'right', '04_Route': 'left' };

async function captureFor(target, locale, captureId) {
  const [platform, device] = target.captures;
  const path = join(ROOT, 'captures', platform, locale, device, `${captureId}.png`);
  if (!existsSync(path)) return null;
  if (!target.cropStatusBar) return path;
  const cropped = join(ROOT, 'dist/.work', locale, `${captureId}-no-status-bar.png`);
  await mkdir(dirname(cropped), { recursive: true });
  await run('ffmpeg', ['-v', 'error', '-y', '-i', path, '-vf', `crop=iw:ih-${target.cropStatusBar}:0:${target.cropStatusBar}`, cropped]);
  return cropped;
}

async function buildScreenshots(copy, manifest) {
  const template = join(ROOT, 'templates/screenshot.html');
  for (const target of SCREENSHOT_TARGETS) {
    for (const locale of LOCALES) {
      for (const [position, slot] of copy.screenshots.entries()) {
        const capture = await captureFor(target, locale, slot.capture);
        // A missing capture stops the build: a partial set would replace the whole live listing.
        if (!capture) throw new Error(`${target.name} ${locale} ${slot.id}: no capture; rerun capture-ios.sh`);
        const out = target.out(locale, slot.id);
        const { layout } = target;
        await renderTemplate({
          template,
          width: target.width,
          height: target.height,
          out,
          data: {
            lang: locale === 'ko' ? 'ko' : 'en',
            index: String(position + 1).padStart(2, '0'),
            eyebrow: slot.eyebrow,
            headline: slot[locale].headline,
            sub: slot[locale].sub,
            capture: pathToFileURL(capture).href,
            connector: CONNECTORS[slot.id] ?? 'none',
            textTop: layout.textTop,
            headlineSize: layout.headlineSize[locale],
            captureWidth: layout.captureWidth,
            captureTop: layout.captureTop,
            connectorTop: layout.connectorTop,
          },
        });
        manifest.push({ kind: 'image', file: relative(ROOT, out), spec: { width: target.width, height: target.height } });
        console.log(`ok ${relative(REPO, out)}`);
      }
    }
  }
}

async function main(which) {
  const copy = JSON.parse(await readFile(join(ROOT, 'copy.json'), 'utf8'));
  const manifestPath = join(ROOT, 'dist/manifest.json');
  const previous = existsSync(manifestPath) ? JSON.parse(await readFile(manifestPath, 'utf8')) : [];
  const manifest = [];
  try {
    if (which === 'all' || which === 'screenshots') await buildScreenshots(copy, manifest);
    if (which === 'all' || which === 'creatives') await buildCreatives({ copy, manifest, root: ROOT, locales: LOCALES });
  } finally {
    await closeBrowser();
  }
  // Keep entries this run did not rebuild (videos come from video.mjs).
  const rebuilt = new Set(manifest.map((entry) => entry.file));
  const merged = [...previous.filter((entry) => !rebuilt.has(entry.file)), ...manifest];
  await mkdir(dirname(manifestPath), { recursive: true });
  await writeFile(manifestPath, `${JSON.stringify(merged, null, 2)}\n`);
}

await main(process.argv[2] ?? 'all');
