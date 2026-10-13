import { unzipSync } from "fflate";
import type { CarouselLayout } from "./carousel";
import { buildCarouselArchive, carouselArchiveName, pngDimensions } from "./carouselZip";

/** A PNG header for the given size with no image data; enough for the dimension check. */
function fakePng(width: number, height: number): Uint8Array {
  const bytes = new Uint8Array(33);
  bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a], 0);
  const view = new DataView(bytes.buffer);
  view.setUint32(8, 13);
  bytes.set([0x49, 0x48, 0x44, 0x52], 12);
  view.setUint32(16, width);
  view.setUint32(20, height);
  return bytes;
}

const layout: CarouselLayout = {
  language: "ko",
  slides: [
    { kind: "cover", label: "큐레이터의 시선", hookLines: ["훅"], titleLines: ["전시"], venue: "장소" },
    { kind: "insight", label: "큐레이터의 시선", lines: ["하나"] },
    { kind: "insight", label: "큐레이터의 시선", lines: ["둘"] },
    { kind: "closing", label: "큐레이터의 시선", dates: "2026.09.02 – 10.31", venue: "장소", district: "용산구", hours: null, tipLines: ["관람 팁 · 팁"] },
  ],
  caption: "훅\n\n하나\n\n#gallr",
  omitted: [],
};

describe("buildCarouselArchive", () => {
  it("stores the slides in order with the caption and no report when nothing was left out", () => {
    const archive = buildCarouselArchive(layout, layout.slides.map((slide) => ({ slide, png: fakePng(1080, 1350) })));
    const entries = unzipSync(archive);

    expect(Object.keys(entries)).toEqual(["01-cover.png", "02-insight.png", "03-insight.png", "04-closing.png", "caption.txt"]);
    expect(new TextDecoder().decode(entries["caption.txt"])).toBe("훅\n\n하나\n\n#gallr");
    expect(Object.keys(entries).filter((name) => name.endsWith(".png")).every((name) => {
      const size = pngDimensions(entries[name]);
      return size?.width === 1080 && size.height === 1350;
    })).toBe(true);
  });

  it("adds a report listing what was left out", () => {
    const archive = buildCarouselArchive({ ...layout, omitted: ["남은 문장."] }, layout.slides.map((slide) => ({ slide, png: fakePng(1080, 1350) })));
    const entries = unzipSync(archive);

    expect(new TextDecoder().decode(entries["report.txt"])).toContain("남은 문장.");
  });

  it("names the archive after the note and language", () => {
    expect(carouselArchiveName("who-lived-happily", 2, "en")).toBe("curator-note-who-lived-happily-v2-en.zip");
    expect(pngDimensions(new Uint8Array([1, 2, 3]))).toBeNull();
  });
});
