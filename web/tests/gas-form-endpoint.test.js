const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const source = fs.readFileSync(path.resolve(__dirname, "../../gas/FormEndpoint.gs"), "utf8");
const sandbox = {
  module: { exports: {} },
  console,
  Utilities: {
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
  responseJson,
} = sandbox.module.exports;

const fields = {
  name_ko: "전시 제목",
  venue_name_ko: "갤러리",
  opening_date: "2026-06-01",
  closing_date: "2026-06-30",
  address_ko: "서울시 종로구",
  opening_time: "10:00 AM",
  hours: "Tue-Sun 10:00-18:00",
  contact: "gallery@example.com",
  name_en: "Show Title",
};

{
  const result = validateFormPayload({ fields, images: [{ base64: "QUJD", contentType: "image/jpeg", name: "a.jpg" }] });
  assert.equal(result.valid, true);
  assert.equal(result.error, null);
}

{
  const result = validateFormPayload({ fields: { ...fields, contact: "bad" }, images: [] });
  assert.equal(result.valid, false);
  assert.equal(result.error, "contact invalid");
}

{
  const headers = ["status", "name_ko", "name_en", "contact", "image_url_1", "image_url_2"];
  const row = buildSubmissionRow(headers, fields, ["https://cdn.example/1.jpg"]);
  assert.deepEqual(row, [
    "pending",
    "전시 제목",
    "Show Title",
    "",
    "https://cdn.example/1.jpg",
    "",
  ]);
}

{
  const response = responseJson({ success: true });
  assert.equal(response.content, JSON.stringify({ success: true }));
  assert.equal(response.mimeType, "application/json");
}

console.log("[gas-form-endpoint.test] all tests passed");
