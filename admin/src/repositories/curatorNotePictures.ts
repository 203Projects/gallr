import type { CuratorNoteImage } from "./AdminCuratorNoteRepository";

/** The app sets at most this many gallery pictures into a note, one above each insight (`NOTE_IMAGE_LIMIT`). */
export const NOTE_PICTURE_LIMIT = 4;

/** Where an exhibition's pictures land when readers open its note. */
export interface NotePictures {
  /** The cover, shown as the note's thumbnail; null when the exhibition has none. */
  cover: CuratorNoteImage | null;
  /** Gallery pictures the note shows, each above the insight with this one-based number. */
  placed: { image: CuratorNoteImage; insight: number }[];
  /** Gallery pictures the exhibition page shows but the note does not. */
  unused: CuratorNoteImage[];
  /** One-based numbers of the insights that get no picture. */
  withoutPicture: number[];
}

/**
 * Mirrors the app's rule for the note block (`Exhibition.noteImages` in the shared module): gallery pictures in
 * staff order, without one that repeats the cover, the first [NOTE_PICTURE_LIMIT] of them, one above each insight.
 * Staff see here what a reader will see, so a change to the app's rule has to land in both places.
 */
export function notePictures(
  coverImageUrl: string | null,
  images: readonly CuratorNoteImage[],
  insightCount: number,
): NotePictures {
  const coverUrl = coverImageUrl?.trim() || null;
  const cover = coverUrl
    ? images.find((image) => image.role === "cover" && image.url === coverUrl) ??
      { url: coverUrl, role: "cover" as const, credit: "", altKo: "", altEn: "" }
    : null;
  const gallery = images.filter((image) => image.role === "gallery" && image.url !== coverUrl);
  const shown = Math.min(gallery.length, insightCount, NOTE_PICTURE_LIMIT);
  return {
    cover,
    placed: gallery.slice(0, shown).map((image, index) => ({ image, insight: index + 1 })),
    unused: gallery.slice(shown),
    withoutPicture: Array.from({ length: Math.max(0, insightCount - shown) }, (_, index) => shown + index + 1),
  };
}
