// Keep this URL contract aligned with web/scripts/lib/slug.js and gallery/src/publicExhibitionUrl.ts. The
// public site derives human-readable paths at build time from the published record.
export function publicExhibitionSlug(exhibition: { id: string; nameEn?: string; nameKo?: string }): string {
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
  exhibition: { id: string; nameEn?: string; nameKo?: string },
  publicSiteUrl = "https://gallrmap.com",
): string {
  return new URL(`/exhibitions/${publicExhibitionSlug(exhibition)}/`, publicSiteUrl).toString();
}
