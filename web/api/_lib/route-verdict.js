"use strict";

// The shared route page's day-level view (spec 089 RO1, DR-D3, DR-D14). The page has no visitor origin and no
// schedule, so it judges whole days: a stop is open on a day when its exhibition runs that day and its hours list
// that weekday. A partial reading of the hours never claims a closure.

const { parseOpeningHours, WEEKDAYS } = require("./opening-hours.js");
const { BLOCKING_STATUSES, statusLabel } = require("./route-labels.js");

const MAX_SUGGESTION_DAYS = 7;
const KO_WEEKDAYS = { MON: "월", TUE: "화", WED: "수", THU: "목", FRI: "금", SAT: "토", SUN: "일" };
const EN_WEEKDAYS = {
  MON: "MONDAY",
  TUE: "TUESDAY",
  WED: "WEDNESDAY",
  THU: "THURSDAY",
  FRI: "FRIDAY",
  SAT: "SATURDAY",
  SUN: "SUNDAY",
};

// The order and wording of the non-open counts on the verdict line.
const EXCLUSIONS = [
  { kind: "ClosedOnPlannedDay", ko: "휴관", en: "CLOSED" },
  { kind: "NotYetOpen", ko: "개막 전", en: "NOT YET OPEN" },
  { kind: "Ended", ko: "종료", en: "ENDED" },
  { kind: "Unavailable", ko: "볼 수 없음", en: "NO LONGER LISTED" },
  { kind: "HoursUnknown", ko: "시간 미확인", en: "WITH UNCONFIRMED HOURS" },
];

/**
 * The status of one stop on `date` ("YYYY-MM-DD"). `stop` carries `listed` (still in the catalogue),
 * `openingDate`, `closingDate` and the free-text `hours`.
 */
function stopStatusOn(stop, date) {
  const kind = statusKindOn(stop, date);
  if (kind === "Open") return { kind, blocking: false, label: null };
  const values = kind === "NotYetOpen" ? { date: monthDay(stop.openingDate) } : {};
  return {
    kind,
    blocking: BLOCKING_STATUSES.has(kind),
    label: { ko: statusLabel(kind, "ko", values), en: statusLabel(kind, "en", values) },
  };
}

function statusKindOn(stop, date) {
  if (!stop.listed) return "Unavailable";
  if (date < stop.openingDate) return "NotYetOpen";
  if (date > stop.closingDate) return "Ended";
  const hours = parseOpeningHours(stop.hours);
  if (hours.completeness === "UNKNOWN") return "HoursUnknown";
  if (hours.byDay[weekdayOf(date)]) return "Open";
  return hours.completeness === "COMPLETE" ? "ClosedOnPlannedDay" : "HoursUnknown";
}

/**
 * Today's verdict line in both languages, each stop's status today, and the stop the primary action points at:
 * the first stop open today, else stop 1. "모두 열림" only when every stop is open; otherwise the open count and
 * each kind of exclusion, plus the first day within a week on which more stops are open than today.
 */
function routeVerdict(stops, today) {
  const statuses = stops.map((stop) => stopStatusOn(stop, today));
  const openToday = statuses.filter((status) => status.kind === "Open").length;
  const weekday = weekdayOf(today);
  const todayKo = `오늘(${KO_WEEKDAYS[weekday]})`;
  const todayEn = `TODAY (${weekday})`;
  const firstOpen = statuses.findIndex((status) => status.kind === "Open");
  const primaryIndex = firstOpen >= 0 ? firstOpen : 0;

  if (stops.length > 0 && openToday === stops.length) {
    return { line: { ko: `${todayKo} · 모두 열림`, en: `${todayEn} · ALL OPEN` }, statuses, primaryIndex };
  }

  const ko = [todayKo, `${stops.length}곳 중 ${openToday}곳 열림`];
  const en = [todayEn, `${openToday} OF ${stops.length} OPEN`];
  for (const exclusion of EXCLUSIONS) {
    const count = statuses.filter((status) => status.kind === exclusion.kind).length;
    if (count === 0) continue;
    ko.push(`${count}곳 ${exclusion.ko}`);
    en.push(`${count} ${exclusion.en}`);
  }
  const better = betterDay(stops, today, openToday);
  if (better) {
    const name = WEEKDAYS_FULL_KO[weekdayOf(better.date)];
    ko.push(`${name}엔 ${stops.length}곳 중 ${better.open}곳 열림`);
    en.push(`${EN_WEEKDAYS[weekdayOf(better.date)]}: ${better.open} OF ${stops.length} OPEN`);
  }
  return { line: { ko: ko.join(" · "), en: en.join(" · ") }, statuses, primaryIndex };
}

const WEEKDAYS_FULL_KO = {
  MON: "월요일",
  TUE: "화요일",
  WED: "수요일",
  THU: "목요일",
  FRI: "금요일",
  SAT: "토요일",
  SUN: "일요일",
};

function betterDay(stops, today, openToday) {
  for (let offset = 1; offset <= MAX_SUGGESTION_DAYS; offset += 1) {
    const date = addDays(today, offset);
    const open = stops.filter((stop) => statusKindOn(stop, date) === "Open").length;
    if (open > openToday) return { date, open };
  }
  return null;
}

/** The calendar date in Seoul for `instant`, as "YYYY-MM-DD". */
function seoulDate(instant) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(instant);
  const value = (type) => parts.find((part) => part.type === type).value;
  return `${value("year")}-${value("month")}-${value("day")}`;
}

function weekdayOf(date) {
  // Date.UTC keeps the arithmetic on the calendar date, whatever the server's zone.
  const [year, month, day] = date.split("-").map(Number);
  const sundayFirst = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
  return WEEKDAYS[(sundayFirst + 6) % 7];
}

function addDays(date, days) {
  const [year, month, day] = date.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10);
}

function monthDay(date) {
  const [, month, day] = date.split("-").map(Number);
  return `${month}.${day}`;
}

module.exports = { stopStatusOn, routeVerdict, seoulDate, weekdayOf, addDays };
