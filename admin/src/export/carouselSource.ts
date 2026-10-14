import type { CuratorNoteFacts } from "../repositories/AdminCuratorNoteRepository";
import type { CarouselFacts } from "./carousel";
import type { CarouselGalleryImage } from "./carouselCanvas";
import { publicExhibitionUrl } from "./publicExhibitionUrl";

/** What a single-exhibition carousel draws from an exhibition's facts, with or without a note. */
export interface CarouselSource {
  facts: CarouselFacts;
  coverUrl: string | null;
  coverCredit: string;
  gallery: CarouselGalleryImage[];
  exhibitionUrl: string;
}

export function carouselSourceOf(facts: CuratorNoteFacts): CarouselSource {
  const packet = (facts.packet ?? {}) as Record<string, unknown>;
  const coverUrl = facts.coverImageUrl;
  return {
    facts: packet as CarouselFacts,
    coverUrl,
    coverCredit: facts.images.find((image) => image.role === "cover")?.credit ?? "",
    // A gallery image that repeats the cover is left out, as the Notes pane and the app leave it out (`notePictures`).
    gallery: facts.images
      .filter((image) => image.role === "gallery" && image.url !== coverUrl)
      .map((image) => ({ url: image.url, credit: image.credit, altKo: image.altKo, altEn: image.altEn })),
    exhibitionUrl: publicExhibitionUrl({
      id: facts.exhibitionId,
      nameEn: typeof packet.name_en === "string" ? packet.name_en : undefined,
      nameKo: typeof packet.name_ko === "string" ? packet.name_ko : undefined,
    }),
  };
}
