import { BODY_LINE_HEIGHT, CAPTION_PX, SLIDE_HEIGHT, SLIDE_MARGIN, WORDMARK_PX, type CoverSlide, type Measure } from "./carousel";
import type { ApprovedCuratorNote } from "../repositories/AdminCuratorNoteRepository";
import {
  INDEX_ROW_GAP,
  INDEX_TOP,
  RoundupSizeError,
  closingInMonth,
  closingTitle,
  layoutRoundup,
  roundupArchiveName,
  roundupHashtags,
  roundupSlideFileName,
  type RoundupEntrySlide,
  type RoundupIndexSlide,
} from "./roundup";

/** Every character is 20px wide regardless of size, so a 936px line holds 46 characters. */
const measure: Measure = (text) => Array.from(text).length * 20;

function note(id: string, overrides: Partial<ApprovedCuratorNote> = {}): ApprovedCuratorNote {
  return {
    versionId: `version-${id}`,
    exhibitionId: id,
    versionNumber: 1,
    hookKo: `${id} 훅`,
    hookEn: `${id} hook`,
    approvedAt: "2026-10-12T03:00:00Z",
    nameKo: `${id} 전시`,
    nameEn: `${id} show`,
    venueNameKo: "에스더쉬퍼",
    venueNameEn: "Esther Schipper Seoul",
    regionKo: "용산구",
    regionEn: "Yongsan-gu",
    openingDate: "2026-09-02",
    closingDate: "2026-10-31",
    coverImageUrl: `https://example.invalid/${id}.jpg`,
    ...overrides,
  };
}

describe("layoutRoundup", () => {
  it("lays out a cover with the editor's title, one slide per exhibition in the order given, and the list", () => {
    const layout = layoutRoundup("10월에 끝나는 전시 3", [note("a"), note("b"), note("c")], "ko", measure);

    expect(layout.slides.map((slide) => slide.kind)).toEqual(["cover", "entry", "entry", "entry", "index"]);
    expect(layout.slides[0]).toMatchObject({ label: "큐레이터의 시선", hookLines: ["10월에 끝나는 전시 3"], titleLines: [], metaLines: ["전시 3곳"] });
    expect(layout.slides[2]).toEqual({
      kind: "entry",
      exhibitionId: "b",
      label: "02 / 03",
      nameLines: ["b 전시"],
      metaLines: ["에스더쉬퍼 · 용산구", "2026.09.02 – 10.31"],
      hookLines: ["b 훅"],
    });
    expect((layout.slides[4] as RoundupIndexSlide).rows).toEqual([
      { nameLines: ["01 a 전시"], metaLines: ["에스더쉬퍼 · 용산구 · 2026.09.02 – 10.31"] },
      { nameLines: ["02 b 전시"], metaLines: ["에스더쉬퍼 · 용산구 · 2026.09.02 – 10.31"] },
      { nameLines: ["03 c 전시"], metaLines: ["에스더쉬퍼 · 용산구 · 2026.09.02 – 10.31"] },
    ]);
    expect(layout.omitted).toEqual([]);
    expect(layout.slides.map((slide, index) => roundupSlideFileName(index, slide))).toEqual([
      "01-cover.png",
      "02-entry.png",
      "03-entry.png",
      "04-entry.png",
      "05-index.png",
    ]);
  });

  it("writes English when asked and falls back to Korean where a note has none", () => {
    const layout = layoutRoundup("Two shows", [note("a"), note("b", { hookEn: "", nameEn: "" })], "en", measure);

    expect((layout.slides[0] as CoverSlide).metaLines).toEqual(["2 exhibitions"]);
    expect(layout.slides[1]).toMatchObject({ nameLines: ["a show"], hookLines: ["a hook"], metaLines: ["Esther Schipper Seoul · Yongsan-gu", "2026.09.02 – 10.31"] });
    expect(layout.slides[2]).toMatchObject({ nameLines: ["b 전시"], hookLines: ["b 훅"] });
  });

  it("reports a name or hook that runs past its lines instead of shrinking it", () => {
    const longName = Array.from({ length: 40 }, () => "name").join(" ");
    const longHook = Array.from({ length: 60 }, () => "hook").join(" ");
    const layout = layoutRoundup("Title", [note("a", { nameEn: longName, hookEn: longHook }), note("b")], "en", measure);
    const entry = layout.slides[1] as RoundupEntrySlide;

    expect(entry.nameLines).toHaveLength(3);
    expect(entry.hookLines).toHaveLength(4);
    expect(`${entry.nameLines.join(" ")} ${layout.omitted[0]}`).toBe(longName);
    expect(`${entry.hookLines.join(" ")} ${layout.omitted[1]}`).toBe(longHook);
  });

  it("keeps ten exhibitions on one list slide and continues on a second when the names run to two lines", () => {
    const contentBottom = SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX - 48;
    const line = CAPTION_PX * BODY_LINE_HEIGHT;
    const venue = { venueNameEn: "Gallery", regionEn: "Jongno-gu" };
    const short = layoutRoundup("Ten", Array.from({ length: 10 }, (_, index) => note(`e${index}`, venue)), "en", measure);
    const longName = Array.from({ length: 12 }, () => "name").join(" ");
    const long = layoutRoundup("Ten", Array.from({ length: 10 }, (_, index) => note(`e${index}`, { ...venue, nameEn: longName })), "en", measure);
    const lists = (layout: typeof short) => layout.slides.filter((slide): slide is RoundupIndexSlide => slide.kind === "index");

    expect(Math.floor((contentBottom - INDEX_TOP + INDEX_ROW_GAP) / (2 * line + INDEX_ROW_GAP))).toBe(10);
    expect(lists(short).map((slide) => slide.rows.length)).toEqual([10]);
    expect(Math.floor((contentBottom - INDEX_TOP + INDEX_ROW_GAP) / (3 * line + INDEX_ROW_GAP))).toBe(7);
    expect(lists(long).map((slide) => slide.rows.length)).toEqual([7, 3]);
    expect(lists(long)[1].rows[0].nameLines[0]).toMatch(/^08 name/);
    expect(long.slides).toHaveLength(1 + 10 + 2);
  });

  it("refuses fewer than two, more than ten, or a blank title", () => {
    const eleven = Array.from({ length: 11 }, (_, index) => note(`e${index}`));

    expect(() => layoutRoundup("Title", [note("a")], "ko", measure)).toThrow(RoundupSizeError);
    expect(() => layoutRoundup("Title", eleven, "ko", measure)).toThrow(RoundupSizeError);
    expect(() => layoutRoundup("   ", [note("a"), note("b")], "ko", measure)).toThrow(RoundupSizeError);
  });

  it("writes the caption as the title, a numbered block per exhibition and tags from the venues and districts", () => {
    const layout = layoutRoundup(
      "10월에 끝나는 전시 2",
      [note("a"), note("b", { venueNameKo: "국제갤러리", regionKo: "종로구", closingDate: "2027-01-03" })],
      "ko",
      measure,
    );

    expect(layout.caption).toBe(
      [
        "10월에 끝나는 전시 2",
        "① a 전시\n에스더쉬퍼 · 용산구\n2026.09.02 – 10.31",
        "② b 전시\n국제갤러리 · 종로구\n2026.09.02 – 2027.01.03",
        "#gallr #전시 #서울전시 #에스더쉬퍼 #국제갤러리 #용산구 #종로구",
      ].join("\n\n"),
    );
    expect(roundupHashtags(Array.from({ length: 10 }, (_, index) => note(`e${index}`, { venueNameEn: `Venue ${index}`, regionEn: `District ${index}` })), "en")).toHaveLength(15);
  });
});

describe("roundup presets and names", () => {
  it("picks the exhibitions closing in a month that have a cover, at most ten", () => {
    const notes = [
      note("oct-1", { closingDate: "2026-10-18" }),
      note("oct-no-cover", { closingDate: "2026-10-20", coverImageUrl: null }),
      note("nov", { closingDate: "2026-11-02" }),
      ...Array.from({ length: 12 }, (_, index) => note(`oct-${index + 2}`, { closingDate: "2026-10-31" })),
    ];

    const picked = closingInMonth(notes, "2026-10-13");
    expect(picked).toHaveLength(10);
    expect(picked.map((item) => item.exhibitionId).slice(0, 2)).toEqual(["oct-1", "oct-2"]);
    expect(closingInMonth(notes, "2026-12-01")).toEqual([]);
  });

  it("suggests a plain title and names the archive", () => {
    expect(closingTitle("ko", "2026-10-13", 7)).toBe("10월에 끝나는 전시 7");
    expect(closingTitle("en", "2026-10-13", 7)).toBe("7 exhibitions closing in October");
    expect(roundupArchiveName("ko", 7, "2026-10-13")).toBe("curator-roundup-2026-10-13-7-ko.zip");
  });
});
