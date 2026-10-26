const assert = require("node:assert/strict");
const { exhibitionImagePresentation } = require("../scripts/lib/exhibition-image-presentation.js");

const mica = {
  id: "075d8567-73c5-47e8-8171-a95b85f036c5",
  cover_image_url: "https://oqrvbstopuppznxqoonp.supabase.co/storage/v1/object/public/exhibition-images/cms/847527a1-d780-4353-a02b-5653cd68eaee/original.jpg",
};

assert.deepEqual(exhibitionImagePresentation(mica), { fit: "contain", background: "#ead477" });
assert.equal(exhibitionImagePresentation({ ...mica, id: "another-show" }), null);
assert.equal(exhibitionImagePresentation({ ...mica, cover_image_url: "https://example.com/replacement.jpg" }), null);
assert.equal(exhibitionImagePresentation({ ...mica, cover_image_url: null }), null);
assert.equal(exhibitionImagePresentation(null), null);
console.log("[exhibition-image-presentation.test] passed");
