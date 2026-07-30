const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");

const {
  validateSubmission,
  composeReception,
} = require("../submit/submit.js");

function validFields(overrides = {}) {
  return {
    name_ko: "전시 제목",
    venue_name_ko: "갤러리",
    opening_date: "2026-06-01",
    closing_date: "2026-06-30",
    address_ko: "서울시 종로구",
    hours: "10am - 6pm Tuesday - Sunday",
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

// --- happy path: no reception fields and no images → still valid.
//     Reception and photos are optional; supplied photos are still validated. ---
{
  const result = validateSubmission(validFields(), []);
  assert.equal(result.valid, true);
  assert.deepEqual(result.errors, {});
}

// --- opening_time is gone: supplying it must NOT be required, and its
//     absence must not produce an error (drift-guard covers the lists). ---
{
  const result = validateSubmission(validFields(), []);
  assert.equal(result.errors.opening_time, undefined);
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
  const result = validateSubmission(validFields(), [image({ size: 6 * 1024 * 1024 + 1 })]);
  assert.equal(result.valid, false);
  assert.equal(result.errors.images, "too_large");
}

// --- Fix 3 conditional-required: reception date filled → start hour required ---
{
  // date present, no start hour → error keyed to the start-time group
  const result = validateSubmission(
    validFields({ reception_date_day: "2026-06-05", reception_start_h: "" }),
    []
  );
  assert.equal(result.valid, false);
  assert.equal(result.errors.reception_start, "required");
}

{
  // date present + start hour present → valid (minute/ampm have defaults)
  const result = validateSubmission(
    validFields({ reception_date_day: "2026-06-05", reception_start_h: "6", reception_start_ampm: "PM" }),
    []
  );
  assert.equal(result.valid, true);
  assert.equal(result.errors.reception_start, undefined);
}

{
  // date empty + a stray start hour → still valid (all reception fields optional)
  const result = validateSubmission(
    validFields({ reception_date_day: "", reception_start_h: "6" }),
    []
  );
  assert.equal(result.valid, true);
}

{
  // out-of-range hour is rejected even when the field is otherwise required
  const result = validateSubmission(
    validFields({ reception_date_day: "2026-06-05", reception_start_h: "13", reception_start_ampm: "PM" }),
    []
  );
  assert.equal(result.valid, false);
  assert.equal(result.errors.reception_start, "invalid_time");
}

// --- composeReception: date + 12h start/AMPM → ISO-ish reception_date string ---
{
  // 6:00 PM on 2026-06-05 → 18:00 local. We assert the date + 24h time prefix,
  // not the timezone suffix (that varies by runner).
  const out = composeReception({
    reception_date_day: "2026-06-05",
    reception_start_h: "6",
    reception_start_m: "00",
    reception_start_ampm: "PM",
    reception_end_h: "8",
    reception_end_m: "30",
    reception_end_ampm: "PM",
  });
  assert.equal(out.reception_date, "2026-06-05T18:00");
  assert.equal(out.reception_end, "2026-06-05T20:30");
}

{
  // 12 AM → 00:xx, 12 PM → 12:xx (the 12-hour edge cases)
  const midnight = composeReception({ reception_date_day: "2026-01-02", reception_start_h: "12", reception_start_m: "15", reception_start_ampm: "AM" });
  assert.equal(midnight.reception_date, "2026-01-02T00:15");
  const noon = composeReception({ reception_date_day: "2026-01-02", reception_start_h: "12", reception_start_m: "00", reception_start_ampm: "PM" });
  assert.equal(noon.reception_date, "2026-01-02T12:00");
}

{
  // no date → both empty; minute defaults to 00 when start hour given with a date
  assert.deepEqual(composeReception({ reception_date_day: "" }), { reception_date: "", reception_end: "" });
  const defMin = composeReception({ reception_date_day: "2026-06-05", reception_start_h: "9", reception_start_ampm: "AM" });
  assert.equal(defMin.reception_date, "2026-06-05T09:00");
  // end omitted → empty even with a date
  assert.equal(defMin.reception_end, "");
}

{
  const html = fs.readFileSync(path.resolve(__dirname, "../submit/index.html"), "utf8");
  assert.equal(/<input[^>]+name="images"[^>]+required/.test(html), false);
  assert.equal(/사진 첨부[^<]*<span class="submit-req"/.test(html), false);
  assert.equal(html.includes('name="website"'), true);
  assert.equal(html.includes("data-token="), false);
}

{
  const script = fs.readFileSync(path.resolve(__dirname, "../submit/submit.js"), "utf8");
  assert.equal(script.includes("readAsDataURL"), false);
  assert.equal(script.includes('body: JSON.stringify'), false);
  assert.equal(script.includes("body,"), true);
}

console.log("[submit-validation.test] all tests passed");
