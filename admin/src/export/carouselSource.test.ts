import type { CuratorNoteFacts } from "../repositories/AdminCuratorNoteRepository";
import { carouselSourceOf } from "./carouselSource";
import { exhibitionCarouselArchiveName } from "./carouselZip";

const facts: CuratorNoteFacts = {
  exhibitionId: "abcd-1234",
  autoDraftBlocked: false,
  approvedVersionId: null,
  qualifies: true,
  packet: { name_ko: "전시", name_en: "Show", venue_name_ko: "갤러리" },
  packetHash: null,
  coverImageUrl: "https://example.invalid/cover.jpg",
  images: [
    { url: "https://example.invalid/cover.jpg", role: "cover", credit: "Gallery", altKo: "", altEn: "" },
    { url: "https://example.invalid/cover.jpg", role: "gallery", credit: "Gallery", altKo: "표지", altEn: "Cover" },
    { url: "https://example.invalid/1.jpg", role: "gallery", credit: "Photo A", altKo: "전경", altEn: "View" },
  ],
  versions: [],
};

describe("carouselSourceOf", () => {
  it("takes the cover, its credit, the gallery without the cover again, and the public page", () => {
    expect(carouselSourceOf(facts)).toEqual({
      facts: facts.packet,
      coverUrl: "https://example.invalid/cover.jpg",
      coverCredit: "Gallery",
      gallery: [{ url: "https://example.invalid/1.jpg", credit: "Photo A", altKo: "전경", altEn: "View" }],
      exhibitionUrl: "https://gallrmap.com/exhibitions/show-abcd/",
    });
  });

  it("names an exhibition carousel's archive apart from a note's", () => {
    expect(exhibitionCarouselArchiveName("abcd-1234", "en")).toBe("exhibition-carousel-abcd-1234-en.zip");
  });
});
