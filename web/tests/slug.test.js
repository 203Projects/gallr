const assert = require("assert").strict;
const { buildSlug, slugify } = require("../scripts/lib/slug.js");

// Slugify primitives
assert.equal(slugify("Void Forms"),                "void-forms");
assert.equal(slugify("Line & Form"),               "line-form");
assert.equal(slugify("  Trim   Spaces  "),         "trim-spaces");
assert.equal(slugify("Already-hyphen"),            "already-hyphen");
assert.equal(slugify("UPPERCASE"),                 "uppercase");
assert.equal(slugify(""),                          "");

// Korean input: keep it as-is (URL-safe via percent-encoding at link time)
// but normalize whitespace and strip punctuation. We don't transliterate.
assert.equal(slugify("한국 단색화의 계보"),         "한국-단색화의-계보");
assert.equal(slugify("VOID — FORMS"),              "void-forms");

// buildSlug composes slugify(en ?? ko) + "-" + first 4 chars of id
const id = "abcd1234-5678-9012-3456-789012345678";
assert.equal(buildSlug({ name_en: "Void Forms", name_ko: "보이드 폼", id }), "void-forms-abcd");
assert.equal(buildSlug({ name_en: null, name_ko: "보이드 폼", id }),         "보이드-폼-abcd");
assert.equal(buildSlug({ name_en: "",   name_ko: "보이드 폼", id }),         "보이드-폼-abcd");
assert.equal(buildSlug({ name_en: "Void Forms", name_ko: null, id }),        "void-forms-abcd");

// Collision-resilience: same name, different ids → different slugs
const a = buildSlug({ name_en: "Annual Show", name_ko: null, id: "1111aaaa-..." });
const b = buildSlug({ name_en: "Annual Show", name_ko: null, id: "2222bbbb-..." });
assert.notEqual(a, b);
assert.equal(a, "annual-show-1111");
assert.equal(b, "annual-show-2222");

console.log("[slug.test] all tests passed");
