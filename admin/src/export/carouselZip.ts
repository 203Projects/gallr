import { zipSync } from "fflate";
import { slideFileName, type CarouselLayout, type CarouselSlide } from "./carousel";

export interface RenderedSlide {
  slide: CarouselSlide;
  png: Uint8Array;
}

/** Reads width and height from a PNG's IHDR chunk; null when the bytes are not a PNG. */
export function pngDimensions(bytes: Uint8Array): { width: number; height: number } | null {
  const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (bytes.length < 24 || signature.some((value, index) => bytes[index] !== value)) return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return { width: view.getUint32(16), height: view.getUint32(20) };
}

export function carouselArchiveName(exhibitionId: string, versionNumber: number, language: string): string {
  return `curator-note-${exhibitionId}-v${versionNumber}-${language}.zip`;
}

/** An exhibition carousel exported without a note. */
export function exhibitionCarouselArchiveName(exhibitionId: string, language: string): string {
  return `exhibition-carousel-${exhibitionId}-${language}.zip`;
}

/** Slides in order, the caption, and a report only when something was left out; entries are stored, not deflated. */
export function buildCarouselArchive(layout: CarouselLayout, slides: RenderedSlide[]): Uint8Array {
  const entries: Record<string, Uint8Array> = {};
  slides.forEach((rendered, index) => {
    entries[slideFileName(index, rendered.slide)] = rendered.png;
  });
  const encoder = new TextEncoder();
  entries["caption.txt"] = encoder.encode(layout.caption);
  if (layout.omitted.length > 0) {
    entries["report.txt"] = encoder.encode(
      ["Left out of the carousel (text that did not fit its slide, or pictures that could not be loaded):", "", ...layout.omitted].join("\n"),
    );
  }
  return zipSync(entries, { level: 0 });
}
