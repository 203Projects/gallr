import type { CuratorNoteLanguage, CuratorNoteVersion } from "../repositories/AdminCuratorNoteRepository";

/** Instagram portrait slides (spec 090 FR-015, DESIGN.md exported-content exception). */
export const SLIDE_WIDTH = 1080;
export const SLIDE_HEIGHT = 1350;
export const SLIDE_MARGIN = 72;
/** Export type sizes at 1080 width; text is never shrunk below these (FR-016). */
export const TITLE_PX = 64;
export const BODY_PX = 44;
export const CAPTION_PX = 32;
export const TITLE_LINE_HEIGHT = 1.15;
export const BODY_LINE_HEIGHT = 1.4;
/** Fixed furniture shared by the layout budget and the canvas: swatches, the wordmark footer, the QR tile. */
export const SWATCH_PX = 24;
export const WORDMARK_PX = 40;
export const QR_TILE_PX = 320;
/** Where a picture sits outside the full-bleed slides: a band across the top of an insight slide, the poster as a 3:4 tile on the closing slide. */
export const INSIGHT_BAND_PX = 480;
export const CLOSING_POSTER_WIDTH_PX = 400;
export const CLOSING_POSTER_HEIGHT_PX = Math.round((CLOSING_POSTER_WIDTH_PX * 4) / 3);
const MAX_INSIGHT_SLIDES = 4;
/** A carousel never runs past this many slides: the cover, the insight slides, the picture slides and the closing slide. */
export const MAX_SLIDES = 10;
/** The most extra pictures one carousel can draw: every slide between the cover and the closing slide. */
export const MAX_GALLERY_PICTURES = MAX_SLIDES - 2;
const MAX_PICTURE_CAPTION_LINES = 4;
const MAX_HOOK_LINES = 4;
const MAX_TITLE_LINES = 2;
const MAX_META_LINES = 2;
/** An insight's opening sentence is set as a headline when it holds within this many title lines. */
export const MAX_HEADLINE_LINES = 3;
const MAX_TERM_TAGS = 6;
const COLUMN_GAP = 48;
const BLOCK_GAP = 32;
export const CONTENT_WIDTH = SLIDE_WIDTH - 2 * SLIDE_MARGIN;
const BODY_LINE_PX = BODY_PX * BODY_LINE_HEIGHT;
const TITLE_LINE_PX = TITLE_PX * TITLE_LINE_HEIGHT;
/** Text stops here: the wordmark sits on the bottom margin with 48px of clearance above it. */
export const CONTENT_BOTTOM = SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX - COLUMN_GAP;
/** The top edge of the wordmark footer; a caption over a full-bleed picture ends 24px above it. */
const FOOTER_TOP = SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX;
const PICTURE_CAPTION_GAP = 24;
/** Insight slide: the paper panel under the band keeps the label, the swatch row and the body as before, shifted down by the band. */
export const INSIGHT_LABEL_TOP = INSIGHT_BAND_PX + SLIDE_MARGIN;
export const INSIGHT_SWATCH_TOP = INSIGHT_LABEL_TOP + CAPTION_PX + 16;
export const INSIGHT_BODY_TOP = INSIGHT_SWATCH_TOP + SWATCH_PX + 48;
/** Body lines that fit between the band's panel furniture and the footer at 44px with the 1.4 line height (FR-016: never shrunk). */
export const MAX_BODY_LINES = Math.floor((CONTENT_BOTTOM - INSIGHT_BODY_TOP) / BODY_LINE_PX);
/** Closing slide: poster tile top-left under the label, facts in the column to its right, the QR tile bottom-right. */
export const CLOSING_FACTS_TOP = SLIDE_MARGIN + CAPTION_PX + 40;
export const CLOSING_FACTS_COLUMN_LEFT = SLIDE_MARGIN + CLOSING_POSTER_WIDTH_PX + COLUMN_GAP;
export const CLOSING_FACTS_COLUMN_WIDTH = CONTENT_WIDTH - CLOSING_POSTER_WIDTH_PX - COLUMN_GAP;
export const QR_TILE_TOP = CONTENT_BOTTOM - QR_TILE_PX;
/** Width of a line that stays clear of the QR tile once text reaches the tile's rows. */
export const QR_SAFE_WIDTH = CONTENT_WIDTH - QR_TILE_PX - COLUMN_GAP;
const CLOSING_POSTER_BOTTOM = CLOSING_FACTS_TOP + CLOSING_POSTER_HEIGHT_PX;
const MAX_FACT_LINES_BESIDE = Math.floor(CLOSING_POSTER_HEIGHT_PX / BODY_LINE_PX);
const CLOSING_FACTS_BELOW_TOP = CLOSING_POSTER_BOTTOM + BLOCK_GAP;
const MAX_FACT_LINES_BELOW = Math.floor((CONTENT_BOTTOM - CLOSING_FACTS_BELOW_TOP) / BODY_LINE_PX);

/** One of the exhibition's extra pictures: who to credit and, from its alt text, what it shows in the export language. */
export interface CarouselPicture {
  credit: string;
  caption: string;
}

/** The pictures the export may draw besides the note's text: the cover's credit and the gallery images that loaded, in order. */
export interface CarouselImages {
  coverCredit: string;
  gallery: readonly CarouselPicture[];
}

/** Measures rendered text width in pixels for a font size and weight; the canvas supplies the real one. */
export type Measure = (text: string, fontPx: number, weight: 400 | 500) => number;

/** The facts the export may print, as the packet carries them. */
export interface CarouselFacts {
  name_ko?: string;
  name_en?: string;
  venue_name_ko?: string;
  venue_name_en?: string;
  region_ko?: string;
  region_en?: string;
  opening_date?: string;
  closing_date?: string;
  hours?: string;
  art_terms?: { name_ko?: string; name_en?: string }[];
}

export type CarouselNote = Pick<
  CuratorNoteVersion,
  "hookKo" | "hookEn" | "insightsKo" | "insightsEn" | "tipKo" | "tipEn"
>;

export interface CoverSlide {
  kind: "cover";
  label: string;
  hookLines: string[];
  titleLines: string[];
  /** Venue and dates under the title, wrapped. */
  metaLines: string[];
}

export interface InsightSlide {
  kind: "insight";
  /** The section label with the insight's number, shared by every slide of that insight. */
  label: string;
  /** The insight's opening sentence as a headline; empty on a continuation slide or when it would not fit. */
  headlineLines: string[];
  lines: string[];
  bodyTop: number;
  /** Which slice of the picture the band shows, from 0 (its start) to 1 (its end), so each insight gets its own detail. */
  focus: number;
  /** The exhibition's own extra picture this insight shows, by its place in the gallery; null shows the cover. */
  image: number | null;
}

export interface ClosingSlide {
  kind: "closing";
  label: string;
  dates: string;
  venue: string;
  district: string;
  hours: string | null;
  /** How many of the first [factLines] are the exhibition's title. */
  titleLineCount: number;
  /** The title and the facts wrapped for drawing, beside the poster tile or under it when they would run past it. */
  factLines: string[];
  factsLeft: number;
  factsTop: number;
  tipLines: string[];
  tipTop: number;
}

export interface PictureSlide {
  kind: "picture";
  /** The extra picture shown full-bleed, by its place in the gallery. */
  image: number;
  /** What the picture shows, wrapped; empty when it has no alt text. */
  captionLines: string[];
  /**
   * Where the caption starts, which is where the scrim reaches text strength. Without a caption it is the footer's
   * top, so the wordmark and the slide number still read over the picture.
   */
  captionTop: number;
}

export type CarouselSlide = CoverSlide | InsightSlide | PictureSlide | ClosingSlide;

/**
 * What the carousel is built from: an approved note, or the exhibition alone (its name as the cover line, its
 * pictures and its facts), which any published exhibition can export without a note.
 */
export type CarouselMode = "note" | "exhibition";

const NO_NOTE: CarouselNote = { hookKo: "", hookEn: "", insightsKo: [], insightsEn: [], tipKo: "", tipEn: "" };

export interface CarouselLayout {
  language: CuratorNoteLanguage;
  slides: CarouselSlide[];
  caption: string;
  /**
   * What the export left out, for the report: text that did not fit its slide, verbatim (insight sentences past the
   * four insight slides, facts, a tip or a picture caption; FR-015 scenario 3), and a line counting gallery pictures
   * that could not be loaded.
   */
  omitted: string[];
}

/** The text in the export language, falling back to Korean when there is no English. */
export function pick(language: CuratorNoteLanguage, korean: string | undefined, english: string | undefined): string {
  const ko = korean?.trim() ?? "";
  const en = english?.trim() ?? "";
  return language === "en" ? (en || ko) : ko;
}

/** Greedy word wrap; a word wider than the line breaks by character so Korean without spaces still fits. */
export function wrapText(text: string, fontPx: number, weight: 400 | 500, measure: Measure, width = CONTENT_WIDTH): string[] {
  const lines: string[] = [];
  let current = "";
  const push = () => {
    if (current.trim()) lines.push(current.trim());
    current = "";
  };
  for (const word of text.trim().split(/\s+/).filter(Boolean)) {
    const candidate = current ? `${current} ${word}` : word;
    if (measure(candidate, fontPx, weight) <= width) {
      current = candidate;
      continue;
    }
    push();
    if (measure(word, fontPx, weight) <= width) {
      current = word;
      continue;
    }
    let chunk = "";
    for (const character of Array.from(word)) {
      if (measure(chunk + character, fontPx, weight) <= width) {
        chunk += character;
      } else {
        if (chunk) lines.push(chunk);
        chunk = character;
      }
    }
    current = chunk;
  }
  push();
  return lines;
}

const ABBREVIATION = /(?:^|[\s(])(?:vs|mr|mrs|ms|dr|st|no|jr|sr|inc|ltd|co|e\.g|i\.e)\.$/i;

/**
 * Splits a paragraph into its opening sentence and the rest. A full stop after an abbreviation or inside a number
 * does not end the sentence; a paragraph of one sentence is all lead.
 */
export function splitLead(text: string): { lead: string; rest: string } {
  const paragraph = text.trim().replace(/\s+/g, " ");
  const boundary = /[.?!]["'”’)\]]*\s+/g;
  for (let match = boundary.exec(paragraph); match !== null; match = boundary.exec(paragraph)) {
    const lead = paragraph.slice(0, match.index + match[0].length).trim();
    if (ABBREVIATION.test(lead)) continue;
    return { lead, rest: paragraph.slice(match.index + match[0].length).trim() };
  }
  return { lead: paragraph, rest: "" };
}

export function formatDates(opening: string | undefined, closing: string | undefined): string {
  const start = opening?.replace(/-/g, ".") ?? "";
  const end = closing?.replace(/-/g, ".") ?? "";
  if (start && end && start.slice(0, 5) === end.slice(0, 5)) return `${start} – ${end.slice(5)}`;
  return [start, end].filter(Boolean).join(" – ");
}

export function carouselLabels(language: CuratorNoteLanguage) {
  return language === "ko"
    ? {
      section: "에디터의 시선",
      exhibition: "전시",
      points: "관람 포인트",
      photos: "사진",
      tip: "관람 팁",
      tags: ["gallr", "전시", "서울전시"],
    }
    : {
      section: "EDITOR'S EYE",
      exhibition: "EXHIBITION",
      points: "Viewing points",
      photos: "Photos",
      tip: "Viewing tip",
      tags: ["gallr", "exhibition", "seoul"],
    };
}

export function hashtag(value: string | undefined): string | null {
  const cleaned = (value ?? "").normalize("NFKC").replace(/[^\p{L}\p{N}]+/gu, "");
  return cleaned ? `#${cleaned}` : null;
}

/** Hashtags come only from the packet: venue, district and the reviewed terms (spec assumption). */
export function captionHashtags(facts: CarouselFacts, language: CuratorNoteLanguage): string[] {
  const base = carouselLabels(language).tags.map((tag) => `#${tag}`);
  const venue = hashtag(pick(language, facts.venue_name_ko, facts.venue_name_en));
  const district = hashtag(pick(language, facts.region_ko, facts.region_en));
  const terms = (facts.art_terms ?? [])
    .map((term) => hashtag(pick(language, term.name_ko, term.name_en)))
    .filter((tag): tag is string => tag !== null)
    .slice(0, MAX_TERM_TAGS);
  return Array.from(new Set([...base, venue, district, ...terms].filter((tag): tag is string => tag !== null)));
}

const POINT_MARKS = ["①", "②", "③", "④"];

interface ClosingPlacement {
  titleLineCount: number;
  factLines: string[];
  factsLeft: number;
  factsTop: number;
  tipLines: string[];
  tipTop: number;
  omitted: string[];
}

/**
 * Places the facts and the tip around the poster and QR tiles without shrinking type (FR-016). The facts take the
 * column beside the poster; when they would run past it they wrap under it instead, at the width that stays clear
 * of the QR tile. The tip follows at full width unless it would reach the QR tile's rows, where it wraps to the
 * same clear width, and runs down to the footer. Lines with no room left are reported, not squeezed.
 */
function placeClosing(title: string, facts: string[], tip: string, measure: Measure): ClosingPlacement {
  const omitted: string[] = [];
  const wrapTitle = (width: number) => wrapText(title, BODY_PX, 500, measure, width);
  const wrapFacts = (width: number) => [...wrapTitle(width), ...facts.flatMap((fact) => wrapText(fact, BODY_PX, 400, measure, width))];
  let titleLineCount = wrapTitle(CLOSING_FACTS_COLUMN_WIDTH).length;
  let factLines = wrapFacts(CLOSING_FACTS_COLUMN_WIDTH);
  let factsLeft = CLOSING_FACTS_COLUMN_LEFT;
  let factsTop = CLOSING_FACTS_TOP;
  let factsBottom = Math.max(CLOSING_POSTER_BOTTOM, factsTop + factLines.length * BODY_LINE_PX);
  if (factLines.length > MAX_FACT_LINES_BESIDE) {
    titleLineCount = wrapTitle(QR_SAFE_WIDTH).length;
    factLines = wrapFacts(QR_SAFE_WIDTH);
    factsLeft = SLIDE_MARGIN;
    factsTop = CLOSING_FACTS_BELOW_TOP;
    if (factLines.length > MAX_FACT_LINES_BELOW) omitted.push(factLines.slice(MAX_FACT_LINES_BELOW).join(" "));
    factLines = factLines.slice(0, MAX_FACT_LINES_BELOW);
    factsBottom = factsTop + factLines.length * BODY_LINE_PX;
  }

  const tipTop = factsBottom + BLOCK_GAP;
  let tipLines = tip ? wrapText(tip, BODY_PX, 500, measure) : [];
  if (tipTop + tipLines.length * BODY_LINE_PX > QR_TILE_TOP) tipLines = wrapText(tip, BODY_PX, 500, measure, QR_SAFE_WIDTH);
  const tipRoom = Math.max(0, Math.floor((CONTENT_BOTTOM - tipTop) / BODY_LINE_PX));
  if (tipLines.length > tipRoom) omitted.push(tipLines.slice(tipRoom).join(" "));
  return {
    titleLineCount: Math.min(titleLineCount, factLines.length),
    factLines,
    factsLeft,
    factsTop,
    tipLines: tipLines.slice(0, tipRoom),
    tipTop,
    omitted,
  };
}

/**
 * Lays out the carousel: a cover with the hook, two to four insight slides, a closing slide, and the caption.
 * Each insight is numbered and opens with its first sentence as a headline over the rest as body, under one of the
 * exhibition's extra pictures when there are any and a slice of the cover otherwise. Insight text that does not
 * fit a slide continues on the next one; after the fourth insight slide the rest is reported in
 * [CarouselLayout.omitted] rather than shrunk.
 *
 * Pictures no insight slide shows each get a slide of their own, as many as fit within [MAX_SLIDES]: one after
 * each insight that has slides, in gallery order, and the rest before the closing slide. That includes the picture
 * of an insight left out by the four-slide limit, which would otherwise be dropped while slides stood free.
 */
export function layoutCarousel(
  note: CarouselNote,
  facts: CarouselFacts,
  language: CuratorNoteLanguage,
  measure: Measure,
  images: CarouselImages = { coverCredit: "", gallery: [] },
  mode: CarouselMode = "note",
): CarouselLayout {
  const text = carouselLabels(language);
  const label = mode === "note" ? text.section : text.exhibition;
  const hook = pick(language, note.hookKo, note.hookEn);
  const insights = (language === "en" && note.insightsEn.length > 0 ? note.insightsEn : note.insightsKo)
    .map((insight) => insight.text.trim())
    .filter(Boolean);
  const tip = pick(language, note.tipKo, note.tipEn);
  const title = pick(language, facts.name_ko, facts.name_en);
  const venue = pick(language, facts.venue_name_ko, facts.venue_name_en);
  const district = pick(language, facts.region_ko, facts.region_en);
  const dates = formatDates(facts.opening_date, facts.closing_date);

  const slides: CarouselSlide[] = [];
  const omitted: string[] = [];

  // Without a note the exhibition's name is the cover line, as the reference posts set a name over a picture.
  const hookLines = wrapText(mode === "note" ? hook : title, TITLE_PX, 500, measure);
  if (hookLines.length > MAX_HOOK_LINES) omitted.push(hookLines.slice(MAX_HOOK_LINES).join(" "));
  slides.push({
    kind: "cover",
    label,
    hookLines: hookLines.slice(0, MAX_HOOK_LINES),
    titleLines: mode === "note" ? wrapText(title, CAPTION_PX, 500, measure).slice(0, MAX_TITLE_LINES) : [],
    metaLines: wrapText([venue, dates].filter(Boolean).join(" · "), CAPTION_PX, 400, measure).slice(0, MAX_META_LINES),
  });

  const leads: string[] = [];
  /** The slides of each insight, kept apart so the picture slides can be dealt between them. */
  const insightGroups: InsightSlide[][] = [];
  let insightSlides = 0;
  insights.forEach((paragraph, insightIndex) => {
    const group: InsightSlide[] = [];
    insightGroups.push(group);
    const label = `${text.section} ${String(insightIndex + 1).padStart(2, "0")}`;
    const image = insightIndex < images.gallery.length ? insightIndex : null;
    const focus = image === null ? (insightIndex + 0.5) / insights.length : 0.5;
    const { lead, rest } = splitLead(paragraph);
    leads.push(lead);
    const leadLines = wrapText(lead, TITLE_PX, 500, measure);
    const headline = leadLines.length <= MAX_HEADLINE_LINES ? leadLines : [];
    let lines = wrapText(headline.length > 0 ? rest : paragraph, BODY_PX, 400, measure);
    let opening = true;
    do {
      if (insightSlides >= MAX_INSIGHT_SLIDES) {
        omitted.push(opening ? paragraph : lines.join(" "));
        break;
      }
      const headlineLines = opening ? headline : [];
      const bodyTop = INSIGHT_BODY_TOP + (headlineLines.length > 0 ? headlineLines.length * TITLE_LINE_PX + BLOCK_GAP : 0);
      const room = Math.floor((CONTENT_BOTTOM - bodyTop) / BODY_LINE_PX);
      group.push({ kind: "insight", label, headlineLines, lines: lines.slice(0, room), bodyTop, focus, image });
      insightSlides += 1;
      lines = lines.slice(room);
      opening = false;
    } while (lines.length > 0);
  });

  const used = new Set(insightGroups.flatMap((group) => group.flatMap((slide) => (slide.image === null ? [] : [slide.image]))));
  const pictureRoom = Math.max(0, MAX_SLIDES - 2 - insightSlides);
  const pictureSlides = images.gallery
    .map((picture, index) => ({ picture, index }))
    .filter(({ index }) => !used.has(index))
    .slice(0, pictureRoom)
    .map(({ picture, index }): PictureSlide => {
      const wrapped = wrapText(picture.caption, CAPTION_PX, 400, measure);
      if (wrapped.length > MAX_PICTURE_CAPTION_LINES) omitted.push(wrapped.slice(MAX_PICTURE_CAPTION_LINES).join(" "));
      const captionLines = wrapped.slice(0, MAX_PICTURE_CAPTION_LINES);
      const captionTop =
        captionLines.length > 0 ? FOOTER_TOP - PICTURE_CAPTION_GAP - captionLines.length * CAPTION_PX * BODY_LINE_HEIGHT : FOOTER_TOP;
      return { kind: "picture", image: index, captionLines, captionTop };
    });
  let dealt = 0;
  for (const group of insightGroups) {
    slides.push(...group);
    if (group.length > 0 && dealt < pictureSlides.length) slides.push(pictureSlides[dealt++]);
  }
  slides.push(...pictureSlides.slice(dealt));

  const hours = facts.hours?.trim() || null;
  const place = [venue, district].filter(Boolean).join(" · ");
  const closing = placeClosing(
    title,
    [dates, place, hours ?? ""].filter(Boolean),
    tip ? `${text.tip} · ${tip}` : "",
    measure,
  );
  omitted.push(...closing.omitted);
  slides.push({
    kind: "closing",
    label,
    dates,
    venue,
    district,
    hours,
    titleLineCount: closing.titleLineCount,
    factLines: closing.factLines,
    factsLeft: closing.factsLeft,
    factsTop: closing.factsTop,
    tipLines: closing.tipLines,
    tipTop: closing.tipTop,
  });

  const drawn = new Set(
    slides.flatMap((slide) => ((slide.kind === "insight" || slide.kind === "picture") && slide.image !== null ? [slide.image] : [])),
  );
  const credits = Array.from(
    new Set(
      [images.coverCredit, ...images.gallery.filter((_, index) => drawn.has(index)).map((picture) => picture.credit)]
        .map((credit) => credit.trim())
        .filter(Boolean),
    ),
  );
  const caption = [
    [hook],
    [title, place, dates, hours?.split(/\s*\n\s*/).join(" · ") ?? ""],
    leads.length > 0 ? [text.points, ...leads.slice(0, POINT_MARKS.length).map((lead, index) => `${POINT_MARKS[index]} ${lead}`)] : [],
    [tip ? `${text.tip} · ${tip}` : ""],
    [credits.length > 0 ? `${text.photos} · ${credits.join(", ")}` : ""],
    [captionHashtags(facts, language).join(" ")],
  ]
    .map((block) => block.filter(Boolean).join("\n"))
    .filter(Boolean)
    .join("\n\n");

  return { language, slides, caption, omitted };
}

/**
 * Lays out a carousel of an exhibition that has no approved note (or whose note is not wanted): the name over the
 * cover, a full-bleed slide for each gallery picture with what it shows, and the closing slide with the facts and
 * the QR code, ten slides at most. The caption holds the facts, the photo credits and the tags; no note wording.
 */
export function layoutExhibitionCarousel(
  facts: CarouselFacts,
  language: CuratorNoteLanguage,
  measure: Measure,
  images: CarouselImages = { coverCredit: "", gallery: [] },
): CarouselLayout {
  return layoutCarousel(NO_NOTE, facts, language, measure, images, "exhibition");
}

/**
 * The report line for gallery pictures that could not be fetched, which the export leaves out; null when every
 * picture loaded. Without it the editor would get a shorter carousel and no reason.
 */
export function unloadedPicturesReport(failed: number): string | null {
  if (failed <= 0) return null;
  return failed === 1
    ? "1 of the exhibition's pictures could not be loaded and was left out."
    : `${failed} of the exhibition's pictures could not be loaded and were left out.`;
}

/** Entry names inside the archive, in slide order. */
export function slideFileName(index: number, slide: CarouselSlide): string {
  return `${String(index + 1).padStart(2, "0")}-${slide.kind}.png`;
}
