import type { CuratorNoteLanguage } from "../repositories/AdminCuratorNoteRepository";
import {
  BODY_LINE_HEIGHT,
  BODY_PX,
  CAPTION_PX,
  SLIDE_HEIGHT,
  SLIDE_MARGIN,
  SLIDE_WIDTH,
  TITLE_LINE_HEIGHT,
  TITLE_PX,
  WORDMARK_PX,
  type CoverSlide,
} from "./carousel";
import {
  CarouselExportTimeoutError,
  canvasMeasure,
  choosePaper,
  darkestOf,
  drawFooter,
  drawLabel,
  drawLines,
  drawPosterCrop,
  drawScrim,
  ensureFonts,
  newCanvas,
  toPng,
} from "./carouselCanvas";
import { loadPoster, PosterUnavailableError, type LoadedPoster } from "./posterPalette";
import {
  INDEX_ROW_GAP,
  INDEX_TOP,
  layoutRoundup,
  roundupSlideFileName,
  type RoundupEntry,
  type RoundupEntrySlide,
  type RoundupIndexSlide,
  type RoundupLayout,
} from "./roundup";
import { zipSync } from "fflate";

const EXPORT_TIMEOUT_MS = 60_000;

export interface RoundupExportInput {
  title: string;
  entries: RoundupEntry[];
  language: CuratorNoteLanguage;
}

export interface RoundupExportResult {
  archive: Uint8Array;
  layout: RoundupLayout;
  slideCount: number;
}

/** One exhibition's cover could not be used; the roundup is refused and names it. */
export class RoundupPosterUnavailableError extends Error {
  constructor(readonly exhibitionId: string) {
    super("An exhibition in the roundup has no usable cover image.");
    this.name = "RoundupPosterUnavailableError";
  }
}

/** The picture full-bleed with white text stacked from the footer up: label, then the given blocks in order. */
function drawOverPoster(
  context: CanvasRenderingContext2D,
  poster: LoadedPoster,
  label: string,
  blocks: { lines: string[]; px: number; weight: 400 | 500; lineHeight: number; gapAbove: number }[],
) {
  const filled = blocks.filter((block) => block.lines.length > 0);
  const height = filled.reduce((sum, block) => sum + block.gapAbove + block.lines.length * block.px * block.lineHeight, 0);
  const bottom = SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX - 24;
  const labelTop = bottom - height - CAPTION_PX;
  drawPosterCrop(context, poster.bitmap, 0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
  drawScrim(context, darkestOf(poster), labelTop);
  drawLabel(context, label, "#FFFFFF", labelTop);
  let top = labelTop + CAPTION_PX;
  for (const block of filled) {
    top = drawLines(context, block.lines, SLIDE_MARGIN, top + block.gapAbove, block.px, block.weight, block.lineHeight, "#FFFFFF");
  }
}

function drawRoundupCover(context: CanvasRenderingContext2D, slide: CoverSlide, poster: LoadedPoster) {
  drawOverPoster(context, poster, slide.label, [
    { lines: slide.hookLines, px: TITLE_PX, weight: 500, lineHeight: TITLE_LINE_HEIGHT, gapAbove: 24 },
    { lines: slide.metaLines, px: CAPTION_PX, weight: 400, lineHeight: BODY_LINE_HEIGHT, gapAbove: 24 },
  ]);
}

function drawEntry(context: CanvasRenderingContext2D, slide: RoundupEntrySlide, poster: LoadedPoster) {
  drawOverPoster(context, poster, slide.label, [
    { lines: slide.nameLines, px: TITLE_PX, weight: 500, lineHeight: TITLE_LINE_HEIGHT, gapAbove: 24 },
    { lines: slide.metaLines, px: CAPTION_PX, weight: 400, lineHeight: BODY_LINE_HEIGHT, gapAbove: 16 },
    { lines: slide.hookLines, px: BODY_PX, weight: 400, lineHeight: BODY_LINE_HEIGHT, gapAbove: 32 },
  ]);
}

function drawIndex(context: CanvasRenderingContext2D, slide: RoundupIndexSlide, ink: string) {
  context.fillStyle = "#FFFFFF";
  context.fillRect(0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
  drawLabel(context, slide.label, ink);
  let top = INDEX_TOP;
  for (const row of slide.rows) {
    top = drawLines(context, row.nameLines, SLIDE_MARGIN, top, CAPTION_PX, 500, BODY_LINE_HEIGHT, ink);
    top = drawLines(context, row.metaLines, SLIDE_MARGIN, top, CAPTION_PX, 400, BODY_LINE_HEIGHT, ink) + INDEX_ROW_GAP;
  }
}

/** Renders a roundup's slides to PNG bytes; `posters` are the entries' pictures in slide order. */
export async function renderRoundup(layout: RoundupLayout, posters: LoadedPoster[]): Promise<Uint8Array[]> {
  const ink = choosePaper(posters[0].palette).ink;
  const rendered: Uint8Array[] = [];
  let entry = 0;
  for (const [index, slide] of layout.slides.entries()) {
    const { canvas, context } = newCanvas();
    if (slide.kind === "cover") drawRoundupCover(context, slide, posters[0]);
    else if (slide.kind === "entry") drawEntry(context, slide, posters[entry++]);
    else drawIndex(context, slide, ink);
    drawFooter(context, slide.kind === "index" ? ink : "#FFFFFF", index, layout.slides.length);
    rendered.push(await toPng(canvas));
  }
  return rendered;
}

/** Slides in order and the caption; a report only when text was left out. Entries are stored, not deflated. */
export function buildRoundupArchive(layout: RoundupLayout, slides: Uint8Array[]): Uint8Array {
  const entries: Record<string, Uint8Array> = {};
  slides.forEach((png, index) => {
    entries[roundupSlideFileName(index, layout.slides[index])] = png;
  });
  const encoder = new TextEncoder();
  entries["caption.txt"] = encoder.encode(layout.caption);
  if (layout.omitted.length > 0) {
    entries["report.txt"] = encoder.encode(["Left out of the roundup because it ran past its lines:", "", ...layout.omitted].join("\n"));
  }
  return zipSync(entries, { level: 0 });
}

async function loadPosters(entries: RoundupEntry[]): Promise<LoadedPoster[]> {
  const settled = await Promise.allSettled(entries.map((entry) => loadPoster(entry.coverImageUrl)));
  const failed = settled.findIndex((result) => result.status === "rejected");
  if (failed === -1) return settled.map((result) => (result as PromiseFulfilledResult<LoadedPoster>).value);
  for (const result of settled) if (result.status === "fulfilled") result.value.bitmap.close();
  const reason = (settled[failed] as PromiseRejectedResult).reason;
  throw reason instanceof PosterUnavailableError ? new RoundupPosterUnavailableError(entries[failed].exhibitionId) : reason;
}

/** Fetches every cover, lays out and renders the roundup, and zips it with the caption; refused when a cover is missing. */
export async function exportRoundup(input: RoundupExportInput): Promise<RoundupExportResult> {
  const work = (async () => {
    await ensureFonts();
    const layout = layoutRoundup(input.title, input.entries, input.language, canvasMeasure());
    const posters = await loadPosters(input.entries);
    try {
      const slides = await renderRoundup(layout, posters);
      return { archive: buildRoundupArchive(layout, slides), layout, slideCount: slides.length };
    } finally {
      for (const poster of posters) poster.bitmap.close();
    }
  })();
  let timer = 0;
  const timeout = new Promise<never>((_, reject) => {
    timer = window.setTimeout(() => reject(new CarouselExportTimeoutError()), EXPORT_TIMEOUT_MS);
  });
  try {
    return await Promise.race([work, timeout]);
  } finally {
    window.clearTimeout(timer);
  }
}
