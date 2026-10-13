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
const MAX_INSIGHT_SLIDES = 4;
const MAX_HOOK_LINES = 4;
const MAX_TITLE_LINES = 2;
const MAX_TIP_LINES = 4;
const MAX_TERM_TAGS = 6;
const CONTENT_WIDTH = SLIDE_WIDTH - 2 * SLIDE_MARGIN;
/** Body lines that fit between the slide label and the footer at 44px with the 1.4 line height. */
const MAX_BODY_LINES = 11;

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
  venue: string;
}

export interface InsightSlide {
  kind: "insight";
  label: string;
  lines: string[];
}

export interface ClosingSlide {
  kind: "closing";
  label: string;
  dates: string;
  venue: string;
  district: string;
  hours: string | null;
  tipLines: string[];
}

export type CarouselSlide = CoverSlide | InsightSlide | ClosingSlide;

export interface CarouselLayout {
  language: CuratorNoteLanguage;
  slides: CarouselSlide[];
  caption: string;
  /** Sentences that did not fit within the four insight slides, verbatim (FR-015 scenario 3). */
  omitted: string[];
}

function pick(language: CuratorNoteLanguage, korean: string | undefined, english: string | undefined): string {
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

export function formatDates(opening: string | undefined, closing: string | undefined): string {
  const start = opening?.replace(/-/g, ".") ?? "";
  const end = closing?.replace(/-/g, ".") ?? "";
  if (start && end && start.slice(0, 5) === end.slice(0, 5)) return `${start} – ${end.slice(5)}`;
  return [start, end].filter(Boolean).join(" – ");
}

function labels(language: CuratorNoteLanguage) {
  return language === "ko"
    ? {
      section: "큐레이터의 시선",
      tip: "관람 팁",
      tags: ["gallr", "전시", "서울전시"],
    }
    : {
      section: "CURATOR'S EYE",
      tip: "Viewing tip",
      tags: ["gallr", "exhibition", "seoul"],
    };
}

function hashtag(value: string | undefined): string | null {
  const cleaned = (value ?? "").normalize("NFKC").replace(/[^\p{L}\p{N}]+/gu, "");
  return cleaned ? `#${cleaned}` : null;
}

/** Hashtags come only from the packet: venue, district and the reviewed terms (spec assumption). */
export function captionHashtags(facts: CarouselFacts, language: CuratorNoteLanguage): string[] {
  const base = labels(language).tags.map((tag) => `#${tag}`);
  const venue = hashtag(pick(language, facts.venue_name_ko, facts.venue_name_en));
  const district = hashtag(pick(language, facts.region_ko, facts.region_en));
  const terms = (facts.art_terms ?? [])
    .map((term) => hashtag(pick(language, term.name_ko, term.name_en)))
    .filter((tag): tag is string => tag !== null)
    .slice(0, MAX_TERM_TAGS);
  return Array.from(new Set([...base, venue, district, ...terms].filter((tag): tag is string => tag !== null)));
}

/**
 * Lays out the carousel: a cover with the hook, two to four insight slides, a closing slide, and the caption.
 * Insight text that does not fit a slide continues on the next one; after the fourth insight slide the rest is
 * reported in [CarouselLayout.omitted] rather than shrunk.
 */
export function layoutCarousel(
  note: CarouselNote,
  facts: CarouselFacts,
  language: CuratorNoteLanguage,
  measure: Measure,
): CarouselLayout {
  const text = labels(language);
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

  const hookLines = wrapText(hook, TITLE_PX, 500, measure);
  if (hookLines.length > MAX_HOOK_LINES) omitted.push(hookLines.slice(MAX_HOOK_LINES).join(" "));
  slides.push({
    kind: "cover",
    label: text.section,
    hookLines: hookLines.slice(0, MAX_HOOK_LINES),
    titleLines: wrapText(title, CAPTION_PX, 500, measure).slice(0, MAX_TITLE_LINES),
    venue,
  });

  let insightSlides = 0;
  for (const paragraph of insights) {
    let lines = wrapText(paragraph, BODY_PX, 400, measure);
    while (lines.length > 0) {
      if (insightSlides >= MAX_INSIGHT_SLIDES) {
        omitted.push(lines.join(" "));
        lines = [];
        break;
      }
      slides.push({ kind: "insight", label: text.section, lines: lines.slice(0, MAX_BODY_LINES) });
      insightSlides += 1;
      lines = lines.slice(MAX_BODY_LINES);
    }
  }

  const tipLines = tip ? wrapText(`${text.tip} · ${tip}`, BODY_PX, 500, measure) : [];
  if (tipLines.length > MAX_TIP_LINES) omitted.push(tipLines.slice(MAX_TIP_LINES).join(" "));
  slides.push({
    kind: "closing",
    label: text.section,
    dates,
    venue,
    district,
    hours: facts.hours?.trim() || null,
    tipLines: tipLines.slice(0, MAX_TIP_LINES),
  });

  const caption = [
    hook,
    "",
    insights[0] ?? "",
    "",
    [dates, venue].filter(Boolean).join(" · "),
    tip,
    "",
    captionHashtags(facts, language).join(" "),
  ].filter((line, index, all) => !(line === "" && all[index - 1] === "")).join("\n").trim();

  return { language, slides, caption, omitted };
}

/** Entry names inside the archive, in slide order. */
export function slideFileName(index: number, slide: CarouselSlide): string {
  return `${String(index + 1).padStart(2, "0")}-${slide.kind}.png`;
}
