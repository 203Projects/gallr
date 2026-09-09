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

type AddressChangeCase = [previous: string, next: string, preserves: boolean];

const preservesCoordinates = (cases: readonly AddressChangeCase[]) =>
  it.each(cases)("%s -> %s keeps the pin: %s", (previous, next, expected) => {
    expect(shouldPreserveCoordinatesForAddressChange(previous, next)).toBe(expected);
  });

describe("Korean exhibition address changes", () => {
  describe("floor, unit, and building details keep a confirmed map pin", () => {
    preservesCoordinates([
      ["서울 용산구 한남대로 28", "서울 용산구 한남대로 28 3층", true],
      ["서울 용산구 한남대로 28 3층", "서울 용산구 한남대로 28 4층 401호", true],
      ["서울 용산구 한남대로 28", "서울 용산구 한남대로 28,", true],
      ["서울 용산구 한남대로 28,", "서울 용산구 한남대로 28, 3층", true],
      ["서울 용산구 한남대로 28", "서울 용산구 한남대로 28(한남동)", true],
      ["서울 용산구 한남동 1-1번지", "서울 용산구 한남동 1-1번지 3층", true],
      ["서울 강남구 역삼동 12-3", "서울 강남구 역삼동 12-3 101동 1", true],
      ["서울 강남구 역삼동 12-3 101동 1", "서울 강남구 역삼동 12-3 101동 1001호", true],
      ["서울 종로구 삼청로 30", "서울 종로구 삼청로 30 (구 삼청로 5", true],
      ["서울 종로구 삼일대로 30다길 21", "서울 종로구 삼일대로 30다길 21 2층", true],
      ["경기 양평군 서종면 문호리 123", "경기 양평군 서종면 문호리 123 별관", true],
    ]);
  });

  describe("a parcel suffix typed one character at a time keeps the pin", () => {
    preservesCoordinates([
      ["서울 강남구 역삼동 12-3", "서울 강남구 역삼동 12-3ㅂ", true],
      ["서울 강남구 역삼동 12-3ㅂ", "서울 강남구 역삼동 12-3번", true],
      ["서울 강남구 역삼동 12-3번", "서울 강남구 역삼동 12-3번지", true],
    ]);
  });

  describe("spacing and full-width punctuation are not address changes", () => {
    preservesCoordinates([
      ["서울 용산구 한남대로28", "서울 용산구 한남대로 28", true],
      ["서울 용산구 한남대로28", "서울 용산구 한남대로28, 3층", true],
      ["서울 강남구 테헤란로 12", "서울 강남구 테헤란로 12（역삼동）", true],
      ["서울 강남구 테헤란로 １２", "서울 강남구 테헤란로 12 3층", true],
      ["서울 용산구", " 서울  용산구 ", true],
    ]);
  });

  describe("numbered and lettered street names are part of the street", () => {
    preservesCoordinates([
      ["서울 강남구 테헤란로4길 12", "서울 강남구 테헤란로4길 12 3층", true],
      ["서울 강남구 테헤란로4길 12", "서울 강남구 테헤란로4길 13", false],
      ["경기 성남시 중앙로 123번길 45", "경기 성남시 중앙로 123번길 45 2층", true],
      ["경기 성남시 중앙로 123번길 45", "경기 성남시 중앙로 123번길 46", false],
      ["서울 종로구 삼일대로 30다길 21", "서울 종로구 삼일대로 30다길 22", false],
      ["서울 관악구 신림로 23나길 5", "서울 관악구 신림로 23나길 9", false],
      ["서울 마포구 월드컵로 4안길 7", "서울 마포구 월드컵로 4안길 8", false],
      ["서울 중구 을지로3가 15", "서울 중구 을지로3가 15, 2층", true],
      ["서울 중구 을지로3가 15", "서울 중구 을지로3가 16", false],
      ["서울 성동구 성수동2가 300", "서울 성동구 성수동2가 300 B1", true],
      ["서울 성동구 성수동2가 300", "서울 성동구 성수동2가 301", false],
      ["서울 강남구 테헤란로 4 길 5", "서울 강남구 테헤란로 4 길 9", false],
      ["서울 중구 삼일대로 30 다길 21", "서울 중구 삼일대로 30 다길 22", false],
    ]);
  });

  describe("numbered administrative dongs and mountain lots keep their parcel number", () => {
    preservesCoordinates([
      ["서울 구로구 구로1동 123-4", "서울 구로구 구로1동 123-4 3층", true],
      ["서울 구로구 구로1동 123-4", "서울 구로구 구로1동 987-6", false],
      ["서울 영등포구 신길3동 100", "서울 영등포구 신길3동 200", false],
      ["서울 용산구 원효로 1", "서울 용산구 원효로1동 45-6", false],
      ["서울 동대문구 답십리1동 10", "서울 동대문구 답십리1동 20", false],
      ["서울 성동구 성수1가1동 685-1", "서울 성동구 성수1가1동 700", false],
      ["경기 양평군 서종면 문호리 산 12", "경기 양평군 서종면 문호리 산 12 2층", true],
      ["경기 양평군 서종면 문호리 산 12", "경기 양평군 서종면 문호리 산 13", false],
    ]);
  });

  describe("a changed building number or street clears the pin", () => {
    preservesCoordinates([
      ["서울 용산구 한남대로 28", "서울 용산구 한남대로 281", false],
      ["서울 용산구 한남대로 28", "서울 용산구 한남대로 283층", false],
      ["서울 용산구 한남대로 28", "서울 용산구 한남대로 28-1", false],
      ["서울 용산구 한남대로 28", "서울 용산구 한남대로 28\u20131", false],
      ["서울 용산구 한남대로 28", "서울 용산구 한남대로 28\u22121", false],
      ["서울 용산구 한남대로 28", "서울 용산구 한남대로 28\u200b1", false],
      ["서울 용산구 한남동 1-1번지", "서울 용산구 한남동 1-2번지", false],
      ["서울 용산구 한남대로 28 3층", "서울 용산구 이태원로 55 3층", false],
    ]);
  });

  describe("a landmark address without a street number keeps its pin through floor and unit details", () => {
    preservesCoordinates([
      ["서울시청", "서울시청 3", true],
      ["서울시청 3", "서울시청 3층", true],
      ["서울시청 3층", "서울시청 3층 302호", true],
      ["서울시청", "서울시청, 지하1층", true],
      ["서울시청", "서울시청 지", true],
      ["서울시청", "서울시청 (본관)", true],
      ["국립현대미술관 서울관", "국립현대미술관 서울관 B1", true],
      ["28 Hannam-daero", "28 Hannam-daero 3F", true],
    ]);
  });

  describe("renaming a landmark or losing the address clears the pin", () => {
    preservesCoordinates([
      ["국립현대미술관 서울관", "국립현대미술관 과천관", false],
      ["서울시청", "서울시청 별관", false],
      ["28 Hannam-daero", "29 Hannam-daero", false],
      ["", "서울", false],
      ["3층", "3층 302호", false],
      ["서울 용산구", "서울 용산구 한남", false],
      ["서울 용산구 한남대로 28", "서울 용산구 한남대로", false],
      ["서울 용산구 한남대로 28", "", false],
    ]);
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
