"use strict";

// JavaScript port of shared/src/commonMain/kotlin/com/gallr/shared/hours/OpeningHoursParser.kt (spec 089, E-D10).
// Keep the two in step: tests/route-hours.test.js and the Kotlin host test check both against
// specs/089-personal-routes/contracts/opening-hours-parity.json.
//
// Reads a venue's free-text hours into { completeness, byDay } where byDay maps "MON".."SUN" to
// { opens: "HH:MM", closes: "HH:MM" }. Total and deterministic: any input yields a value.

const WEEKDAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"];
const UNKNOWN = Object.freeze({ completeness: "UNKNOWN", byDay: Object.freeze({}) });

const ALL_DAYS_TOKEN = "alldays";
const KOREAN_DAY_CHARS = "월화수목금토일";
const ENGLISH_DAY =
  "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun)s?";
const ENGLISH_DAY_LIST = `(?:\\b${ENGLISH_DAY}\\b\\.?\\s*(?:,|&|and|-|/)?\\s*)+`;
const KOREAN_DAY_LIST = "(?:[월화수목금토일](?:요일)?\\s*(?:,|및|/|-)?\\s*)+";
// A day list that stays on its line, so `휴관일: 월, 화` cannot swallow the next line's hours.
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
const KOREAN_CLOSED_DAYS_AFTER = new RegExp(`(?:${CLOSED_WORDS})[ \\t]*[:：]?[ \\t]*(${KOREAN_DAY_LIST_INLINE})`, "g");
const OTHERWISE_CLOSED = new RegExp(`(?:그\\s*외|이외|나머지)(?:\\s*요일)?(?:은|는|에는)?\\s*(?:${CLOSED_WORDS})`, "g");
const BARE_CLOSED_WORD = new RegExp(`closed(?:\\s+on)?|${CLOSED_WORDS}`, "g");
const CLOCK = "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?";
const TIME_RANGE = new RegExp(`${CLOCK}\\s*-\\s*${CLOCK}`);
const TIME_RANGE_ALL = new RegExp(`${CLOCK}\\s*-\\s*${CLOCK}`, "g");
const DAY_TOKEN = new RegExp(`\\b${ENGLISH_DAY}\\b|[월화수목금토일](?:요일)?`, "g");
const DAY_GROUP_START = new RegExp(`^(?:${ENGLISH_DAY}\\b|[월화수목금토일]|${ALL_DAYS_TOKEN})`);

function parseOpeningHours(text) {
  const source = typeof text === "string" ? text.trim() : "";
  if (source.length === 0) return UNKNOWN;
  try {
    return read(source);
  } catch {
    return UNKNOWN;
  }
}

function read(source) {
  const closedDays = new Set();
  const byDay = new Map();
  const daylessTimes = [];
  let unreadable = false;

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
  body = body.replace(PARENTHETICAL, (_match, note) => {
    if (CLOSED_MARKER.test(note)) readDays(note).forEach((day) => closedDays.add(day));
    return " ";
  });
  body = body
    .replace(ENGLISH_CLOSED_CLAUSE, (_match, days) => {
      readDays(days).forEach((day) => closedDays.add(day));
      return " ";
    })
    .replace(KOREAN_CLOSED_CLAUSE, (_match, days) => {
      readDays(days).forEach((day) => closedDays.add(day));
      return " ";
    })
    .replace(KOREAN_CLOSED_DAYS_AFTER, (_match, days) => {
      readDays(days).forEach((day) => closedDays.add(day));
      return " ";
    })
    .replace(OTHERWISE_CLOSED, " ")
    .replace(BARE_CLOSED_WORD, " ");

  let pending = null;
  for (const rawSegment of body.split("\n").flatMap(splitDayGroups)) {
    const segment = trimChars(rawSegment.trim(), ",.:; ");
    if (segment.length === 0) continue;
    const timeMatches = [...segment.matchAll(TIME_RANGE_ALL)];
    const times = timeMatches.map(readTimeRange);
    if (times.some((time) => time === null)) {
      unreadable = true;
      continue;
    }
    const time = span(times);
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
        const paired = pending;
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

  let completeness = unreadable ? "PARTIAL" : "COMPLETE";
  if (byDay.size === 0) {
    if (daylessTimes.length !== 1) return UNKNOWN;
    WEEKDAYS.forEach((day) => byDay.set(day, daylessTimes[0]));
    if (closedDays.size === 0) completeness = "PARTIAL";
  } else if (daylessTimes.length > 0) {
    completeness = "PARTIAL";
  }
  closedDays.forEach((day) => byDay.delete(day));
  if (byDay.size === 0) return UNKNOWN;
  const ordered = {};
  for (const day of WEEKDAYS) if (byDay.has(day)) ordered[day] = byDay.get(day);
  return { completeness, byDay: ordered };
}

// A comma starts a new group only when hours precede it and a day follows it, so "Mon-Fri 10-18, Sat 11-17"
// reads as two groups while "Mon, Wed, Fri 10-18" and "10:00-12:00, 13:00-18:00" each stay one.
function splitDayGroups(line) {
  const groups = [];
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

function withoutRanges(segment, matches) {
  let text = segment;
  for (let index = matches.length - 1; index >= 0; index -= 1) {
    const match = matches[index];
    text = text.slice(0, match.index) + text.slice(match.index + match[0].length);
  }
  return text;
}

// Several ranges on the same days read as one span from the first opening to the last closing.
function span(times) {
  if (times.length === 0) return null;
  let opens = times[0].opensAt;
  let closes = times[0].closesAt;
  for (const time of times) {
    opens = Math.min(opens, time.opensAt);
    closes = Math.max(closes, time.closesAt);
  }
  return { opens: clock(opens), closes: clock(closes) };
}

function readTimeRange(match) {
  const startHour = Number(match[1]);
  const startMinute = Number(match[2] || "0");
  const startMeridiem = toMeridiem(match[3]);
  const endHour = Number(match[4]);
  const endMinute = Number(match[5] || "0");
  const endMeridiem = toMeridiem(match[6]);
  if (startMinute > 59 || endMinute > 59) return null;

  let opens;
  let closes;
  if (!startMeridiem && !endMeridiem) {
    opens = hour24(startHour);
    closes = hour24(endHour);
  } else if (startMeridiem && endMeridiem) {
    opens = hour12(startHour, startMeridiem);
    closes = hour12(endHour, endMeridiem);
  } else if (!startMeridiem) {
    closes = hour12(endHour, endMeridiem);
    if (closes === null) return null;
    opens = inferMissingMeridiem(startHour, endMeridiem, (hour) => hour < closes);
  } else {
    opens = hour12(startHour, startMeridiem);
    if (opens === null) return null;
    closes = inferMissingMeridiem(endHour, startMeridiem, (hour) => hour > opens);
  }
  if (opens === null || closes === null) return null;
  const opensAt = opens * 60 + startMinute;
  const closesAt = closes * 60 + endMinute;
  if (closesAt <= opensAt) return null;
  return { opensAt, closesAt };
}

/** A bare hour next to a 12-hour time takes the same meridiem unless only the other one is valid. */
function inferMissingMeridiem(hour, sibling, valid) {
  if (hour > 12) {
    const value = hour24(hour);
    return value !== null && valid(value) ? value : null;
  }
  const same = hour12(hour, sibling);
  if (same !== null && valid(same)) return same;
  const other = hour12(hour, sibling === "AM" ? "PM" : "AM");
  return other !== null && valid(other) ? other : null;
}

function readDays(text) {
  if (text.includes(ALL_DAYS_TOKEN)) return WEEKDAYS.slice();
  const tokens = [...text.matchAll(DAY_TOKEN)].filter((match) => isStandaloneDay(text, match));
  const days = [];
  let index = 0;
  while (index < tokens.length) {
    const current = tokens[index];
    const day = toWeekday(current[0]);
    if (!day) {
      index += 1;
      continue;
    }
    const next = tokens[index + 1];
    const between = next ? text.slice(current.index + current[0].length, next.index).trim() : null;
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

// A single Korean day character counts only on its own: "일" inside "평일", "휴관일" or "일부" is not Sunday.
// Neighbouring day characters are fine ("토일"), and "월요일" is matched whole.
function isStandaloneDay(text, match) {
  if (match[0].length !== 1) return true;
  const before = text[match.index - 1];
  const after = text[match.index + 1];
  return !isNonDayHangul(before) && !isNonDayHangul(after);
}

function isNonDayHangul(char) {
  if (char === undefined) return false;
  const code = char.charCodeAt(0);
  return code >= 0xac00 && code <= 0xd7a3 && !KOREAN_DAY_CHARS.includes(char);
}

function daysFrom(from, to) {
  const start = WEEKDAYS.indexOf(from);
  const end = WEEKDAYS.indexOf(to);
  return start <= end
    ? WEEKDAYS.slice(start, end + 1)
    : WEEKDAYS.slice(start).concat(WEEKDAYS.slice(0, end + 1));
}

function toWeekday(token) {
  if (token.startsWith("mon") || token.startsWith("월")) return "MON";
  if (token.startsWith("tue") || token.startsWith("화")) return "TUE";
  if (token.startsWith("wed") || token.startsWith("수")) return "WED";
  if (token.startsWith("thu") || token.startsWith("목")) return "THU";
  if (token.startsWith("fri") || token.startsWith("금")) return "FRI";
  if (token.startsWith("sat") || token.startsWith("토")) return "SAT";
  if (token.startsWith("sun") || token.startsWith("일")) return "SUN";
  return null;
}

function toMeridiem(text) {
  const value = (text || "").replace(/\./g, "");
  if (value === "am") return "AM";
  if (value === "pm") return "PM";
  return null;
}

function hour24(hour) {
  return hour >= 0 && hour <= 23 ? hour : null;
}

function hour12(hour, meridiem) {
  if (hour < 1 || hour > 12) return null;
  const base = hour % 12;
  return meridiem === "PM" ? base + 12 : base;
}

function clock(minutes) {
  return `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
}

function trimChars(text, chars) {
  let start = 0;
  let end = text.length;
  while (start < end && chars.includes(text[start])) start += 1;
  while (end > start && chars.includes(text[end - 1])) end -= 1;
  return text.slice(start, end);
}

module.exports = { parseOpeningHours, WEEKDAYS };
