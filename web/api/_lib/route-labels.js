"use strict";

// Stop status labels shared with the app's composer (spec 089, DR-D8). The same table is checked
// against statusLabels in specs/089-personal-routes/contracts/opening-hours-parity.json by
// tests/route-hours.test.js and by the composeApp host test, so the app and the page never word a
// status differently. Placeholders: {arrival}, {closes} (HH:MM), {minutes}, {date} (M.D).

const STATUS_LABELS = Object.freeze({
  ClosedOnPlannedDay: Object.freeze({ ko: "! 휴관일", en: "! CLOSED THAT DAY" }),
  ArrivesAfterClose: Object.freeze({ ko: "! {arrival} 도착 · {closes} 마감", en: "! ARRIVES {arrival} · CLOSES {closes}" }),
  VisitCutShort: Object.freeze({ ko: "! 관람 {minutes}분밖에 없어요", en: "! ONLY {minutes} MIN TO VISIT" }),
  NotYetOpen: Object.freeze({ ko: "! {date} 개막", en: "! OPENS {date}" }),
  Ended: Object.freeze({ ko: "! 종료된 전시", en: "! EXHIBITION ENDED" }),
  Unavailable: Object.freeze({ ko: "! 더 이상 볼 수 없는 전시", en: "! NO LONGER LISTED" }),
  HoursUnknown: Object.freeze({ ko: "운영 시간 미확인", en: "HOURS UNCONFIRMED" }),
});

/** Statuses that make a stop unvisitable that day; "운영 시간 미확인" is informational. */
const BLOCKING_STATUSES = Object.freeze(new Set(["ClosedOnPlannedDay", "ArrivesAfterClose", "VisitCutShort", "NotYetOpen", "Ended", "Unavailable"]));

/** The label for `kind` in `lang` ("ko" or "en") with its placeholders filled from `values`. */
function statusLabel(kind, lang, values = {}) {
  const template = STATUS_LABELS[kind][lang === "en" ? "en" : "ko"];
  return template.replace(/\{(\w+)\}/g, (_match, name) => (name in values ? String(values[name]) : `{${name}}`));
}

module.exports = { STATUS_LABELS, BLOCKING_STATUSES, statusLabel };
