// Slug helpers for exhibition URLs.
// Korean characters are preserved as-is; browsers + Eleventy handle them via
// percent-encoding at link time. We deliberately do not transliterate Korean
// to ASCII — round-tripping is not lossless and the percent-encoded form
// is universally supported.

function slugify(input) {
  if (!input) return "";
  return String(input)
    .toLowerCase()
    .normalize("NFKC")
    // Replace anything that is NOT a letter/digit/hyphen with a space.
    // The unicode property escape \p{L} matches Korean and other scripts.
    .replace(/[^\p{L}\p{N}-]+/gu, " ")
    .trim()
    .replace(/\s+/g, "-")
    // Collapse repeated hyphens
    .replace(/-+/g, "-");
}

function buildSlug({ name_en, name_ko, id }) {
  const base = slugify(name_en || name_ko || "");
  const suffix = String(id).slice(0, 4);
  return base ? `${base}-${suffix}` : suffix;
}

module.exports = { slugify, buildSlug };
