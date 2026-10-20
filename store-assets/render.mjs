// Renders the store templates at exact pixel sizes with headless Chromium, then flattens each PNG to
// opaque RGB so App Store Connect accepts it.
//
// Templates use {{key}} placeholders, filled with HTML-escaped values. Elements marked `data-fit` must
// not overflow their own box; a caption that does fails the render instead of shipping clipped.
import { chromium } from 'playwright';
import { execFile } from 'node:child_process';
import { mkdtemp, readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { promisify } from 'node:util';

const run = promisify(execFile);
let browserPromise;

function browser() {
  browserPromise ??= chromium.launch({ args: ['--allow-file-access-from-files'] });
  return browserPromise;
}

export async function closeBrowser() {
  if (!browserPromise) return;
  const instance = await browserPromise;
  browserPromise = undefined;
  await instance.close();
}

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

export function fill(html, data) {
  return html.replace(/\{\{\s*([\w.]+)\s*\}\}/g, (match, key) => {
    if (data[key] === undefined || data[key] === null) throw new Error(`template value missing: ${key}`);
    return String(data[key]).replace(/[&<>"']/g, (char) => ESCAPES[char]);
  });
}

/** Opens a filled template on a page of the given size; the caller closes the page. */
export async function openTemplate({ template, data, width, height }) {
  const html = fill(await readFile(template, 'utf8'), data);
  const scratch = await mkdtemp(join(tmpdir(), 'gallr-store-'));
  // The filled page sits beside the template so relative font and stylesheet paths resolve.
  const page = await (await browser()).newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  const filled = join(dirname(template), `.render-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}.html`);
  await writeFile(filled, html);
  try {
    await page.goto(pathToFileURL(filled).href, { waitUntil: 'load' });
    await page.evaluate(async () => {
      await document.fonts.ready;
      await Promise.all(
        [...document.images].map((image) => (image.complete ? null : new Promise((done) => (image.onload = image.onerror = done)))),
      );
      if (typeof window.layout === 'function') await window.layout();
    });
  } finally {
    await rm(filled, { force: true });
    await rm(scratch, { recursive: true, force: true });
  }
  return page;
}

export async function assertNoOverflow(page) {
  const overflowing = await page.evaluate(() =>
    [...document.querySelectorAll('[data-fit]')]
      .filter((element) => {
        // Descenders count toward scrollHeight at tight line heights; a wrapped extra line adds a full
        // line, so a quarter em of slack separates the two.
        const slack = parseFloat(getComputedStyle(element).fontSize) * 0.25;
        return element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + slack;
      })
      .map((element) => element.tagName.toLowerCase() + (element.id ? `#${element.id}` : '')),
  );
  if (overflowing.length) throw new Error(`overflow: ${overflowing.join(', ')}`);
}

/** Writes an opaque RGB PNG of the page; `omitBackground` would keep alpha, so it is never used. */
export async function capturePage(page, out) {
  await mkdir(dirname(out), { recursive: true });
  const raw = `${out}.raw.png`;
  await page.screenshot({ path: raw, type: 'png', fullPage: false });
  await run('ffmpeg', ['-v', 'error', '-y', '-i', raw, '-pix_fmt', 'rgb24', out]);
  await rm(raw, { force: true });
}

export async function renderTemplate({ template, data, width, height, out }) {
  const page = await openTemplate({ template, data, width, height });
  try {
    await assertNoOverflow(page);
    await capturePage(page, out);
  } finally {
    await page.close();
  }
}
