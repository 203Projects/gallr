import type { ApprovedCuratorNote, CuratorNoteLanguage } from "../repositories/AdminCuratorNoteRepository";
import {
  BODY_LINE_HEIGHT,
  BODY_PX,
  CAPTION_PX,
  CONTENT_BOTTOM,
  CONTENT_WIDTH,
  SLIDE_MARGIN,
  TITLE_PX,
  carouselLabels,
  formatDates,
  hashtag,
  pick,
  wrapText,
  type CoverSlide,
  type Measure,
} from "./carousel";

/** A roundup is one carousel of several approved notes: a cover, a slide per exhibition, then the list. */
export const MIN_ROUNDUP_ENTRIES = 2;
export const MAX_ROUNDUP_ENTRIES = 10;
const MAX_TITLE_LINES = 4;
const MAX_NAME_LINES = 3;
const MAX_ENTRY_META_LINES = 3;
const MAX_ENTRY_HOOK_LINES = 4;
const MAX_INDEX_NAME_LINES = 2;
const MAX_INDEX_META_LINES = 2;
const MAX_TAGS = 15;
const CAPTION_LINE_PX = CAPTION_PX * BODY_LINE_HEIGHT;
/** The list slide: rows start under the label and are separated by this gap. */
export const INDEX_TOP = SLIDE_MARGIN + CAPTION_PX + 40;
export const INDEX_ROW_GAP = 16;
const ENTRY_MARKS = ["①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩"];

/** One exhibition: its picture full-bleed with the name, where and when, and the note's hook as the reason to go. */
export interface RoundupEntrySlide {
  kind: "entry";
  exhibitionId: string;
  /** The entry's place, `01 / 05`. */
  label: string;
  nameLines: string[];
  metaLines: string[];
  hookLines: string[];
}

export interface RoundupIndexRow {
  nameLines: string[];
  metaLines: string[];
}

/** Every exhibition of the roundup in order, to save or screenshot; continues on a second slide when it is long. */
export interface RoundupIndexSlide {
  kind: "index";
  label: string;
  rows: RoundupIndexRow[];
}

export type RoundupSlide = CoverSlide | RoundupEntrySlide | RoundupIndexSlide;

export interface RoundupLayout {
  language: CuratorNoteLanguage;
  slides: RoundupSlide[];
  caption: string;
  /** Text that did not fit its slide, verbatim; nothing is shrunk to make it fit. */
  omitted: string[];
}

export class RoundupSizeError extends Error {
  constructor() {
    super(`A roundup holds ${MIN_ROUNDUP_ENTRIES} to ${MAX_ROUNDUP_ENTRIES} exhibitions and needs a title.`);
    this.name = "RoundupSizeError";
  }
}

function countLabel(language: CuratorNoteLanguage, count: number): string {
  return language === "ko" ? `전시 ${count}곳` : `${count} exhibitions`;
}

function place(entry: ApprovedCuratorNote, language: CuratorNoteLanguage): string {
  return [pick(language, entry.venueNameKo, entry.venueNameEn), pick(language, entry.regionKo, entry.regionEn)].filter(Boolean).join(" · ");
}

function dates(entry: ApprovedCuratorNote): string {
  return formatDates(entry.openingDate ?? undefined, entry.closingDate ?? undefined);
}

/** Wraps to at most `limit` lines and reports what ran past them. */
function clamp(lines: string[], limit: number, omitted: string[]): string[] {
  if (lines.length > limit) omitted.push(lines.slice(limit).join(" "));
  return lines.slice(0, limit);
}

/** Base tags, then each venue and district once, in slide order. */
export function roundupHashtags(entries: readonly ApprovedCuratorNote[], language: CuratorNoteLanguage): string[] {
  const base = carouselLabels(language).tags.map((tag) => `#${tag}`);
  const venues = entries.map((entry) => hashtag(pick(language, entry.venueNameKo, entry.venueNameEn)));
  const districts = entries.map((entry) => hashtag(pick(language, entry.regionKo, entry.regionEn)));
  return Array.from(new Set([...base, ...venues, ...districts].filter((tag): tag is string => tag !== null))).slice(0, MAX_TAGS);
}

/**
 * Lays out a roundup in the order given: the editor's title over the first exhibition's picture, one slide per
 * exhibition, the list, and the caption. Type sizes are the export's fixed ones.
 */
export function layoutRoundup(
  title: string,
  entries: readonly ApprovedCuratorNote[],
  language: CuratorNoteLanguage,
  measure: Measure,
): RoundupLayout {
  const heading = title.trim();
  if (!heading || entries.length < MIN_ROUNDUP_ENTRIES || entries.length > MAX_ROUNDUP_ENTRIES) throw new RoundupSizeError();
  const text = carouselLabels(language);
  const omitted: string[] = [];
  const total = String(entries.length).padStart(2, "0");

  const slides: RoundupSlide[] = [{
    kind: "cover",
    label: text.section,
    hookLines: clamp(wrapText(heading, TITLE_PX, 500, measure), MAX_TITLE_LINES, omitted),
    titleLines: [],
    metaLines: [countLabel(language, entries.length)],
  }];

  entries.forEach((entry, index) => {
    slides.push({
      kind: "entry",
      exhibitionId: entry.exhibitionId,
      label: `${String(index + 1).padStart(2, "0")} / ${total}`,
      nameLines: clamp(wrapText(pick(language, entry.nameKo, entry.nameEn), TITLE_PX, 500, measure), MAX_NAME_LINES, omitted),
      metaLines: clamp(
        [place(entry, language), dates(entry)].filter(Boolean).flatMap((line) => wrapText(line, CAPTION_PX, 400, measure)),
        MAX_ENTRY_META_LINES,
        omitted,
      ),
      hookLines: clamp(wrapText(pick(language, entry.hookKo, entry.hookEn), BODY_PX, 400, measure), MAX_ENTRY_HOOK_LINES, omitted),
    });
  });

  let rows: RoundupIndexRow[] = [];
  let top = INDEX_TOP;
  entries.forEach((entry, index) => {
    const name = `${String(index + 1).padStart(2, "0")} ${pick(language, entry.nameKo, entry.nameEn)}`;
    const row: RoundupIndexRow = {
      nameLines: clamp(wrapText(name, CAPTION_PX, 500, measure, CONTENT_WIDTH), MAX_INDEX_NAME_LINES, omitted),
      metaLines: clamp(
        wrapText([place(entry, language), dates(entry)].filter(Boolean).join(" · "), CAPTION_PX, 400, measure),
        MAX_INDEX_META_LINES,
        omitted,
      ),
    };
    const height = (row.nameLines.length + row.metaLines.length) * CAPTION_LINE_PX;
    if (rows.length > 0 && top + height > CONTENT_BOTTOM) {
      slides.push({ kind: "index", label: text.section, rows });
      rows = [];
      top = INDEX_TOP;
    }
    rows.push(row);
    top += height + INDEX_ROW_GAP;
  });
  slides.push({ kind: "index", label: text.section, rows });

  const caption = [
    heading,
    ...entries.map((entry, index) =>
      [`${ENTRY_MARKS[index]} ${pick(language, entry.nameKo, entry.nameEn)}`, place(entry, language), dates(entry)].filter(Boolean).join("\n")
    ),
    roundupHashtags(entries, language).join(" "),
  ].filter(Boolean).join("\n\n");

  return { language, slides, caption, omitted };
}

/** Entry names inside the archive, in slide order. */
export function roundupSlideFileName(index: number, slide: RoundupSlide): string {
  return `${String(index + 1).padStart(2, "0")}-${slide.kind}.png`;
}

export function roundupArchiveName(language: CuratorNoteLanguage, count: number, day: string): string {
  return `curator-roundup-${day}-${count}-${language}.zip`;
}

/** The exhibitions that close in the month of `day` (YYYY-MM-DD), as the list orders them, up to the roundup's size. */
export function closingInMonth(notes: readonly ApprovedCuratorNote[], day: string): ApprovedCuratorNote[] {
  const month = day.slice(0, 7);
  return notes
    .filter((note) => note.coverImageUrl !== null && (note.closingDate ?? "").slice(0, 7) === month)
    .slice(0, MAX_ROUNDUP_ENTRIES);
}

const EN_MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

/** A plain starting title for a "closing this month" roundup; the editor rewrites it. */
export function closingTitle(language: CuratorNoteLanguage, day: string, count: number): string {
  const month = Number(day.slice(5, 7));
  return language === "ko" ? `${month}월에 끝나는 전시 ${count}` : `${count} exhibitions closing in ${EN_MONTHS[month - 1] ?? ""}`.trim();
}
