import { describe, expect, it } from "vitest";
import {
  exhibitionTemporalStatus,
  seoulCalendarDate,
  shouldPreserveCoordinatesForAddressChange,
  sortAdminExhibitions,
} from "./domain";
import { exhibitionFixtures } from "./data/fixtures";

describe("Korean exhibition address changes", () => {
  it("keeps a map pin when only a floor or unit detail changes", () => {
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남대로 28",
        "서울 용산구 한남대로 28 3층",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남대로 28 3층",
        "서울 용산구 한남대로 28 4층 401호",
      ),
    ).toBe(true);
  });

  it("invalidates a map pin when the searchable street address changes", () => {
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남대로 28 3층",
        "서울 용산구 이태원로 55 3층",
      ),
    ).toBe(false);
  });
});

describe("admin exhibition temporal status", () => {
  it("classifies running, upcoming, and ended dates against an injected day", () => {
    expect(exhibitionTemporalStatus("2026-08-01", "2026-08-31", "2026-08-11"))
      .toBe("running");
    expect(exhibitionTemporalStatus("2026-08-12", "2026-08-31", "2026-08-11"))
      .toBe("upcoming");
    expect(exhibitionTemporalStatus("2026-07-01", "2026-08-10", "2026-08-11"))
      .toBe("ended");
    expect(exhibitionTemporalStatus("2026-09-01", "2026-08-31", "2026-08-11"))
      .toBe("ended");
  });

  it("uses the Seoul calendar day independently of the browser time zone", () => {
    expect(seoulCalendarDate(new Date("2026-08-11T15:30:00Z"))).toBe("2026-08-12");
  });
});

describe("admin exhibition sorting", () => {
  it("places undated drafts after dated exhibitions for ascending date sorts", () => {
    const records = [
      { ...exhibitionFixtures[0], id: "later", openingDate: "2026-09-01" },
      { ...exhibitionFixtures[0], id: "undated", openingDate: "" },
      { ...exhibitionFixtures[0], id: "earlier", openingDate: "2026-08-01" },
    ];

    expect(sortAdminExhibitions(records, "opening_asc").map(({ id }) => id)).toEqual([
      "earlier",
      "later",
      "undated",
    ]);
  });

  it("reorders records when a selected sort field changes", () => {
    const records = [
      { ...exhibitionFixtures[0], id: "older", publishedAt: "2026-08-01T00:00:00Z" },
      { ...exhibitionFixtures[0], id: "newer", publishedAt: "2026-08-10T00:00:00Z" },
    ];

    expect(sortAdminExhibitions(records, "published_desc").map(({ id }) => id)).toEqual([
      "newer",
      "older",
    ]);
  });
});
