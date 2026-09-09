"use strict";

const SEOUL_TIME_ZONE = "Asia/Seoul";

function seoulDateIso(date = new Date()) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: SEOUL_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]));
  return `${values.year}-${values.month}-${values.day}`;
}

function currentYearMonth(dateIso = seoulDateIso()) {
  const match = /^(\d{4})-(0[1-9]|1[0-2])-\d{2}$/.exec(dateIso);
  if (!match) {
    throw new Error("Homepage date must use YYYY-MM-DD.");
  }
  return `${match[1]} / ${match[2]}`;
}

module.exports = { currentYearMonth, SEOUL_TIME_ZONE, seoulDateIso };
