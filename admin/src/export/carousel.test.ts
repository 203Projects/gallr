import {
  BODY_LINE_HEIGHT,
  BODY_PX,
  CAPTION_PX,
  CLOSING_FACTS_COLUMN_LEFT,
  CLOSING_FACTS_COLUMN_WIDTH,
  CLOSING_FACTS_TOP,
  CLOSING_POSTER_HEIGHT_PX,
  CLOSING_POSTER_WIDTH_PX,
  INSIGHT_BAND_PX,
  INSIGHT_BODY_TOP,
  MAX_BODY_LINES,
  MAX_HEADLINE_LINES,
  QR_SAFE_WIDTH,
  QR_TILE_PX,
  QR_TILE_TOP,
  SLIDE_HEIGHT,
  SLIDE_MARGIN,
  SWATCH_PX,
  TITLE_LINE_HEIGHT,
  TITLE_PX,
  WORDMARK_PX,
  captionHashtags,
  formatDates,
  layoutCarousel,
  slideFileName,
  splitLead,
  wrapText,
  type CarouselFacts,
  type CarouselNote,
  type ClosingSlide,
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
};

const bodyLine = BODY_PX * BODY_LINE_HEIGHT;
const contentBottom = SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX - 48;

describe("layoutCarousel", () => {
  it("produces a cover, one slide per insight and a closing slide in the chosen language", () => {
    const layout = layoutCarousel(note, facts, "en", measure);

    expect(layout.slides.map((slide) => slide.kind)).toEqual(["cover", "insight", "insight", "closing"]);
    expect(layout.slides[0]).toMatchObject({
      hookLines: ["Why a bear walked into a fairy tale"],
      metaLines: ["Esther Schipper Seoul · 2026.09.02 – 10.31"],
    });
    expect(layout.slides[1]).toMatchObject({ label: "CURATOR'S EYE 01", lines: [] });
    expect((layout.slides[1] as InsightSlide).headlineLines.join(" ")).toBe("A first solo show in Seoul matters less than the number four.");
    expect(layout.slides[2]).toMatchObject({ label: "CURATOR'S EYE 02" });
    expect(layout.slides[3]).toMatchObject({
      kind: "closing",
      dates: "2026.09.02 – 10.31",
      venue: "Esther Schipper Seoul",
      district: "Yongsan-gu",
      hours: "11am - 6pm Tuesday - Saturday",
    });
    expect(layout.slides[3]).not.toHaveProperty("disclosure");
    expect(layout.omitted).toEqual([]);
    expect(layout.slides.map((slide, index) => slideFileName(index, slide))).toEqual([
      "01-cover.png",
      "02-insight.png",
      "03-insight.png",
      "04-closing.png",
    ]);
  });

  it("uses Korean text for a Korean export and carries no drafting or review line", () => {
    const layout = layoutCarousel(note, facts, "ko", measure);

    expect(layout.slides[0]).toMatchObject({ hookLines: ["곰 한 마리가 동화 속으로 들어간 이유"], label: "큐레이터의 시선" });
    expect(layout.slides.at(-1)).toMatchObject({ district: "용산구" });
    expect(JSON.stringify(layout)).not.toMatch(/AI 초안|검수/);
    expect((layout.slides.at(-1) as { tipLines: string[] }).tipLines[0]).toContain("관람 팁 · 복도 끝의");
  });

  it("splits an insight that does not fit across slides instead of shrinking it", () => {
    const long = Array.from({ length: 18 }, (_, index) => `sentence number ${index + 1} of the long paragraph goes on.`).join(" ");
    const layout = layoutCarousel({ ...note, insightsEn: [{ text: long, grounding: ["description_en"] }] }, facts, "en", measure);
    const insightSlides = layout.slides.filter((slide): slide is InsightSlide => slide.kind === "insight");

    expect(insightSlides.length).toBeGreaterThan(1);
    expect(insightSlides.every((slide) => slide.lines.length >= 1 && slide.lines.length <= MAX_BODY_LINES)).toBe(true);
    expect(insightSlides[0].headlineLines.join(" ")).toBe("sentence number 1 of the long paragraph goes on.");
    expect(insightSlides[0].lines).toHaveLength(Math.floor((contentBottom - insightSlides[0].bodyTop) / bodyLine));
    expect(insightSlides.slice(1).every((slide) => slide.headlineLines.length === 0 && slide.bodyTop === INSIGHT_BODY_TOP)).toBe(true);
    expect(insightSlides.slice(1, -1).every((slide) => slide.lines.length === MAX_BODY_LINES)).toBe(true);
    expect(insightSlides.flatMap((slide) => [...slide.headlineLines, ...slide.lines]).join(" ")).toBe(long);
    expect(new Set(insightSlides.map((slide) => slide.label))).toEqual(new Set(["CURATOR'S EYE 01"]));
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
    expect(layout.omitted.slice(-3)).toEqual([paragraph, paragraph, paragraph]);
  });

  it("opens an insight with its first sentence as a headline and sets the rest as body under it", () => {
    const text = "방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역한다. 그림을 보기 전에 벽의 색을 먼저 보라.";
    const layout = layoutCarousel({ ...note, insightsKo: [{ text, grounding: ["description_ko"] }] }, facts, "ko", measure);
    const slide = layout.slides[1] as InsightSlide;

    expect(slide.label).toBe("큐레이터의 시선 01");
    expect(slide.headlineLines).toEqual(["방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역한다."]);
    expect(slide.lines).toEqual(["그림을 보기 전에 벽의 색을 먼저 보라."]);
    expect(slide.bodyTop).toBe(INSIGHT_BODY_TOP + TITLE_PX * TITLE_LINE_HEIGHT + 32);
  });

  it("sets an opening sentence too long for a headline as body rather than shrinking it", () => {
    const opening = `${Array.from({ length: 30 }, () => "words").join(" ")}.`;
    const text = `${opening} Then a short one.`;
    const layout = layoutCarousel({ ...note, insightsEn: [{ text, grounding: ["description_en"] }] }, facts, "en", measure);
    const slide = layout.slides[1] as InsightSlide;

    expect(wrapText(opening, TITLE_PX, 500, measure).length).toBeGreaterThan(MAX_HEADLINE_LINES);
    expect(slide.headlineLines).toEqual([]);
    expect(slide.bodyTop).toBe(INSIGHT_BODY_TOP);
    expect(slide.lines.join(" ")).toBe(text);
  });

  it("gives each insight its own picture when the exhibition has more, and the cover's slices to the rest", () => {
    const three = {
      ...note,
      insightsEn: [1, 2, 3].map((index) => ({ text: `Point ${index}.`, grounding: [] })),
    };
    const layout = layoutCarousel(three, facts, "en", measure, { coverCredit: "", galleryCredits: ["Photo A", ""] });
    const insightSlides = layout.slides.filter((slide): slide is InsightSlide => slide.kind === "insight");

    expect(insightSlides.map((slide) => slide.image)).toEqual([0, 1, null]);
    expect(insightSlides.map((slide) => slide.focus)).toEqual([0.5, 0.5, 2.5 / 3]);
    expect(layoutCarousel(three, facts, "en", measure).slides.filter((slide): slide is InsightSlide => slide.kind === "insight").map((slide) => slide.image))
      .toEqual([null, null, null]);
  });

  it("credits the photographs in the caption, each name once", () => {
    const layout = layoutCarousel(note, facts, "ko", measure, { coverCredit: "사진 김지원", galleryCredits: ["ⓒ 에스더쉬퍼", "사진 김지원", "안 쓰인 사진"] });
    const lines = layout.caption.split("\n");

    expect(lines.at(-3)).toBe("사진 · 사진 김지원, ⓒ 에스더쉬퍼");
    expect(layoutCarousel(note, facts, "en", measure, { coverCredit: "", galleryCredits: ["Photo A"] }).caption).toContain("\n\nPhotos · Photo A\n\n#gallr");
    expect(layoutCarousel(note, facts, "ko", measure).caption).not.toContain("사진 ·");
  });

  it("gives each insight its own slice of the poster and keeps it across that insight's slides", () => {
    const long = Array.from({ length: 18 }, (_, index) => `sentence number ${index + 1} of the long paragraph goes on.`).join(" ");
    const layout = layoutCarousel(
      { ...note, insightsEn: [{ text: long, grounding: [] }, { text: "A second point.", grounding: [] }] },
      facts,
      "en",
      measure,
    );
    const insightSlides = layout.slides.filter((slide): slide is InsightSlide => slide.kind === "insight");

    expect(insightSlides.map((slide) => slide.focus)).toEqual([0.25, 0.25, 0.25, 0.75]);
  });

  it("falls back to Korean when an English export is asked of a Korean-only note", () => {
    const layout = layoutCarousel({ ...note, hookEn: "", insightsEn: [], tipEn: "" }, facts, "en", measure);

    expect(layout.slides[0]).toMatchObject({ hookLines: ["곰 한 마리가 동화 속으로 들어간 이유"] });
    expect(layout.slides.filter((slide) => slide.kind === "insight")).toHaveLength(2);
  });

  it("writes the caption from the note and tags only from the packet", () => {
    const layout = layoutCarousel(note, facts, "ko", measure);

    expect(layout.caption.split("\n")[0]).toBe("곰 한 마리가 동화 속으로 들어간 이유");
    expect(layout.caption).toContain(
      "\n\nWho Lived Happily Ever After? | 사이먼 후지와라\n에스더쉬퍼 · 용산구\n2026.09.02 – 10.31\n11am - 6pm Tuesday - Saturday\n\n",
    );
    expect(layout.caption).toContain(
      "관람 포인트\n① 서울 첫 개인전이라는 사실보다 중요한 것은 네 번째 전시라는 숫자다.\n② 방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역한다.\n\n관람 팁 · 복도 끝의 가장 어두운 방부터 보라.",
    );
    expect(layout.caption).not.toMatch(/\n\n\n/);
    expect(layout.caption.split("\n").at(-1)).toBe("#gallr #전시 #서울전시 #에스더쉬퍼 #용산구 #회화 #정체성");
    expect(layoutCarousel(note, { ...facts, hours: "11am - 6pm\nTuesday - Saturday" }, "en", measure).caption).toContain(
      "Esther Schipper Seoul · Yongsan-gu\n2026.09.02 – 10.31\n11am - 6pm · Tuesday - Saturday\n\nViewing points\n① A first solo show",
    );
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

describe("insight slide geometry", () => {
  it("reserves a 480px poster band and budgets the body lines from the space left under it", () => {
    expect(INSIGHT_BAND_PX).toBe(480);
    const bodyTop = INSIGHT_BAND_PX + SLIDE_MARGIN + CAPTION_PX + 16 + SWATCH_PX + 48;
    const contentBottom = SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX - 48;
    expect(INSIGHT_BODY_TOP).toBe(bodyTop);
    expect(bodyTop).toBe(672);
    expect(contentBottom).toBe(1190);
    expect(MAX_BODY_LINES).toBe(Math.floor((contentBottom - bodyTop) / (BODY_PX * BODY_LINE_HEIGHT)));
    expect(MAX_BODY_LINES).toBe(8);
    expect(bodyTop + MAX_BODY_LINES * BODY_PX * BODY_LINE_HEIGHT).toBeLessThanOrEqual(contentBottom);
  });
});

describe("closing slide geometry", () => {
  const lineHeight = BODY_PX * BODY_LINE_HEIGHT;

  it("keeps a 3:4 poster tile 400px wide under the label with the facts wrapped to its right", () => {
    expect(CLOSING_POSTER_WIDTH_PX).toBe(400);
    expect(CLOSING_POSTER_HEIGHT_PX).toBe(Math.round((400 * 4) / 3));
    expect(CLOSING_FACTS_TOP).toBe(SLIDE_MARGIN + CAPTION_PX + 40);
    expect(CLOSING_FACTS_COLUMN_LEFT).toBe(SLIDE_MARGIN + CLOSING_POSTER_WIDTH_PX + 48);
    expect(CLOSING_FACTS_COLUMN_WIDTH).toBe(1080 - 2 * SLIDE_MARGIN - CLOSING_POSTER_WIDTH_PX - 48);
    expect(QR_TILE_TOP).toBe(SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX - 48 - QR_TILE_PX);

    const closing = layoutCarousel(note, facts, "en", measure).slides.at(-1) as ClosingSlide;
    expect(closing.factsLeft).toBe(CLOSING_FACTS_COLUMN_LEFT);
    expect(closing.factsTop).toBe(CLOSING_FACTS_TOP);
    expect(closing.titleLineCount).toBe(2);
    expect(closing.factLines).toEqual([
      "Who Lived Happily Ever",
      "After?",
      "2026.09.02 – 10.31",
      "Esther Schipper Seoul ·",
      "Yongsan-gu",
      "11am - 6pm Tuesday -",
      "Saturday",
    ]);
    expect(closing.factLines.every((line) => measure(line, BODY_PX, 400) <= CLOSING_FACTS_COLUMN_WIDTH)).toBe(true);
    expect(closing.tipTop).toBe(CLOSING_FACTS_TOP + CLOSING_POSTER_HEIGHT_PX + 32);
    expect(closing.tipLines.join(" ")).toBe("Viewing tip · Start with the darkest room at the end of the corridor.");
  });

  it("wraps the facts under the poster tile when they would run past it, and never shrinks them", () => {
    const hours = Array.from({ length: 36 }, () => "Tue").join(" ");
    const layout = layoutCarousel(note, { ...facts, hours }, "en", measure);
    const closing = layout.slides.at(-1) as ClosingSlide;

    expect(closing.factsLeft).toBe(SLIDE_MARGIN);
    expect(closing.factsTop).toBe(CLOSING_FACTS_TOP + CLOSING_POSTER_HEIGHT_PX + 32);
    expect(closing.factLines.every((line) => measure(line, BODY_PX, 400) <= QR_SAFE_WIDTH)).toBe(true);
    expect(closing.factsTop + closing.factLines.length * lineHeight).toBeLessThanOrEqual(SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX - 48);
    expect(closing.factLines).toHaveLength(7);
    expect(closing.tipLines).toEqual([]);
    expect(layout.omitted.join(" ")).toContain("Tue Tue");
    expect(layout.omitted.join(" ")).toContain("Viewing tip · Start with the darkest room");
  });

  it("wraps the tip beside the QR tile when it would otherwise reach into it", () => {
    const tipEn = Array.from({ length: 16 }, () => "room").join(" ");
    const closing = layoutCarousel({ ...note, tipEn }, facts, "en", measure).slides.at(-1) as ClosingSlide;

    expect(closing.tipTop).toBe(CLOSING_FACTS_TOP + CLOSING_POSTER_HEIGHT_PX + 32);
    expect(closing.tipLines).toHaveLength(4);
    expect(closing.tipLines.every((line) => measure(line, BODY_PX, 500) <= QR_SAFE_WIDTH)).toBe(true);
    expect(closing.tipLines.join(" ")).toBe(`Viewing tip · ${tipEn}`);
  });

  it("lets a long tip run down beside the QR tile to the footer and reports only what is left", () => {
    const tipEn = Array.from({ length: 60 }, () => "room").join(" ");
    const layout = layoutCarousel({ ...note, tipEn }, facts, "en", measure);
    const closing = layout.slides.at(-1) as ClosingSlide;
    const room = Math.floor((SLIDE_HEIGHT - SLIDE_MARGIN - WORDMARK_PX - 48 - closing.tipTop) / lineHeight);

    expect(room).toBe(7);
    expect(closing.tipLines).toHaveLength(room);
    expect(`${closing.tipLines.join(" ")} ${layout.omitted.join(" ")}`).toBe(`Viewing tip · ${tipEn}`);
  });
});

describe("splitLead", () => {
  it("takes the first sentence and leaves the rest", () => {
    expect(splitLead("벽의 색을 먼저 보라. 색이 바뀌면 이야기가 바뀐다.")).toEqual({ lead: "벽의 색을 먼저 보라.", rest: "색이 바뀌면 이야기가 바뀐다." });
    expect(splitLead("Is it a room? It is two. And a corridor.")).toEqual({ lead: "Is it a room?", rest: "It is two. And a corridor." });
    expect(splitLead("한 문장뿐이다.")).toEqual({ lead: "한 문장뿐이다.", rest: "" });
  });

  it("does not stop at an abbreviation or inside a number", () => {
    expect(splitLead("Oh In-hwan vs. Jang Seo-young share one floor. Start left.")).toEqual({
      lead: "Oh In-hwan vs. Jang Seo-young share one floor.",
      rest: "Start left.",
    });
    expect(splitLead("The screen is 8.1 metres wide. Stand back.")).toEqual({ lead: "The screen is 8.1 metres wide.", rest: "Stand back." });
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
