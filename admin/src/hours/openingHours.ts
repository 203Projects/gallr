import {
  HOURS_WEEKLY_DAYS,
  type HoursWeekly,
  type HoursWeeklyDay,
} from "../domain";

export { HOURS_WEEKLY_DAYS };

// TypeScript port of shared/src/commonMain/kotlin/com/gallr/shared/hours/OpeningHoursParser.kt, kept in step with
// the web route page's port (web/api/_lib/opening-hours.js). openingHours.test.ts checks it against
// specs/089-personal-routes/contracts/opening-hours-parity.json, which the Kotlin host test regenerates, and the
// grammar is documented in specs/088-discovery-accuracy-fixes/contracts/opening-hours-grammar.md.
//
// Admin uses the reading only to prefill the weekly hours editor; staff confirm each day before it is stored.

export const OPENING_HOURS_WEEKDAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"] as const;
export type OpeningHoursWeekday = (typeof OPENING_HOURS_WEEKDAYS)[number];

export type OpeningHoursCompleteness = "COMPLETE" | "PARTIAL" | "UNKNOWN";

export interface DailyOpening {
  /** 24-hour `HH:MM`. */
  opens: string;
  /** 24-hour `HH:MM`, always after [opens]; overnight intervals are not read. */
  closes: string;
}

/**
 * A weekday missing from [byDay] is a known closure only when the reading is COMPLETE; a PARTIAL reading never
 * claims a closure, and an UNKNOWN reading carries no days.
 */
export interface OpeningHoursReading {
  completeness: OpeningHoursCompleteness;
  byDay: Partial<Record<OpeningHoursWeekday, DailyOpening>>;
}

interface MinuteRange {
  opensAt: number;
  closesAt: number;
}

type Meridiem = "AM" | "PM";

const ALL_DAYS_TOKEN = "alldays";
const KOREAN_DAY_CHARS = "월화수목금토일";
const ENGLISH_DAY =
  "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun)s?";
const ENGLISH_DAY_LIST = `(?:\\b${ENGLISH_DAY}\\b\\.?\\s*(?:,|&|and|-|/)?\\s*)+`;
const KOREAN_DAY_LIST = "(?:[월화수목금토일](?:요일)?\\s*(?:,|및|/|-)?\\s*)+";
/** A day list that stays on its line, so `휴관일: 월, 화` cannot swallow the next line's hours. */
const KOREAN_DAY_LIST_INLINE = "(?:[월화수목금토일](?:요일)?[ \\t]*(?:,|및|/|-)?[ \\t]*)+";
const CLOSED_WORDS = "휴관일|휴무일|정기휴일|정기휴무|휴관|휴무|쉽니다";
const DASH_VARIANTS = /[–—−~〜∼]/g;
const TIME_TO = /(\d|am|pm)\s+to\s+(\d)/g;
const SEGMENT_SEPARATORS = /[·•|;]/g;
const HOLIDAY_PHRASES = /(?:(?:,|and|&)\s*)?(?:national|public)\s+holidays?|공휴일/g;
const EVERY_DAY = /\b(?:every\s*day|everyday|daily)\b|매일|연중무휴/g;
const WEEKDAYS_WORD = /\bweekdays?\b|평일/g;
const WEEKEND_WORD = /\bweekends?\b|주말/g;
const DATED_NOTE = /\b\d{1,2}\s*\/\s*\d{1,2}\b|\d{1,2}\s*월\s*\d{1,2}\s*일/;
const PARENTHETICAL = /[(（]([^)）\n]*)[)）]?/g;
const CLOSED_MARKER = new RegExp(`closed|${CLOSED_WORDS}`);
const ENGLISH_CLOSED_CLAUSE = new RegExp(`closed(?:\\s+(?:on|every))?\\s*:?\\s*(${ENGLISH_DAY_LIST})`, "g");
const KOREAN_CLOSED_CLAUSE = new RegExp(`(${KOREAN_DAY_LIST})(?:은|는)?\\s*(?:${CLOSED_WORDS})`, "g");
const KOREAN_CLOSED_DAYS_AFTER = new RegExp(
  `(?:${CLOSED_WORDS})[ \\t]*[:：]?[ \\t]*(${KOREAN_DAY_LIST_INLINE})`,
  "g",
);
const OTHERWISE_CLOSED = new RegExp(
  `(?:그\\s*외|이외|나머지)(?:\\s*요일)?(?:은|는|에는)?\\s*(?:${CLOSED_WORDS})`,
  "g",
);
const BARE_CLOSED_WORD = new RegExp(`closed(?:\\s+on)?|${CLOSED_WORDS}`, "g");
const CLOCK = "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?";
const TIME_RANGE = new RegExp(`${CLOCK}\\s*-\\s*${CLOCK}`);
const TIME_RANGE_ALL = new RegExp(`${CLOCK}\\s*-\\s*${CLOCK}`, "g");
const DAY_TOKEN = new RegExp(`\\b${ENGLISH_DAY}\\b|[월화수목금토일](?:요일)?`, "g");
const DAY_GROUP_START = new RegExp(`^(?:${ENGLISH_DAY}\\b|[월화수목금토일]|${ALL_DAYS_TOKEN})`);
const MINUTES_PER_HOUR = 60;

/** Reads a venue's free-text hours. Total and deterministic: any input yields a value and never throws. */
export function parseOpeningHours(text: string | null | undefined): OpeningHoursReading {
  const source = typeof text === "string" ? text.trim() : "";
  if (source.length === 0) return unknownReading();
  try {
    return read(source);
  } catch {
    return unknownReading();
  }
}

/**
 * The `hours_weekly` value `admin_confirm_exhibition_hours` accepts: the seven contract keys in week order, a
 * weekday the reading has no hours for as `null` (closed). Staff review every day before it is sent, because a
 * PARTIAL or UNKNOWN reading does not prove a closure.
 */
export function toHoursWeekly(reading: OpeningHoursReading): HoursWeekly {
  const weekly = {} as Record<HoursWeeklyDay, DailyOpening | null>;
  HOURS_WEEKLY_DAYS.forEach((day, index) => {
    const opening = reading.byDay[OPENING_HOURS_WEEKDAYS[index]];
    weekly[day] = opening ? { opens: opening.opens, closes: opening.closes } : null;
  });
  return weekly;
}

function unknownReading(): OpeningHoursReading {
  return { completeness: "UNKNOWN", byDay: {} };
}

function read(source: string): OpeningHoursReading {
  const closedDays = new Set<OpeningHoursWeekday>();
  const byDay = new Map<OpeningHoursWeekday, DailyOpening>();
  const daylessTimes: DailyOpening[] = [];
  let unreadable = false;
  const addClosed = (days: OpeningHoursWeekday[]) => days.forEach((day) => closedDays.add(day));

  let body = source
    .toLowerCase()
    .replace(DASH_VARIANTS, "-")
    .replace(TIME_TO, "$1 - $2")
    .replace(SEGMENT_SEPARATORS, "\n")
    .replace(HOLIDAY_PHRASES, " ")
    .replace(EVERY_DAY, ` ${ALL_DAYS_TOKEN} `)
    .replace(WEEKDAYS_WORD, " 월-금 ")
    .replace(WEEKEND_WORD, " 토-일 ");
  body = body
    .split("\n")
    .filter((line) => !DATED_NOTE.test(line))
    .join("\n");
  // Parenthetical notes are ignored, except that a closed-day marker inside one still applies.
  body = body.replace(PARENTHETICAL, (_match, note: string) => {
    if (CLOSED_MARKER.test(note)) addClosed(readDays(note));
    return " ";
  });
  body = body
    .replace(ENGLISH_CLOSED_CLAUSE, (_match, days: string) => {
      addClosed(readDays(days));
      return " ";
    })
    .replace(KOREAN_CLOSED_CLAUSE, (_match, days: string) => {
      addClosed(readDays(days));
      return " ";
    })
    .replace(KOREAN_CLOSED_DAYS_AFTER, (_match, days: string) => {
      addClosed(readDays(days));
      return " ";
    })
    .replace(OTHERWISE_CLOSED, " ")
    .replace(BARE_CLOSED_WORD, " ");

  let pending: DailyOpening | null = null;
  for (const rawSegment of body.split("\n").flatMap(splitDayGroups)) {
    const segment = trimChars(rawSegment.trim(), ",.:; ");
    if (segment.length === 0) continue;
    const timeMatches = [...segment.matchAll(TIME_RANGE_ALL)];
    const times = timeMatches.map(readTimeRange);
    if (times.some((time) => time === null)) {
      unreadable = true;
      continue;
    }
    const time = span(times as MinuteRange[]);
    if (timeMatches.length > 1) unreadable = true;
    const days = readDays(withoutRanges(segment, timeMatches));
    if (time && days.length > 0) {
      if (pending) daylessTimes.push(pending);
      pending = null;
      days.forEach((day) => byDay.set(day, time));
    } else if (time) {
      if (pending) daylessTimes.push(pending);
      pending = time;
    } else if (days.length > 0) {
      if (pending) {
        const paired: DailyOpening = pending;
        days.forEach((day) => byDay.set(day, paired));
        pending = null;
      } else {
        unreadable = true;
      }
    } else {
      unreadable = true;
    }
  }
  if (pending) daylessTimes.push(pending);

  let completeness: OpeningHoursCompleteness = unreadable ? "PARTIAL" : "COMPLETE";
  if (byDay.size === 0) {
    if (daylessTimes.length !== 1) return unknownReading();
    OPENING_HOURS_WEEKDAYS.forEach((day) => byDay.set(day, daylessTimes[0]));
    if (closedDays.size === 0) completeness = "PARTIAL";
  } else if (daylessTimes.length > 0) {
    completeness = "PARTIAL";
  }
  closedDays.forEach((day) => byDay.delete(day));
  if (byDay.size === 0) return unknownReading();
  const ordered: Partial<Record<OpeningHoursWeekday, DailyOpening>> = {};
  for (const day of OPENING_HOURS_WEEKDAYS) {
    const opening = byDay.get(day);
    if (opening) ordered[day] = { ...opening };
  }
  return { completeness, byDay: ordered };
}

/**
 * A comma starts a new group only when hours precede it and a day follows it, so `Mon-Fri 10-18, Sat 11-17` reads
 * as two groups while `Mon, Wed, Fri 10-18` and `10:00-12:00, 13:00-18:00` each stay one.
 */
function splitDayGroups(line: string): string[] {
  const groups: string[] = [];
  let start = 0;
  for (let index = 0; index < line.length; index += 1) {
    if (line[index] !== ",") continue;
    const left = line.slice(start, index);
    const right = line.slice(index + 1).trimStart();
    if (TIME_RANGE.test(left) && DAY_GROUP_START.test(right)) {
      groups.push(left);
      start = index + 1;
    }
  }
  groups.push(line.slice(start));
  return groups;
}

function withoutRanges(segment: string, matches: RegExpExecArray[] | RegExpMatchArray[]): string {
  let text = segment;
  for (let index = matches.length - 1; index >= 0; index -= 1) {
    const match = matches[index];
    const start = match.index ?? 0;
    text = text.slice(0, start) + text.slice(start + match[0].length);
  }
  return text;
}

/** Several ranges on the same days read as one span from the first opening to the last closing. */
function span(times: MinuteRange[]): DailyOpening | null {
  if (times.length === 0) return null;
  let opens = times[0].opensAt;
  let closes = times[0].closesAt;
  for (const time of times) {
    opens = Math.min(opens, time.opensAt);
    closes = Math.max(closes, time.closesAt);
  }
  return { opens: clock(opens), closes: clock(closes) };
}

function readTimeRange(match: RegExpMatchArray): MinuteRange | null {
  const startHour = Number(match[1]);
  const startMinute = Number(match[2] || "0");
  const startMeridiem = toMeridiem(match[3]);
  const endHour = Number(match[4]);
  const endMinute = Number(match[5] || "0");
  const endMeridiem = toMeridiem(match[6]);
  if (startMinute > 59 || endMinute > 59) return null;

  let opens: number | null;
  let closes: number | null;
  if (!startMeridiem && !endMeridiem) {
    opens = hour24(startHour);
    closes = hour24(endHour);
  } else if (startMeridiem && endMeridiem) {
    opens = hour12(startHour, startMeridiem);
    closes = hour12(endHour, endMeridiem);
  } else if (!startMeridiem && endMeridiem) {
    const end = hour12(endHour, endMeridiem);
    if (end === null) return null;
    closes = end;
    opens = inferMissingMeridiem(startHour, endMeridiem, (hour) => hour < end);
  } else {
    const start = hour12(startHour, startMeridiem as Meridiem);
    if (start === null) return null;
    opens = start;
    closes = inferMissingMeridiem(endHour, startMeridiem as Meridiem, (hour) => hour > start);
  }
  if (opens === null || closes === null) return null;
  const opensAt = opens * MINUTES_PER_HOUR + startMinute;
  const closesAt = closes * MINUTES_PER_HOUR + endMinute;
  if (closesAt <= opensAt) return null;
  return { opensAt, closesAt };
}

/** A bare hour next to a 12-hour time takes the same meridiem unless only the other one is valid. */
function inferMissingMeridiem(
  hour: number,
  sibling: Meridiem,
  valid: (hour: number) => boolean,
): number | null {
  if (hour > 12) {
    const value = hour24(hour);
    return value !== null && valid(value) ? value : null;
  }
  const same = hour12(hour, sibling);
  if (same !== null && valid(same)) return same;
  const other = hour12(hour, sibling === "AM" ? "PM" : "AM");
  return other !== null && valid(other) ? other : null;
}

function readDays(text: string): OpeningHoursWeekday[] {
  if (text.includes(ALL_DAYS_TOKEN)) return [...OPENING_HOURS_WEEKDAYS];
  const tokens = [...text.matchAll(DAY_TOKEN)].filter((match) => isStandaloneDay(text, match));
  const days: OpeningHoursWeekday[] = [];
  let index = 0;
  while (index < tokens.length) {
    const current = tokens[index];
    const day = toWeekday(current[0]);
    if (!day) {
      index += 1;
      continue;
    }
    const next = tokens[index + 1];
    const between = next
      ? text.slice((current.index ?? 0) + current[0].length, next.index ?? 0).trim()
      : null;
    const nextDay = next ? toWeekday(next[0]) : null;
    if (between === "-" && nextDay) {
      days.push(...daysFrom(day, nextDay));
      index += 2;
    } else {
      days.push(day);
      index += 1;
    }
  }
  return [...new Set(days)];
}

/**
 * A single Korean day character counts only on its own: `일` inside `평일`, `휴관일` or `일부` is not Sunday.
 * Neighbouring day characters are fine (`토일`), and `월요일` is matched whole.
 */
function isStandaloneDay(text: string, match: RegExpMatchArray): boolean {
  if (match[0].length !== 1) return true;
  const position = match.index ?? 0;
  return !isNonDayHangul(text[position - 1]) && !isNonDayHangul(text[position + 1]);
}

function isNonDayHangul(char: string | undefined): boolean {
  if (char === undefined) return false;
  const code = char.charCodeAt(0);
  return code >= 0xac00 && code <= 0xd7a3 && !KOREAN_DAY_CHARS.includes(char);
}

function daysFrom(from: OpeningHoursWeekday, to: OpeningHoursWeekday): OpeningHoursWeekday[] {
  const start = OPENING_HOURS_WEEKDAYS.indexOf(from);
  const end = OPENING_HOURS_WEEKDAYS.indexOf(to);
  return start <= end
    ? OPENING_HOURS_WEEKDAYS.slice(start, end + 1)
    : OPENING_HOURS_WEEKDAYS.slice(start).concat(OPENING_HOURS_WEEKDAYS.slice(0, end + 1));
}

function toWeekday(token: string): OpeningHoursWeekday | null {
  if (token.startsWith("mon") || token.startsWith("월")) return "MON";
  if (token.startsWith("tue") || token.startsWith("화")) return "TUE";
  if (token.startsWith("wed") || token.startsWith("수")) return "WED";
  if (token.startsWith("thu") || token.startsWith("목")) return "THU";
  if (token.startsWith("fri") || token.startsWith("금")) return "FRI";
  if (token.startsWith("sat") || token.startsWith("토")) return "SAT";
  if (token.startsWith("sun") || token.startsWith("일")) return "SUN";
  return null;
}

function toMeridiem(text: string | undefined): Meridiem | null {
  const value = (text || "").replace(/\./g, "");
  if (value === "am") return "AM";
  if (value === "pm") return "PM";
  return null;
}

function hour24(hour: number): number | null {
  return hour >= 0 && hour <= 23 ? hour : null;
}

function hour12(hour: number, meridiem: Meridiem): number | null {
  if (hour < 1 || hour > 12) return null;
  const base = hour % 12;
  return meridiem === "PM" ? base + 12 : base;
}

function clock(minutes: number): string {
  const hours = String(Math.floor(minutes / MINUTES_PER_HOUR)).padStart(2, "0");
  return `${hours}:${String(minutes % MINUTES_PER_HOUR).padStart(2, "0")}`;
}

function trimChars(text: string, chars: string): string {
  let start = 0;
  let end = text.length;
  while (start < end && chars.includes(text[start])) start += 1;
  while (end > start && chars.includes(text[end - 1])) end -= 1;
  return text.slice(start, end);
}
