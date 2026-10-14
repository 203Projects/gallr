import type { CuratorNoteLanguage } from "../repositories/AdminCuratorNoteRepository";
import {
  BODY_LINE_HEIGHT,
  BODY_PX,
  CAPTION_PX,
  CLOSING_FACTS_TOP,
  CLOSING_POSTER_HEIGHT_PX,
  CLOSING_POSTER_WIDTH_PX,
  INSIGHT_BAND_PX,
  INSIGHT_BODY_TOP,
  INSIGHT_LABEL_TOP,
  INSIGHT_SWATCH_TOP,
  MAX_GALLERY_PICTURES,
  QR_TILE_PX,
  QR_TILE_TOP,
  SLIDE_HEIGHT,
  SLIDE_MARGIN,
  SLIDE_WIDTH,
  SWATCH_PX,
  TITLE_LINE_HEIGHT,
  TITLE_PX,
  WORDMARK_PX,
  layoutCarousel,
  pick,
  unloadedPicturesReport,
  type CarouselFacts,
  type CarouselLayout,
  type CarouselNote,
  type CarouselSlide,
  type Measure,
} from "./carousel";
import { buildCarouselArchive, type RenderedSlide } from "./carouselZip";
import { contrastRatio, loadPoster, parseHexColor, type LoadedPoster, type Rgb } from "./posterPalette";

const FONT_FAMILY = 'Inter, "Gothic A1", sans-serif';
const EXPORT_TIMEOUT_MS = 30_000;
const MIN_PAPER_CONTRAST = 7;
/** Space between the cover's section label and the hook under it. */
const COVER_LABEL_GAP = 24;
/** Height of the wash that softens the insight band's bottom edge into the paper. */
const INSIGHT_WASH_PX = 120;
/** How far above the text the scrim fades in, and how strong it is at the text's top edge and at the slide's bottom. */
const SCRIM_FADE_PX = 240;
const SCRIM_TEXT_OPACITY = 0.82;
const SCRIM_BOTTOM_OPACITY = 0.92;
const BLACK: Rgb = { red: 0, green: 0, blue: 0 };

export class CarouselExportTimeoutError extends Error {
  constructor() {
    super("The export took longer than 30 seconds.");
    this.name = "CarouselExportTimeoutError";
  }
}

/** One of the exhibition's extra pictures: where it is, who to credit and its alt text, which a picture slide prints. */
export interface CarouselGalleryImage {
  url: string;
  credit: string;
  altKo?: string;
  altEn?: string;
}

export interface CarouselExportInput {
  note: CarouselNote;
  facts: CarouselFacts;
  language: CuratorNoteLanguage;
  coverUrl: string | null;
  coverCredit?: string;
  /** The exhibition's extra pictures in order; the insights take the first of those that load and the rest get slides of their own. */
  gallery?: readonly CarouselGalleryImage[];
  exhibitionUrl: string;
}

export interface CarouselExportResult {
  archive: Uint8Array;
  layout: CarouselLayout;
  slideCount: number;
}

export interface Paper {
  paper: string;
  ink: string;
  darkest: string;
}

export function font(px: number, weight: 400 | 500): string {
  return `${weight} ${px}px ${FONT_FAMILY}`;
}

export function newCanvas(): { canvas: HTMLCanvasElement; context: CanvasRenderingContext2D } {
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

export async function toPng(canvas: HTMLCanvasElement): Promise<Uint8Array> {
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/png"));
  if (!blob) throw new Error("The slide could not be encoded.");
  return new Uint8Array(await blob.arrayBuffer());
}

export function drawLines(
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

export function drawFooter(context: CanvasRenderingContext2D, color: string, index: number, count: number) {
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

export function drawLabel(context: CanvasRenderingContext2D, label: string, color: string, top = SLIDE_MARGIN) {
  context.font = font(CAPTION_PX, 500);
  context.fillStyle = color;
  context.textBaseline = "top";
  context.fillText(label.toUpperCase().split("").join(" "), SLIDE_MARGIN, top);
}

/**
 * Cover-fit crop: the poster is scaled to fill the box with the overflow clipped away. `focus` picks which part of
 * the overflow shows, from 0 (the poster's start) to 1 (its end); 0.5 centres it.
 */
export function drawPosterCrop(
  context: CanvasRenderingContext2D,
  bitmap: ImageBitmap,
  left: number,
  top: number,
  width: number,
  height: number,
  focus = 0.5,
) {
  const scale = Math.max(width / bitmap.width, height / bitmap.height);
  const scaledWidth = bitmap.width * scale;
  const scaledHeight = bitmap.height * scale;
  context.save();
  context.beginPath();
  context.rect(left, top, width, height);
  context.clip();
  context.drawImage(bitmap, left + (width - scaledWidth) * focus, top + (height - scaledHeight) * focus, scaledWidth, scaledHeight);
  context.restore();
}

/** A vertical wash from transparent to the colour over the rows between `top` and `bottom`. */
function drawWash(context: CanvasRenderingContext2D, color: Rgb, top: number, bottom: number, opacity: number) {
  const wash = context.createLinearGradient(0, top, 0, bottom);
  wash.addColorStop(0, `rgba(${color.red}, ${color.green}, ${color.blue}, 0)`);
  wash.addColorStop(1, `rgba(${color.red}, ${color.green}, ${color.blue}, ${opacity})`);
  context.fillStyle = wash;
  context.fillRect(0, top, SLIDE_WIDTH, bottom - top);
}

/** A scrim of the colour that is clear above `textTop - SCRIM_FADE_PX`, at text strength from `textTop` and a little stronger at the bottom. */
export function drawScrim(context: CanvasRenderingContext2D, color: Rgb, textTop: number) {
  const fadeTop = Math.max(0, textTop - SCRIM_FADE_PX);
  const scrim = context.createLinearGradient(0, fadeTop, 0, SLIDE_HEIGHT);
  const rgba = (opacity: number) => `rgba(${color.red}, ${color.green}, ${color.blue}, ${opacity})`;
  scrim.addColorStop(0, rgba(0));
  scrim.addColorStop(Math.min(1, (textTop - fadeTop) / (SLIDE_HEIGHT - fadeTop)), rgba(SCRIM_TEXT_OPACITY));
  scrim.addColorStop(1, rgba(SCRIM_BOTTOM_OPACITY));
  context.fillStyle = scrim;
  context.fillRect(0, fadeTop, SLIDE_WIDTH, SLIDE_HEIGHT - fadeTop);
}

/** The picture's own darkest palette tone, the colour of the scrim drawn over it. */
export function darkestOf(poster: LoadedPoster): Rgb {
  return parseHexColor(choosePaper(poster.palette).darkest) ?? BLACK;
}

function drawCover(context: CanvasRenderingContext2D, slide: Extract<CarouselSlide, { kind: "cover" }>, poster: LoadedPoster, paper: Paper) {
  drawPosterCrop(context, poster.bitmap, 0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
  drawWash(context, parseHexColor(paper.darkest) ?? { red: 0, green: 0, blue: 0 }, SLIDE_HEIGHT * 0.45, SLIDE_HEIGHT, 0.85);
  const captionLines = [...slide.titleLines, ...slide.metaLines.map((line) => line.toUpperCase())];
  const captionHeight = captionLines.length * CAPTION_PX * BODY_LINE_HEIGHT;
  const hookHeight = slide.hookLines.length * TITLE_PX * TITLE_LINE_HEIGHT;
  const bottom = SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX - 24;
  const hookTop = bottom - captionHeight - 24 - hookHeight;
  drawLabel(context, slide.label, "#FFFFFF", hookTop - COVER_LABEL_GAP - CAPTION_PX);
  drawLines(context, slide.hookLines, SLIDE_MARGIN, hookTop, TITLE_PX, 500, TITLE_LINE_HEIGHT, "#FFFFFF");
  drawLines(context, captionLines, SLIDE_MARGIN, bottom - captionHeight, CAPTION_PX, 400, BODY_LINE_HEIGHT, "#FFFFFF");
}

/**
 * Poster band across the top showing this insight's slice of the poster, washed into the paper at its bottom edge;
 * the numbered label, the swatches, the headline and the body on the paper panel below.
 */
function drawInsight(
  context: CanvasRenderingContext2D,
  slide: Extract<CarouselSlide, { kind: "insight" }>,
  poster: LoadedPoster,
  paper: Paper,
  gallery: readonly LoadedPoster[],
) {
  const picture = slide.image === null ? poster : gallery[slide.image] ?? poster;
  context.fillStyle = paper.paper;
  context.fillRect(0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
  drawPosterCrop(context, picture.bitmap, 0, 0, SLIDE_WIDTH, INSIGHT_BAND_PX, slide.focus);
  drawWash(context, parseHexColor(paper.paper) ?? { red: 255, green: 255, blue: 255 }, INSIGHT_BAND_PX - INSIGHT_WASH_PX, INSIGHT_BAND_PX, 1);
  drawLabel(context, slide.label, paper.ink, INSIGHT_LABEL_TOP);
  poster.palette.forEach((color, index) => {
    context.fillStyle = color;
    context.fillRect(SLIDE_MARGIN + index * (SWATCH_PX + 8), INSIGHT_SWATCH_TOP, SWATCH_PX, SWATCH_PX);
  });
  drawLines(context, slide.headlineLines, SLIDE_MARGIN, INSIGHT_BODY_TOP, TITLE_PX, 500, TITLE_LINE_HEIGHT, paper.ink);
  drawLines(context, slide.lines, SLIDE_MARGIN, slide.bodyTop, BODY_PX, 400, BODY_LINE_HEIGHT, paper.ink);
}

/**
 * One of the exhibition's extra pictures full-bleed under the roundup's scrim in its own darkest tone, so the caption
 * and the footer read in white whatever the picture; the caption stacks up from the footer.
 */
function drawPicture(context: CanvasRenderingContext2D, slide: Extract<CarouselSlide, { kind: "picture" }>, picture: LoadedPoster) {
  drawPosterCrop(context, picture.bitmap, 0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
  drawScrim(context, darkestOf(picture), slide.captionTop);
  drawLines(context, slide.captionLines, SLIDE_MARGIN, slide.captionTop, CAPTION_PX, 400, BODY_LINE_HEIGHT, "#FFFFFF");
}

/** White paper: poster tile top-left under the label, the placed facts and tip, and the QR tile bottom-right. */
async function drawClosing(
  context: CanvasRenderingContext2D,
  slide: Extract<CarouselSlide, { kind: "closing" }>,
  poster: LoadedPoster,
  paper: Paper,
  exhibitionUrl: string,
) {
  context.fillStyle = "#FFFFFF";
  context.fillRect(0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
  drawLabel(context, slide.label, paper.ink);
  drawPosterCrop(context, poster.bitmap, SLIDE_MARGIN, CLOSING_FACTS_TOP, CLOSING_POSTER_WIDTH_PX, CLOSING_POSTER_HEIGHT_PX);
  const factsTop = drawLines(context, slide.factLines.slice(0, slide.titleLineCount), slide.factsLeft, slide.factsTop, BODY_PX, 500, BODY_LINE_HEIGHT, paper.ink);
  drawLines(context, slide.factLines.slice(slide.titleLineCount), slide.factsLeft, factsTop, BODY_PX, 400, BODY_LINE_HEIGHT, paper.ink);
  drawLines(context, slide.tipLines, SLIDE_MARGIN, slide.tipTop, BODY_PX, 500, BODY_LINE_HEIGHT, paper.ink);

  const { encode } = await import("uqr");
  const matrix = encode(exhibitionUrl, { ecc: "M", border: 4, boostEcc: true });
  const tileLeft = SLIDE_WIDTH - SLIDE_MARGIN - QR_TILE_PX;
  context.fillStyle = "#FFFFFF";
  context.fillRect(tileLeft, QR_TILE_TOP, QR_TILE_PX, QR_TILE_PX);
  const module = QR_TILE_PX / matrix.size;
  context.fillStyle = paper.ink;
  for (let row = 0; row < matrix.size; row += 1) {
    for (let column = 0; column < matrix.size; column += 1) {
      if (matrix.data[row]?.[column]) {
        context.fillRect(tileLeft + column * module, QR_TILE_TOP + row * module, Math.ceil(module), Math.ceil(module));
      }
    }
  }
}

/** Renders every slide of a layout to PNG bytes; `gallery` holds the extra pictures the insight and picture slides name. */
export async function renderCarousel(
  layout: CarouselLayout,
  poster: LoadedPoster,
  exhibitionUrl: string,
  gallery: readonly LoadedPoster[] = [],
): Promise<RenderedSlide[]> {
  const paper = choosePaper(poster.palette);
  const rendered: RenderedSlide[] = [];
  for (const [index, slide] of layout.slides.entries()) {
    const { canvas, context } = newCanvas();
    if (slide.kind === "cover") drawCover(context, slide, poster, paper);
    else if (slide.kind === "insight") drawInsight(context, slide, poster, paper, gallery);
    else if (slide.kind === "picture") drawPicture(context, slide, gallery[slide.image] ?? poster);
    else await drawClosing(context, slide, poster, paper, exhibitionUrl);
    drawFooter(context, slide.kind === "cover" || slide.kind === "picture" ? "#FFFFFF" : paper.ink, index, layout.slides.length);
    rendered.push({ slide, png: await toPng(canvas) });
  }
  return rendered;
}

export async function ensureFonts(): Promise<void> {
  const fonts = (document as Document & { fonts?: FontFaceSet }).fonts;
  if (!fonts) return;
  await Promise.all([fonts.load(font(TITLE_PX, 500)), fonts.load(font(BODY_PX, 400)), fonts.load(font(BODY_PX, 500))]);
}

export function canvasMeasure(): Measure {
  const { context } = newCanvas();
  return (text, px, weight) => {
    context.font = font(px, weight);
    return context.measureText(text).width;
  };
}

/**
 * Loads the extra pictures a carousel can draw; one that fails is left out, so the pictures after it move up a
 * place and the last insight may show the cover instead. `failed` counts those, for the report.
 */
async function loadGallery(
  images: readonly CarouselGalleryImage[],
): Promise<{ loaded: { poster: LoadedPoster; image: CarouselGalleryImage }[]; failed: number }> {
  const wanted = images.slice(0, MAX_GALLERY_PICTURES);
  const settled = await Promise.allSettled(wanted.map((image) => loadPoster(image.url)));
  const loaded = settled.flatMap((result, index) => (result.status === "fulfilled" ? [{ poster: result.value, image: wanted[index] }] : []));
  return { loaded, failed: wanted.length - loaded.length };
}

/**
 * Fetches the cover and the exhibition's extra pictures, lays out and renders the slides, and zips them with the
 * caption; refused without a cover.
 */
export async function exportCarousel(input: CarouselExportInput): Promise<CarouselExportResult> {
  const work = (async () => {
    await ensureFonts();
    const poster = await loadPoster(input.coverUrl);
    const { loaded: gallery, failed } = await loadGallery(input.gallery ?? []);
    try {
      const layout = layoutCarousel(input.note, input.facts, input.language, canvasMeasure(), {
        coverCredit: input.coverCredit ?? "",
        gallery: gallery.map(({ image }) => ({ credit: image.credit, caption: pick(input.language, image.altKo, image.altEn) })),
      });
      const unloaded = unloadedPicturesReport(failed);
      if (unloaded) layout.omitted.push(unloaded);
      const slides = await renderCarousel(layout, poster, input.exhibitionUrl, gallery.map((loaded) => loaded.poster));
      return { archive: buildCarouselArchive(layout, slides), layout, slideCount: slides.length };
    } finally {
      poster.bitmap.close();
      for (const image of gallery) image.poster.bitmap.close();
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
