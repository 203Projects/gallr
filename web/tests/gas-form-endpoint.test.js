const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const source = fs.readFileSync(path.resolve(__dirname, "../../gas/FormEndpoint.gs"), "utf8");
const sandbox = {
  module: { exports: {} },
  console,
  Utilities: {
    // Node Buffer indexes 0-255 and has .length; FormEndpoint.gs masks with
    // & 0xff so this stands in for Apps Script base64Decode.
    base64Decode: (value) => Buffer.from(value, "base64"),
    newBlob: (bytes, contentType, name) => ({ bytes, contentType, name }),
    getUuid: () => "uuid-1",
  },
};

vm.createContext(sandbox);
vm.runInContext(source, sandbox);

const {
  validateFormPayload,
  buildSubmissionRow,
  escapeSheetValue,
  imageBytesError,
  magicBytesMatch,
  responseJson,
} = sandbox.module.exports;

// Minimal valid image headers (real magic bytes), base64-encoded.
const JPEG_B64 = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]).toString("base64");
const PNG_B64 = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).toString("base64");

const fields = {
  name_ko: "전시 제목",
  venue_name_ko: "갤러리",
  opening_date: "2026-06-01",
  closing_date: "2026-06-30",
  address_ko: "서울시 종로구",
  hours: "10am - 6pm Tuesday - Sunday",
  contact: "gallery@example.com",
  name_en: "Show Title",
};

// --- validateFormPayload happy path (now requires real magic bytes) ---
{
  const result = validateFormPayload({ fields, images: [{ base64: JPEG_B64, contentType: "image/jpeg", name: "a.jpg" }] });
  assert.equal(result.valid, true);
  assert.equal(result.error, null);
}

// --- validateFormPayload branch coverage ---
{
  assert.equal(validateFormPayload(null).error, "invalid payload");

  const missing = validateFormPayload({ fields: { ...fields, name_ko: "  " }, images: [{ base64: JPEG_B64, contentType: "image/jpeg" }] });
  assert.equal(missing.error, "name_ko required");

  const badEmail = validateFormPayload({ fields: { ...fields, contact: "bad" }, images: [] });
  assert.equal(badEmail.error, "contact invalid");

  const dateOrder = validateFormPayload({
    fields: { ...fields, opening_date: "2026-07-01", closing_date: "2026-06-01" },
    images: [{ base64: JPEG_B64, contentType: "image/jpeg" }],
  });
  assert.equal(dateOrder.error, "closing_date before opening_date");

  const noImages = validateFormPayload({ fields, images: [] });
  assert.equal(noImages.error, "images required");

  const tooMany = validateFormPayload({
    fields,
    images: Array.from({ length: 6 }, () => ({ base64: JPEG_B64, contentType: "image/jpeg" })),
  });
  assert.equal(tooMany.error, "too many images");

  const badType = validateFormPayload({ fields, images: [{ base64: JPEG_B64, contentType: "image/gif" }] });
  assert.equal(badType.error, "invalid image");

  // Spoofed: declares png but bytes are jpeg → magic-byte mismatch.
  const spoofed = validateFormPayload({ fields, images: [{ base64: JPEG_B64, contentType: "image/png" }] });
  assert.equal(spoofed.error, "invalid image");

  // Equal opening/closing dates are allowed.
  const equalDates = validateFormPayload({
    fields: { ...fields, opening_date: "2026-06-01", closing_date: "2026-06-01" },
    images: [{ base64: PNG_B64, contentType: "image/png" }],
  });
  assert.equal(equalDates.valid, true);
}

// --- imageBytesError / magicBytesMatch ---
{
  assert.equal(imageBytesError({ base64: JPEG_B64, contentType: "image/jpeg" }), null);
  assert.equal(imageBytesError({ base64: PNG_B64, contentType: "image/png" }), null);
  assert.equal(imageBytesError({ base64: Buffer.from("not an image").toString("base64"), contentType: "image/jpeg" }), "invalid image");

  const huge = "A".repeat(12 * 1024 * 1024); // ~9MB decoded, over the 8MB cap
  assert.equal(imageBytesError({ base64: huge, contentType: "image/jpeg" }), "image too large");

  assert.equal(magicBytesMatch([0xff, 0xd8, 0xff, 0x00], "image/jpeg"), true);
  assert.equal(magicBytesMatch([0x89, 0x50, 0x4e, 0x47], "image/png"), true);
  assert.equal(magicBytesMatch([0x00, 0x00, 0x00, 0x00], "image/jpeg"), false);
}

// --- escapeSheetValue (formula-injection neutralization) ---
{
  assert.equal(escapeSheetValue('=HYPERLINK("http://evil")'), '\'=HYPERLINK("http://evil")');
  assert.equal(escapeSheetValue("+1"), "'+1");
  assert.equal(escapeSheetValue("-2"), "'-2");
  assert.equal(escapeSheetValue("@x"), "'@x");
  assert.equal(escapeSheetValue("\t=cmd"), "'\t=cmd");
  assert.equal(escapeSheetValue("전시 제목"), "전시 제목"); // safe value untouched
  assert.equal(escapeSheetValue(""), "");
}

// --- buildSubmissionRow: allowlist, status, contact strip, escaping ---
{
  const headers = ["status", "name_ko", "name_en", "contact", "reception_date", "reception_end", "unknown_col", "image_url_1", "image_url_2"];
  const row = buildSubmissionRow(
    headers,
    { ...fields, name_ko: "=BAD()", reception_date: "2026-06-05T18:00", reception_end: "2026-06-05T20:30" },
    ["https://cdn.example/1.jpg"]
  );
  assert.deepEqual(row, [
    "pending",
    "'=BAD()",                // formula-escaped
    "Show Title",
    "",                        // contact stripped (private)
    "2026-06-05T18:00",        // composed reception start passes through
    "2026-06-05T20:30",        // reception end (optional, sheet-only) passes through
    "",                        // unknown header → empty
    "https://cdn.example/1.jpg",
    "",                        // image_url_2 with only one URL → empty
  ]);
}

// --- responseJson ---
{
  const response = responseJson({ success: true });
  assert.equal(response.content, JSON.stringify({ success: true }));
  assert.equal(response.mimeType, "application/json");
}

// --- drift guard: client REQUIRED_FIELDS must match server SUBMISSION_REQUIRED_FIELDS ---
{
  const clientSrc = fs.readFileSync(path.resolve(__dirname, "../submit/submit.js"), "utf8");
  const grab = (src, name) => {
    const m = src.match(new RegExp(name + "\\s*=\\s*\\[([\\s\\S]*?)\\]"));
    if (!m) throw new Error("could not find " + name);
    return m[1]
      .split(",")
      .map((s) => s.trim().replace(/^['"]|['"]$/g, ""))
      .filter(Boolean)
      .sort();
  };
  const clientRequired = grab(clientSrc, "REQUIRED_FIELDS");
  const serverRequired = grab(source, "SUBMISSION_REQUIRED_FIELDS");
  assert.deepEqual(
    clientRequired,
    serverRequired,
    "submit.js REQUIRED_FIELDS drifted from FormEndpoint.gs SUBMISSION_REQUIRED_FIELDS"
  );
}

console.log("[gas-form-endpoint.test] all tests passed");
