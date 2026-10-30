// Spec 101 T055: Admin's hours reader must give the same readings as the app's Kotlin OpeningHoursParser.
// The expected readings come from the parity file the Kotlin host test regenerates and checks
// (shared/src/androidHostTest/.../OpeningHoursParityHostTest.kt), so a drift on either side fails here.
import {
  HOURS_WEEKLY_DAYS,
  OPENING_HOURS_WEEKDAYS,
  parseOpeningHours,
  toHoursWeekly,
  type OpeningHoursWeekday,
} from "./openingHours";
// A `?raw` import is typed by vite/client, so `tsc -b` in the Admin-only Vercel build (Root Directory admin/) never
// resolves a file outside admin/; vite.config.ts allows only its contracts folder.
import parityText from "../../../specs/089-personal-routes/contracts/opening-hours-parity.json?raw";

interface ParityEntry {
  id: string;
  input: string | null;
  completeness: "COMPLETE" | "PARTIAL" | "UNKNOWN";
  week: Record<OpeningHoursWeekday, [string, string] | null>;
}

const entries = (JSON.parse(parityText) as { hours: ParityEntry[] }).hours;
const golden = entries.filter(({ id }) => id.startsWith("golden-"));

function weekOf(input: string | null): Record<OpeningHoursWeekday, [string, string] | null> {
  const reading = parseOpeningHours(input);
  return Object.fromEntries(
    OPENING_HOURS_WEEKDAYS.map((day) => {
      const opening = reading.byDay[day];
      return [day, opening ? [opening.opens, opening.closes] : null];
    }),
  ) as Record<OpeningHoursWeekday, [string, string] | null>;
}

describe("parseOpeningHours parity with the Kotlin parser", () => {
  it("covers every golden case of the opening-hours grammar", () => {
    const ids = new Set(golden.map(({ id }) => id));
    for (let number = 1; number <= 21; number += 1) {
      expect(ids).toContain(`golden-${String(number).padStart(2, "0")}`);
    }
    expect(ids).toContain("golden-15-null");
  });

  it.each(golden.map((entry) => [entry.id, entry] as const))(
    "%s reads the same as the app",
    (_id, entry) => {
      expect(parseOpeningHours(entry.input).completeness).toBe(entry.completeness);
      expect(weekOf(entry.input)).toEqual(entry.week);
    },
  );

  it("reads every hand-checked catalogue listing the same as the app", () => {
    const catalogue = entries.filter(({ id }) => id.startsWith("catalogue-"));
    expect(catalogue.length).toBeGreaterThan(50);
    for (const entry of catalogue) {
      expect({ id: entry.id, completeness: parseOpeningHours(entry.input).completeness })
        .toEqual({ id: entry.id, completeness: entry.completeness });
      expect({ id: entry.id, week: weekOf(entry.input) }).toEqual({ id: entry.id, week: entry.week });
    }
  });

  it("treats null, blank and unreadable text as unknown without throwing", () => {
    expect(parseOpeningHours(null)).toEqual({ completeness: "UNKNOWN", byDay: {} });
    expect(parseOpeningHours(undefined)).toEqual({ completeness: "UNKNOWN", byDay: {} });
    expect(parseOpeningHours("   ")).toEqual({ completeness: "UNKNOWN", byDay: {} });
    expect(parseOpeningHours("((((")).toEqual({ completeness: "UNKNOWN", byDay: {} });
  });
});

describe("toHoursWeekly", () => {
  it("writes the seven contract keys with closed days as null", () => {
    expect(toHoursWeekly(parseOpeningHours("화-일 10:00-18:00, 월요일 휴관"))).toEqual({
      MONDAY: null,
      TUESDAY: { opens: "10:00", closes: "18:00" },
      WEDNESDAY: { opens: "10:00", closes: "18:00" },
      THURSDAY: { opens: "10:00", closes: "18:00" },
      FRIDAY: { opens: "10:00", closes: "18:00" },
      SATURDAY: { opens: "10:00", closes: "18:00" },
      SUNDAY: { opens: "10:00", closes: "18:00" },
    });
  });

  it("keeps the contract key order and never leaves a key out", () => {
    const weekly = toHoursWeekly(parseOpeningHours("By appointment only"));
    expect(Object.keys(weekly)).toEqual([...HOURS_WEEKLY_DAYS]);
    expect(Object.values(weekly).every((value) => value === null)).toBe(true);
  });

  it("carries half-hour times in 24-hour HH:MM form", () => {
    expect(toHoursWeekly(parseOpeningHours("2pm - 5:30pm\nMonday - Saturday")).SATURDAY)
      .toEqual({ opens: "14:00", closes: "17:30" });
  });
});
