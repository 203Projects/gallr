import type { OwnerExhibition } from "./domain";

// Keep this URL contract aligned with web/scripts/lib/slug.js. The public site
// derives human-readable paths at build time from the published record.
export function publicExhibitionSlug(
  exhibition: Pick<OwnerExhibition, "id" | "nameEn" | "nameKo">,
): string {
  const base = (exhibition.nameEn || exhibition.nameKo || "")
    .toLowerCase()
    .normalize("NFKC")
    .replace(/[^\p{L}\p{N}-]+/gu, " ")
    .trim()
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-");
  const suffix = exhibition.id.slice(0, 4);
  return base ? `${base}-${suffix}` : suffix;
}

export function publicExhibitionUrl(
  exhibition: Pick<OwnerExhibition, "id" | "nameEn" | "nameKo">,
): string {
  return new URL(
    `/exhibitions/${publicExhibitionSlug(exhibition)}/`,
    "https://gallrmap.com",
  ).toString();
}
