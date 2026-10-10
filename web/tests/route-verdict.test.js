// Spec 089 RO1 and DR-D14: the shared page's "does this work today" line and its primary action.
// Run: node tests/route-verdict.test.js (also runs as part of `npm test`)

const assert = require("assert").strict;
const { stopStatusOn, routeVerdict, seoulDate } = require("../api/_lib/route-verdict.js");

// 2026-10-08 is a Thursday.
const THURSDAY = "2026-10-08";
const TUE_SAT = "10am - 6pm\nTuesday - Saturday";
const WEEKDAYS_ONLY = "10:00-18:00 Monday-Friday";
const FRI_SUN = "11:00-19:00 Friday-Sunday";

function stop(overrides = {}) {
  return { listed: true, openingDate: "2026-09-01", closingDate: "2026-12-31", hours: TUE_SAT, ...overrides };
}

// --- per-stop status on a day ---
assert.equal(stopStatusOn(stop(), THURSDAY).kind, "Open");
assert.equal(stopStatusOn(stop({ hours: FRI_SUN }), THURSDAY).kind, "ClosedOnPlannedDay");
assert.equal(stopStatusOn(stop({ listed: false }), THURSDAY).kind, "Unavailable");
assert.equal(stopStatusOn(stop({ closingDate: "2026-10-07" }), THURSDAY).kind, "Ended");
const notYet = stopStatusOn(stop({ openingDate: "2026-10-24" }), THURSDAY);
assert.equal(notYet.kind, "NotYetOpen");
assert.equal(notYet.label.ko, "! 10.24 개막");
assert.equal(stopStatusOn(stop({ hours: null }), THURSDAY).kind, "HoursUnknown");
// A partial reading never claims a closure.
assert.equal(stopStatusOn(stop({ hours: "10am - 6pm" }), THURSDAY).kind, "Open");
assert.equal(stopStatusOn(stop({ hours: FRI_SUN }), THURSDAY).label.en, "! CLOSED THAT DAY");
assert.equal(stopStatusOn(stop(), THURSDAY).label, null);
assert.equal(stopStatusOn(stop({ hours: null }), THURSDAY).blocking, false);

// --- verdict line ---
{
  const verdict = routeVerdict([stop(), stop(), stop()], THURSDAY);
  assert.equal(verdict.line.ko, "오늘(목) · 모두 열림");
  assert.equal(verdict.line.en, "TODAY (THU) · ALL OPEN");
  assert.equal(verdict.primaryIndex, 0);
}
{
  // One closed today; Saturday has all three open and is the first better day.
  const verdict = routeVerdict([stop({ hours: FRI_SUN }), stop(), stop()], THURSDAY);
  assert.equal(verdict.line.ko, "오늘(목) · 3곳 중 2곳 열림 · 1곳 휴관 · 금요일엔 3곳 중 3곳 열림");
  assert.equal(verdict.line.en, "TODAY (THU) · 2 OF 3 OPEN · 1 CLOSED · FRIDAY: 3 OF 3 OPEN");
  assert.equal(verdict.primaryIndex, 1, "the primary action skips the closed first stop");
}
{
  // One unknown: never "모두 열림", and no day can beat today.
  const verdict = routeVerdict([stop(), stop({ hours: null })], THURSDAY);
  assert.equal(verdict.line.ko, "오늘(목) · 2곳 중 1곳 열림 · 1곳 시간 미확인");
  assert.equal(verdict.line.en, "TODAY (THU) · 1 OF 2 OPEN · 1 WITH UNCONFIRMED HOURS");
}
{
  // Nothing has opened yet: counts, never "모두 열림"; the first stop stays the primary target.
  const later = { openingDate: "2026-11-01" };
  const verdict = routeVerdict([stop(later), stop(later), stop(later)], THURSDAY);
  assert.equal(verdict.line.ko, "오늘(목) · 3곳 중 0곳 열림 · 3곳 개막 전");
  assert.equal(verdict.primaryIndex, 0);
}
{
  const ended = { closingDate: "2026-10-01" };
  const verdict = routeVerdict([stop(ended), stop(ended)], THURSDAY);
  assert.equal(verdict.line.ko, "오늘(목) · 2곳 중 0곳 열림 · 2곳 종료");
  assert.equal(verdict.line.en, "TODAY (THU) · 0 OF 2 OPEN · 2 ENDED");
}
{
  // Unavailable stops are named; a better day must have strictly more open stops.
  const verdict = routeVerdict([stop(), stop({ listed: false })], THURSDAY);
  assert.equal(verdict.line.ko, "오늘(목) · 2곳 중 1곳 열림 · 1곳 볼 수 없음");
}
{
  // Weekday-only and weekend-only stops never all open together: no day beats today's one open stop.
  const verdict = routeVerdict([stop({ hours: WEEKDAYS_ONLY }), stop({ hours: "11:00-19:00 Saturday-Sunday" })], THURSDAY);
  assert.equal(verdict.line.ko, "오늘(목) · 2곳 중 1곳 열림 · 1곳 휴관");
}
{
  // A better day further away is still found within the week, and labelled by weekday.
  const verdict = routeVerdict([stop({ hours: "11:00-19:00 Sunday" }), stop({ hours: "10:00-18:00 Sunday-Monday" })], THURSDAY);
  assert.equal(verdict.line.ko, "오늘(목) · 2곳 중 0곳 열림 · 2곳 휴관 · 일요일엔 2곳 중 2곳 열림");
}
{
  // The suggestion never passes an exhibition's end: after Saturday the only open day has ended.
  const verdict = routeVerdict([stop({ hours: "11:00-19:00 Sunday", closingDate: "2026-10-10" })], THURSDAY);
  assert.equal(verdict.line.ko, "오늘(목) · 1곳 중 0곳 열림 · 1곳 휴관");
}

// --- Seoul calendar date regardless of the server's zone ---
assert.equal(seoulDate(new Date("2026-10-08T15:30:00Z")), "2026-10-09");
assert.equal(seoulDate(new Date("2026-10-08T14:59:00Z")), "2026-10-08");

console.log("route-verdict: ok");
