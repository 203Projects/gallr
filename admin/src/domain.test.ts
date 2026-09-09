import { describe, expect, it } from "vitest";
import {
  exhibitionTemporalStatus,
  hasCoverImage,
  matchesExhibitionFilters,
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

  it("keeps a map pin while a delimiter or detail is typed after the building number", () => {
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남대로 28",
        "서울 용산구 한남대로 28,",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남대로 28,",
        "서울 용산구 한남대로 28, 3층",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남대로 28",
        "서울 용산구 한남대로 28(한남동)",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남동 1-1번지",
        "서울 용산구 한남동 1-1번지 3층",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남대로28",
        "서울 용산구 한남대로28, 3층",
      ),
    ).toBe(true);
  });

  it("keeps a map pin while 번지 is typed one character at a time after a parcel number", () => {
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 강남구 역삼동 12-3",
        "서울 강남구 역삼동 12-3ㅂ",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 강남구 역삼동 12-3ㅂ",
        "서울 강남구 역삼동 12-3번",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 강남구 역삼동 12-3번",
        "서울 강남구 역삼동 12-3번지",
      ),
    ).toBe(true);
  });

  it("keeps a map pin for apartment and legacy-road details that contain their own numbers", () => {
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 강남구 역삼동 12-3",
        "서울 강남구 역삼동 12-3 101동 1",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 강남구 역삼동 12-3 101동 1",
        "서울 강남구 역삼동 12-3 101동 1001호",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 종로구 삼청로 30",
        "서울 종로구 삼청로 30 (구 삼청로 5",
      ),
    ).toBe(true);
  });

  it("treats numbered road names and lettered parcel districts as part of the street", () => {
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 강남구 테헤란로4길 12",
        "서울 강남구 테헤란로4길 12 3층",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 강남구 테헤란로4길 12",
        "서울 강남구 테헤란로4길 13",
      ),
    ).toBe(false);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "경기 성남시 중앙로 123번길 45",
        "경기 성남시 중앙로 123번길 45 2층",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "경기 성남시 중앙로 123번길 45",
        "경기 성남시 중앙로 123번길 46",
      ),
    ).toBe(false);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 중구 을지로3가 15",
        "서울 중구 을지로3가 15, 2층",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 중구 을지로3가 15",
        "서울 중구 을지로3가 16",
      ),
    ).toBe(false);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "경기 양평군 서종면 문호리 123",
        "경기 양평군 서종면 문호리 123 별관",
      ),
    ).toBe(true);
  });

  it("ignores spacing and full-width punctuation differences around the building number", () => {
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남대로28",
        "서울 용산구 한남대로 28",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 강남구 테헤란로 12",
        "서울 강남구 테헤란로 12（역삼동）",
      ),
    ).toBe(true);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 강남구 테헤란로 １２",
        "서울 강남구 테헤란로 12 3층",
      ),
    ).toBe(true);
  });

  it("invalidates a map pin when the building number itself changes", () => {
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남대로 28",
        "서울 용산구 한남대로 281",
      ),
    ).toBe(false);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남대로 28",
        "서울 용산구 한남대로 283층",
      ),
    ).toBe(false);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남대로 28",
        "서울 용산구 한남대로 28-1",
      ),
    ).toBe(false);
    expect(
      shouldPreserveCoordinatesForAddressChange(
        "서울 용산구 한남동 1-1번지",
        "서울 용산구 한남동 1-2번지",
      ),
    ).toBe(false);
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

describe("admin exhibition cover image presence", () => {
  it("treats null and blank cover URLs as missing", () => {
    expect(hasCoverImage({ coverImageUrl: null })).toBe(false);
    expect(hasCoverImage({ coverImageUrl: "" })).toBe(false);
    expect(hasCoverImage({ coverImageUrl: "   " })).toBe(false);
    expect(
      hasCoverImage({ coverImageUrl: "https://images.example.test/cover.webp" }),
    ).toBe(true);
  });
});

describe("admin exhibition list filter matching", () => {
  const today = "2026-08-23";
  const running = {
    ...exhibitionFixtures[0],
    id: "running-draft",
    nameKo: "빛의 복도",
    nameEn: "Corridor of Light",
    venueNameKo: "갤러리 화이트룸",
    venueNameEn: "White Room Gallery",
    openingDate: "2026-08-01",
    closingDate: "2026-09-01",
    status: "Draft" as const,
    isHomepageFeatured: true,
    coverImageUrl: null,
  };
  const endedPublished = {
    ...running,
    id: "ended-published",
    openingDate: "2026-01-01",
    closingDate: "2026-02-01",
    status: "Published" as const,
    isHomepageFeatured: false,
    coverImageUrl: "https://images.example.test/cover.webp",
  };

  it("matches everything when no filter narrows the list", () => {
    const filters = { search: "", status: "All" as const };
    expect(matchesExhibitionFilters(running, filters, today)).toBe(true);
    expect(matchesExhibitionFilters(endedPublished, filters, today)).toBe(true);
  });

  it("applies publish state, date state, placement, and cover filters together", () => {
    const filters = {
      search: "",
      status: "Draft" as const,
      temporalStatus: "running" as const,
      featuredOnly: true,
      missingCoverOnly: true,
    };
    expect(matchesExhibitionFilters(running, filters, today)).toBe(true);
    expect(matchesExhibitionFilters(endedPublished, filters, today)).toBe(false);
    expect(
      matchesExhibitionFilters(
        { ...running, coverImageUrl: "https://images.example.test/new.webp" },
        filters,
        today,
      ),
    ).toBe(false);
  });

  it("searches names, venues, and ids case-insensitively with trimmed input", () => {
    const byVenue = { search: "  white room  ", status: "All" as const };
    expect(matchesExhibitionFilters(running, byVenue, today)).toBe(true);
    expect(
      matchesExhibitionFilters(running, { search: "ended-pub", status: "All" }, today),
    ).toBe(false);
    expect(
      matchesExhibitionFilters(endedPublished, { search: "ENDED-PUB", status: "All" }, today),
    ).toBe(true);
  });
});
