const assert = require("assert").strict;

const {
  validateSubmission,
  fileToPayload,
} = require("../submit/submit.js");

function validFields(overrides = {}) {
  return {
    name_ko: "전시 제목",
    venue_name_ko: "갤러리",
    opening_date: "2026-06-01",
    closing_date: "2026-06-30",
    address_ko: "서울시 종로구",
    opening_time: "10:00 AM",
    hours: "Tue-Sun 10:00-18:00",
    contact: "gallery@example.com",
    ...overrides,
  };
}

function image(overrides = {}) {
  return {
    name: "cover.jpg",
    type: "image/jpeg",
    size: 1024,
    ...overrides,
  };
}

{
  const result = validateSubmission(validFields(), [image()]);
  assert.equal(result.valid, true);
  assert.deepEqual(result.errors, {});
}

{
  const result = validateSubmission(validFields({ name_ko: "" }), [image()]);
  assert.equal(result.valid, false);
  assert.equal(result.errors.name_ko, "required");
}

{
  const result = validateSubmission(
    validFields({ opening_date: "2026-07-01", closing_date: "2026-06-30" }),
    [image()]
  );
  assert.equal(result.valid, false);
  assert.equal(result.errors.closing_date, "before_opening_date");
}

{
  const result = validateSubmission(validFields({ contact: "not-an-email" }), [image()]);
  assert.equal(result.valid, false);
  assert.equal(result.errors.contact, "invalid_email");
}

{
  const result = validateSubmission(validFields(), []);
  assert.equal(result.valid, false);
  assert.equal(result.errors.images, "required");
}

{
  const result = validateSubmission(validFields(), Array.from({ length: 6 }, (_, i) => image({ name: `${i}.png`, type: "image/png" })));
  assert.equal(result.valid, false);
  assert.equal(result.errors.images, "too_many");
}

{
  const result = validateSubmission(validFields(), [image({ type: "image/gif" })]);
  assert.equal(result.valid, false);
  assert.equal(result.errors.images, "invalid_type");
}

{
  const result = validateSubmission(validFields(), [image({ size: 10 * 1024 * 1024 + 1 })]);
  assert.equal(result.valid, false);
  assert.equal(result.errors.images, "too_large");
}

{
  const payload = fileToPayload({
    name: "poster.png",
    type: "image/png",
    dataUrl: "data:image/png;base64,QUJD",
  });
  assert.deepEqual(payload, {
    name: "poster.png",
    contentType: "image/png",
    base64: "QUJD",
  });
}

console.log("[submit-validation.test] all tests passed");
