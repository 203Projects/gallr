// iOS 27 creative assets (product page header, search results) and the Google Play feature graphic.
//
// The header is a gallery wall: real covers from the public catalogue (fetch-catalogue.mjs), hung on
// one centre line like works in a white cube, each with a small wall label. Covers were screened for
// the App Store's 4+ rule; WALL_WORKS lists the ones chosen. The wall repeats with a fixed period, so a
// pan of exactly one period loops without a seam (video.mjs).
import { execFile } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { openTemplate, assertNoOverflow, capturePage, renderTemplate } from './render.mjs';

const run = promisify(execFile);

/** Catalogue ids (prefixes) of the works on the wall, left to right; HERO_INDEX sits at the centre. */
export const WALL_WORKS = [
  '75cf8ab3', // Pace Gallery Seoul — The Nearest Faraway Place
  '36b1bb46', // CARIN — Between the Feathers
  '76c3989a', // Thaddaeus Ropac — Per Mano
  '886ea792', // Amorepacific Museum of Art — Open Structure | Sol LeWitt
  '1537bb0b', // MMCA Seoul — Do Ho Suh
  '6c149e0c', // Leeum — OUSSSMOS
  'f8a25c0b', // Futura Seoul — 3rd Poem: Es Devlin, Come Home Again
  'da916132', // Total Museum of Contemporary Art — Korean Art Year Zero: 42 Stories
  '34a6724f', // SeMA Seosomun — A Mountain Within Me
  'b6e7cfb8', // HITE Collection — Close to Far
];
export const HERO_INDEX = 4;

export const HEADER = { width: 3840, height: 1646, safe: { width: 1646, height: 661 } };
export const SEARCH = { width: 3840, height: 2560, safe: { width: 2168, height: 1030 } };
export const FEATURE_GRAPHIC = { width: 1024, height: 500 };

const WALL = { centreY: 800, portraitHeight: 660, landscapeWidth: 900, minHeight: 460, gap: 180 };

/**
 * Hangs works of the given aspect ratios (width / height) on one horizontal centre line. Portrait
 * works take `portraitHeight`, landscape works `landscapeWidth`, never shorter than `minHeight`.
 * `period` is the wall's repeat length: the last work's right edge plus one gap.
 */
export function wallLayout(works, { centreY, portraitHeight, landscapeWidth, minHeight, gap }) {
  let x = 0;
  const placed = works.map((work) => {
    let height = work.aspect >= 1 ? landscapeWidth / work.aspect : portraitHeight;
    height = Math.max(Math.min(height, portraitHeight), minHeight);
    const width = height * work.aspect;
    const slot = { ...work, x, y: centreY - height / 2, width, height };
    x += width + gap;
    return slot;
  });
  return { placed, period: x };
}

/** "🏛️ OUSSSMOS | KOO JEONG A" → "OUSSSMOS": the wall label carries the title; the venue has its own line. */
export function cleanTitle(name) {
  return name
    .replace(/\p{Extended_Pictographic}️?/gu, '')
    .split('|')[0]
    .trim();
}

function formatDates(row) {
  const day = (value) => value?.replaceAll('-', '.');
  return [day(row.opening_date), day(row.closing_date)].filter(Boolean).join(' – ');
}

async function aspectOf(path) {
  const { stdout } = await run('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height', '-of', 'csv=p=0', path]);
  const [width, height] = stdout.trim().split(',').map(Number);
  return width / height;
}

async function loadCatalogue(root) {
  const path = join(root, 'data/catalogue.json');
  if (!existsSync(path)) throw new Error('data/catalogue.json missing: run `node fetch-catalogue.mjs` first');
  return JSON.parse(await readFile(path, 'utf8'));
}

/** The wall's works with covers, labels and layout, repeated `copies` times for a seamless strip. */
export async function wallPayload({ root, catalogue, locale, copies = 1 }) {
  const works = [];
  for (const prefix of WALL_WORKS) {
    const row = catalogue.find((candidate) => candidate.id.startsWith(prefix));
    if (!row) throw new Error(`wall work ${prefix} is no longer in the catalogue; pick another in WALL_WORKS`);
    const cover = join(root, 'data/covers', `${row.id}.img`);
    works.push({
      cover: pathToFileURL(cover).href,
      aspect: await aspectOf(cover),
      venue: locale === 'ko' ? row.venue_name_ko : row.venue_name_en,
      title: cleanTitle(locale === 'ko' ? row.name_ko : row.name_en),
      dates: formatDates(row),
    });
  }
  // An integer period: the video crop wraps at it, so the copies must sit exactly one period apart.
  const { placed, period: exact } = wallLayout(works, WALL);
  const period = Math.round(exact);
  const strip = [];
  for (let copy = 0; copy < copies; copy += 1) {
    for (const work of placed) strip.push({ ...work, x: work.x + copy * period });
  }
  const hero = placed[HERO_INDEX];
  return { works: strip, period, heroCentre: hero.x + hero.width / 2 };
}

/**
 * Renders the wall at `width` × HEADER.height with the wall shifted left by `offset` pixels. For the
 * still, the offset centres the hero work; video.mjs renders a strip wide enough to pan one period.
 */
export async function renderWall({ root, catalogue, locale, width, offset, copies, out }) {
  const wall = await wallPayload({ root, catalogue, locale, copies });
  const shift = offset ?? wall.heroCentre - HEADER.width / 2;
  await renderTemplate({
    template: join(root, 'templates/wall.html'),
    width,
    height: HEADER.height,
    out,
    data: { lang: locale === 'ko' ? 'ko' : 'en', payload: JSON.stringify({ works: wall.works, shift }) },
  });
  return { ...wall, shift };
}

export async function buildCreatives({ copy, manifest, root, locales }) {
  const catalogue = await loadCatalogue(root);
  const record = (out, spec) => {
    manifest.push({ kind: 'image', file: relative(root, out), spec });
    console.log(`ok ${relative(root, out)}`);
  };

  for (const locale of locales) {
    const lang = locale === 'ko' ? 'ko' : 'en';
    const header = join(root, 'dist/appstore', locale, 'header-21x9.png');
    // Two copies so the canvas is filled to its right edge wherever the hero sits.
    await renderWall({ root, catalogue, locale, width: HEADER.width, copies: 2, out: header });
    record(header, { width: HEADER.width, height: HEADER.height });

    const search = join(root, 'dist/appstore', locale, 'search-3x2.png');
    const captures = ['01_Home', '02_ClosingThisWeek', '03_Map'].map((id) => {
      const path = join(root, 'captures/ios', locale, 'Gallr Store iPhone 17 Pro Max', `${id}.png`);
      if (!existsSync(path)) throw new Error(`search asset needs ${relative(root, path)}; run capture-ios.sh first`);
      return pathToFileURL(path).href;
    });
    const page = await openTemplate({
      template: join(root, 'templates/search.html'),
      width: SEARCH.width,
      height: SEARCH.height,
      data: {
        lang,
        eyebrow: copy.search[locale].eyebrow,
        headline: copy.search[locale].headline,
        capture1: captures[0],
        capture2: captures[1],
        capture3: captures[2],
        wall: pathToFileURL(header).href,
      },
    });
    try {
      await assertNoOverflow(page);
      await capturePage(page, search);
    } finally {
      await page.close();
    }
    record(search, { width: SEARCH.width, height: SEARCH.height });

    const feature = join(root, 'dist/play', locale, 'feature-graphic.png');
    await renderTemplate({
      template: join(root, 'templates/feature-graphic.html'),
      width: FEATURE_GRAPHIC.width,
      height: FEATURE_GRAPHIC.height,
      out: feature,
      data: {
        lang,
        headline: copy.featureGraphic[locale].headline,
        sub: copy.featureGraphic[locale].sub,
        wall: pathToFileURL(header).href,
      },
    });
    record(feature, FEATURE_GRAPHIC);
  }
}
