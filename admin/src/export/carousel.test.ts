import {
  captionHashtags,
  formatDates,
  layoutCarousel,
  slideFileName,
  wrapText,
  type CarouselFacts,
  type CarouselNote,
  type InsightSlide,
  type Measure,
} from "./carousel";

/** Every character is 20px wide regardless of size, so a 936px line holds 46 characters. */
const measure: Measure = (text) => Array.from(text).length * 20;

const facts: CarouselFacts = {
  name_ko: "Who Lived Happily Ever After? | 사이먼 후지와라",
  name_en: "Who Lived Happily Ever After?",
  venue_name_ko: "에스더쉬퍼",
  venue_name_en: "Esther Schipper Seoul",
  region_ko: "용산구",
  region_en: "Yongsan-gu",
  opening_date: "2026-09-02",
  closing_date: "2026-10-31",
  hours: "11am - 6pm Tuesday - Saturday",
  art_terms: [
    { name_ko: "회화", name_en: "Painting" },
    { name_ko: "정체성", name_en: "Identity" },
  ],
};

const note: CarouselNote = {
  hookKo: "곰 한 마리가 동화 속으로 들어간 이유",
  hookEn: "Why a bear walked into a fairy tale",
  insightsKo: [
    { text: "서울 첫 개인전이라는 사실보다 중요한 것은 네 번째 전시라는 숫자다.", grounding: ["description_ko"] },
    { text: "방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역한다.", grounding: ["description_ko"] },
  ],
  insightsEn: [
    { text: "A first solo show in Seoul matters less than the number four.", grounding: ["description_en"] },
    { text: "Each room changes colour to stage the next disguise.", grounding: ["description_en"] },
  ],
  tipKo: "복도 끝의 가장 어두운 방부터 보라.",
  tipEn: "Start with the darkest room at the end of the corridor.",
  reviewerNameKo: "김지원",
  reviewerNameEn: "Jiwon Kim",
};

describe("layoutCarousel", () => {
  it("produces a cover, one slide per insight and a closing slide in the chosen language", () => {
    const layout = layoutCarousel(note, facts, "en", measure);

    expect(layout.slides.map((slide) => slide.kind)).toEqual(["cover", "insight", "insight", "closing"]);
    expect(layout.slides[0]).toMatchObject({ hookLines: ["Why a bear walked into a fairy tale"], venue: "Esther Schipper Seoul" });
    expect((layout.slides[1] as InsightSlide).lines.join(" ")).toBe("A first solo show in Seoul matters less than the number four.");
    expect(layout.slides[3]).toMatchObject({
      kind: "closing",
      dates: "2026.09.02 – 10.31",
      venue: "Esther Schipper Seoul",
      district: "Yongsan-gu",
      hours: "11am - 6pm Tuesday - Saturday",
      disclosure: "Drafted with AI, reviewed by Jiwon Kim",
    });
    expect(layout.omitted).toEqual([]);
    expect(layout.slides.map((slide, index) => slideFileName(index, slide))).toEqual([
      "01-cover.png",
      "02-insight.png",
      "03-insight.png",
      "04-closing.png",
    ]);
  });

  it("uses Korean text and the Korean disclosure for a Korean export", () => {
    const layout = layoutCarousel(note, facts, "ko", measure);

    expect(layout.slides[0]).toMatchObject({ hookLines: ["곰 한 마리가 동화 속으로 들어간 이유"], label: "큐레이터의 시선" });
    expect(layout.slides.at(-1)).toMatchObject({ disclosure: "AI 초안 · 김지원 검수", district: "용산구" });
    expect((layout.slides.at(-1) as { tipLines: string[] }).tipLines[0]).toContain("관람 팁 · 복도 끝의");
  });

  it("splits an insight that does not fit across slides instead of shrinking it", () => {
    const long = Array.from({ length: 30 }, (_, index) => `sentence number ${index + 1} of the long paragraph goes on.`).join(" ");
    const layout = layoutCarousel({ ...note, insightsEn: [{ text: long, grounding: ["description_en"] }] }, facts, "en", measure);
    const insightSlides = layout.slides.filter((slide): slide is InsightSlide => slide.kind === "insight");

    expect(insightSlides.length).toBeGreaterThan(1);
    expect(insightSlides.every((slide) => slide.lines.length >= 1 && slide.lines.length <= 11)).toBe(true);
    expect(insightSlides.flatMap((slide) => slide.lines).join(" ")).toBe(long);
    expect(layout.omitted).toEqual([]);
  });

  it("stops at four insight slides and reports what was left out", () => {
    const paragraph = Array.from({ length: 40 }, (_, index) => `line ${index + 1} is forty characters long when it is set.`).join(" ");
    const layout = layoutCarousel(
      { ...note, insightsEn: [1, 2, 3, 4].map(() => ({ text: paragraph, grounding: ["description_en"] })) },
      facts,
      "en",
      measure,
    );

    expect(layout.slides.filter((slide) => slide.kind === "insight")).toHaveLength(4);
    expect(layout.omitted.length).toBeGreaterThan(0);
    expect(layout.omitted.join(" ")).toContain("line 40 is forty characters long");
    expect(layout.slides.every((slide) => slide.kind !== "insight" || slide.lines.length > 0)).toBe(true);
  });

  it("falls back to Korean when an English export is asked of a Korean-only note", () => {
    const layout = layoutCarousel({ ...note, hookEn: "", insightsEn: [], tipEn: "" }, facts, "en", measure);

    expect(layout.slides[0]).toMatchObject({ hookLines: ["곰 한 마리가 동화 속으로 들어간 이유"] });
    expect(layout.slides.filter((slide) => slide.kind === "insight")).toHaveLength(2);
  });

  it("writes the caption from the note and tags only from the packet", () => {
    const layout = layoutCarousel(note, facts, "ko", measure);

    expect(layout.caption.split("\n")[0]).toBe("곰 한 마리가 동화 속으로 들어간 이유");
    expect(layout.caption).toContain("2026.09.02 – 10.31 · 에스더쉬퍼");
    expect(layout.caption.split("\n").at(-1)).toBe("#gallr #전시 #서울전시 #에스더쉬퍼 #용산구 #회화 #정체성");
    expect(captionHashtags({}, "en")).toEqual(["#gallr", "#exhibition", "#seoul"]);
    expect(captionHashtags({ venue_name_en: "White Cube Seoul", art_terms: Array(9).fill({ name_en: "T" }) }, "en")).toEqual([
      "#gallr",
      "#exhibition",
      "#seoul",
      "#WhiteCubeSeoul",
      "#T",
    ]);
  });
});

describe("wrapText and formatDates", () => {
  it("wraps by word and breaks an over-long word by character", () => {
    expect(wrapText("one two three", 44, 400, measure, 180)).toEqual(["one two", "three"]);
    expect(wrapText("abcdefghijkl", 44, 400, measure, 100)).toEqual(["abcde", "fghij", "kl"]);
    expect(wrapText("   ", 44, 400, measure)).toEqual([]);
  });

  it("shortens the closing date when the year repeats", () => {
    expect(formatDates("2026-09-02", "2026-10-31")).toBe("2026.09.02 – 10.31");
    expect(formatDates("2026-12-20", "2027-01-17")).toBe("2026.12.20 – 2027.01.17");
    expect(formatDates(undefined, "2026-10-31")).toBe("2026.10.31");
  });
});
