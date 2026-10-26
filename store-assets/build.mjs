// Composes every store still from the raw captures and copy.json, then writes dist/manifest.json for
// validate.mjs. Usage: node build.mjs [screenshots|creatives|all]
import { readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { closeBrowser } from './render.mjs';
import { buildCreatives } from './creatives.mjs';
import { buildTargetScreenshots } from './creative.mjs';
import { SCREENSHOT_TARGETS } from './targets.mjs';

const ROOT = dirname(fileURLToPath(import.meta.url));
const REPO = join(ROOT, '..');
const LOCALES = ['ko', 'en-US'];

async function buildScreenshots(copy, manifest) {
  // Start from empty folders: the upload lane sends every file there, so a retired slot must not linger.
  const folders = new Set(SCREENSHOT_TARGETS.flatMap((target) => LOCALES.map((locale) => dirname(target.out(locale, 'x')))));
  for (const folder of folders) await rm(folder, { recursive: true, force: true });
  for (const target of SCREENSHOT_TARGETS) {
    for (const locale of LOCALES) {
      await buildTargetScreenshots({
        copy,
        target,
        locale,
        record: (out) => {
          manifest.push({ kind: 'image', file: relative(ROOT, out), spec: { width: target.width, height: target.height } });
          console.log(`ok ${relative(REPO, out)}`);
        },
      });
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
    await rm(join(ROOT, 'dist/.work'), { recursive: true, force: true });
  }
  // Keep entries this run did not rebuild (videos come from video.mjs).
  const rebuilt = new Set(manifest.map((entry) => entry.file));
  const kept = previous.filter((entry) => !rebuilt.has(entry.file) && existsSync(join(ROOT, entry.file)));
  const merged = [...kept, ...manifest];
  await mkdir(dirname(manifestPath), { recursive: true });
  await writeFile(manifestPath, `${JSON.stringify(merged, null, 2)}\n`);
}

await main(process.argv[2] ?? 'all');
