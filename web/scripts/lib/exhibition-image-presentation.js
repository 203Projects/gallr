// Reviewed display requirements for an exact promotional asset. Do not carry
// its background or no-crop policy onto a replacement cover automatically.
const MICA_ID = "075d8567-73c5-47e8-8171-a95b85f036c5";
const MICA_COVER = "https://oqrvbstopuppznxqoonp.supabase.co/storage/v1/object/public/exhibition-images/cms/847527a1-d780-4353-a02b-5653cd68eaee/original.jpg";

/** Return reviewed web display metadata, or null for the normal cover style. */
function exhibitionImagePresentation(exhibition) {
  if (exhibition?.id !== MICA_ID || exhibition.cover_image_url !== MICA_COVER) return null;
  // Source background converted from its embedded Adobe RGB profile to sRGB.
  return { fit: "contain", background: "#ead477" };
}

module.exports = { exhibitionImagePresentation };
