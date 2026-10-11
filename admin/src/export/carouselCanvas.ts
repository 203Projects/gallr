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
  layoutCarousel,
  type CarouselFacts,
  type CarouselLayout,
  type CarouselNote,
  type CarouselSlide,
  type Measure,
} from "./carousel";
import { buildCarouselArchive, type RenderedSlide } from "./carouselZip";
import { contrastRatio, loadPoster, parseHexColor, type LoadedPoster } from "./posterPalette";

const FONT_FAMILY = 'Inter, "Gothic A1", sans-serif';
const EXPORT_TIMEOUT_MS = 30_000;
const QR_TILE_PX = 320;
const SWATCH_PX = 24;
const WORDMARK_PX = 40;
const MIN_PAPER_CONTRAST = 7;

export class CarouselExportTimeoutError extends Error {
  constructor() {
    super("The export took longer than 30 seconds.");
    this.name = "CarouselExportTimeoutError";
  }
}

export interface CarouselExportInput {
  note: CarouselNote;
  facts: CarouselFacts;
  language: CuratorNoteLanguage;
  coverUrl: string | null;
  exhibitionUrl: string;
}

export interface CarouselExportResult {
  archive: Uint8Array;
  layout: CarouselLayout;
  slideCount: number;
}

interface Paper {
  paper: string;
  ink: string;
  darkest: string;
}

function font(px: number, weight: 400 | 500): string {
  return `${weight} ${px}px ${FONT_FAMILY}`;
}

function newCanvas(): { canvas: HTMLCanvasElement; context: CanvasRenderingContext2D } {
  const canvas = document.createElement("canvas");
  canvas.width = SLIDE_WIDTH;
  canvas.height = SLIDE_HEIGHT;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas is unavailable in this browser.");
  return { canvas, context };
}

/** Paper from the lightest palette tone that still reads at 7:1 against the darkest; else white on black. */
export function choosePaper(palette: readonly string[]): Paper {
  const colors = palette.map(parseHexColor).filter((color): color is NonNullable<typeof color> => color !== null);
  const darkestHex = palette[0] ?? "#000000";
  const darkest = colors[0] ?? { red: 0, green: 0, blue: 0 };
  const white = { red: 255, green: 255, blue: 255 };
  const light = [...palette].reverse().find((hex) => {
    const color = parseHexColor(hex);
    return color !== null && contrastRatio(color, darkest) >= MIN_PAPER_CONTRAST;
  });
  return {
    paper: light ?? "#FFFFFF",
    ink: contrastRatio(white, darkest) >= MIN_PAPER_CONTRAST ? darkestHex : "#000000",
    darkest: darkestHex,
  };
}

async function toPng(canvas: HTMLCanvasElement): Promise<Uint8Array> {
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/png"));
  if (!blob) throw new Error("The slide could not be encoded.");
  return new Uint8Array(await blob.arrayBuffer());
}

function drawLines(
  context: CanvasRenderingContext2D,
  lines: string[],
  x: number,
  top: number,
  px: number,
  weight: 400 | 500,
  lineHeight: number,
  color: string,
): number {
  context.font = font(px, weight);
  context.fillStyle = color;
  context.textBaseline = "top";
  let y = top;
  for (const line of lines) {
    context.fillText(line, x, y);
    y += px * lineHeight;
  }
  return y;
}

function drawFooter(context: CanvasRenderingContext2D, color: string, index: number, count: number) {
  context.font = font(WORDMARK_PX, 500);
  context.fillStyle = color;
  context.textBaseline = "alphabetic";
  context.textAlign = "left";
  context.fillText("gallr", SLIDE_MARGIN, SLIDE_HEIGHT - SLIDE_MARGIN);
  context.font = font(CAPTION_PX, 400);
  context.textAlign = "right";
  context.fillText(`${index + 1} / ${count}`, SLIDE_WIDTH - SLIDE_MARGIN, SLIDE_HEIGHT - SLIDE_MARGIN);
  context.textAlign = "left";
}

function drawLabel(context: CanvasRenderingContext2D, label: string, color: string) {
  context.font = font(CAPTION_PX, 500);
  context.fillStyle = color;
  context.textBaseline = "top";
  context.fillText(label.toUpperCase().split("").join(" "), SLIDE_MARGIN, SLIDE_MARGIN);
}

function drawCover(context: CanvasRenderingContext2D, slide: Extract<CarouselSlide, { kind: "cover" }>, poster: LoadedPoster, paper: Paper) {
  const { bitmap } = poster;
  const scale = Math.max(SLIDE_WIDTH / bitmap.width, SLIDE_HEIGHT / bitmap.height);
  const width = bitmap.width * scale;
  const height = bitmap.height * scale;
  context.drawImage(bitmap, (SLIDE_WIDTH - width) / 2, (SLIDE_HEIGHT - height) / 2, width, height);
  const wash = context.createLinearGradient(0, SLIDE_HEIGHT * 0.45, 0, SLIDE_HEIGHT);
  const dark = parseHexColor(paper.darkest) ?? { red: 0, green: 0, blue: 0 };
  wash.addColorStop(0, `rgba(${dark.red}, ${dark.green}, ${dark.blue}, 0)`);
  wash.addColorStop(1, `rgba(${dark.red}, ${dark.green}, ${dark.blue}, 0.85)`);
  context.fillStyle = wash;
  context.fillRect(0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
  drawLabel(context, slide.label, "#FFFFFF");
  const captionHeight = (slide.titleLines.length + 1) * CAPTION_PX * BODY_LINE_HEIGHT;
  const hookHeight = slide.hookLines.length * TITLE_PX * TITLE_LINE_HEIGHT;
  const bottom = SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX - 24;
  const hookTop = bottom - captionHeight - 24 - hookHeight;
  drawLines(context, slide.hookLines, SLIDE_MARGIN, hookTop, TITLE_PX, 500, TITLE_LINE_HEIGHT, "#FFFFFF");
  drawLines(context, [...slide.titleLines, slide.venue.toUpperCase()], SLIDE_MARGIN, bottom - captionHeight, CAPTION_PX, 400, BODY_LINE_HEIGHT, "#FFFFFF");
}

function drawInsight(context: CanvasRenderingContext2D, slide: Extract<CarouselSlide, { kind: "insight" }>, palette: readonly string[], paper: Paper) {
  context.fillStyle = paper.paper;
  context.fillRect(0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
  drawLabel(context, slide.label, paper.ink);
  palette.forEach((color, index) => {
    context.fillStyle = color;
    context.fillRect(SLIDE_MARGIN + index * (SWATCH_PX + 8), SLIDE_MARGIN + CAPTION_PX + 16, SWATCH_PX, SWATCH_PX);
  });
  drawLines(context, slide.lines, SLIDE_MARGIN, SLIDE_MARGIN + CAPTION_PX + 16 + SWATCH_PX + 48, BODY_PX, 400, BODY_LINE_HEIGHT, paper.ink);
}

async function drawClosing(
  context: CanvasRenderingContext2D,
  slide: Extract<CarouselSlide, { kind: "closing" }>,
  paper: Paper,
  exhibitionUrl: string,
) {
  context.fillStyle = "#FFFFFF";
  context.fillRect(0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
  drawLabel(context, slide.label, paper.ink);
  const facts = [slide.dates, [slide.venue, slide.district].filter(Boolean).join(" · "), slide.hours ?? ""].filter(Boolean);
  let y = drawLines(context, facts, SLIDE_MARGIN, SLIDE_MARGIN + CAPTION_PX + 40, BODY_PX, 400, BODY_LINE_HEIGHT, paper.ink);
  y = drawLines(context, slide.tipLines, SLIDE_MARGIN, y + 32, BODY_PX, 500, BODY_LINE_HEIGHT, paper.ink);

  const { encode } = await import("uqr");
  const matrix = encode(exhibitionUrl, { ecc: "M", border: 4, boostEcc: true });
  const tileTop = SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX - 48 - QR_TILE_PX;
  const tileLeft = SLIDE_WIDTH - SLIDE_MARGIN - QR_TILE_PX;
  context.fillStyle = "#FFFFFF";
  context.fillRect(tileLeft, tileTop, QR_TILE_PX, QR_TILE_PX);
  const module = QR_TILE_PX / matrix.size;
  context.fillStyle = paper.ink;
  for (let row = 0; row < matrix.size; row += 1) {
    for (let column = 0; column < matrix.size; column += 1) {
      if (matrix.data[row]?.[column]) {
        context.fillRect(tileLeft + column * module, tileTop + row * module, Math.ceil(module), Math.ceil(module));
      }
    }
  }
  const disclosureTop = Math.max(y + 48, tileTop + QR_TILE_PX - CAPTION_PX * BODY_LINE_HEIGHT);
  drawLines(context, [slide.disclosure], SLIDE_MARGIN, disclosureTop, CAPTION_PX, 400, BODY_LINE_HEIGHT, paper.ink);
}

/** Renders every slide of a layout to PNG bytes. */
export async function renderCarousel(layout: CarouselLayout, poster: LoadedPoster, exhibitionUrl: string): Promise<RenderedSlide[]> {
  const paper = choosePaper(poster.palette);
  const rendered: RenderedSlide[] = [];
  for (const [index, slide] of layout.slides.entries()) {
    const { canvas, context } = newCanvas();
    if (slide.kind === "cover") drawCover(context, slide, poster, paper);
    else if (slide.kind === "insight") drawInsight(context, slide, poster.palette, paper);
    else await drawClosing(context, slide, paper, exhibitionUrl);
    drawFooter(context, slide.kind === "cover" ? "#FFFFFF" : paper.ink, index, layout.slides.length);
    rendered.push({ slide, png: await toPng(canvas) });
  }
  return rendered;
}

async function ensureFonts(): Promise<void> {
  const fonts = (document as Document & { fonts?: FontFaceSet }).fonts;
  if (!fonts) return;
  await Promise.all([fonts.load(font(TITLE_PX, 500)), fonts.load(font(BODY_PX, 400)), fonts.load(font(BODY_PX, 500))]);
}

function canvasMeasure(): Measure {
  const { context } = newCanvas();
  return (text, px, weight) => {
    context.font = font(px, weight);
    return context.measureText(text).width;
  };
}

/** Fetches the poster, lays out and renders the slides, and zips them with the caption; refused without a cover. */
export async function exportCarousel(input: CarouselExportInput): Promise<CarouselExportResult> {
  const work = (async () => {
    await ensureFonts();
    const poster = await loadPoster(input.coverUrl);
    try {
      const layout = layoutCarousel(input.note, input.facts, input.language, canvasMeasure());
      const slides = await renderCarousel(layout, poster, input.exhibitionUrl);
      return { archive: buildCarouselArchive(layout, slides), layout, slideCount: slides.length };
    } finally {
      poster.bitmap.close();
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
