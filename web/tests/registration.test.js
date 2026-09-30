const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const {
  validateDraft,
  validatePoster,
  readRegistration,
  statusLabel,
} = require("../registration/domain.js");

const draft = {
  name_ko: "전시",
  artists: "작가",
  opening_date: "2026-10-01",
  closing_date: "2026-10-31",
  description_ko: "소개",
  venue_name_ko: "대관 공간",
  address_ko: "서울 종로구",
  hours: "10:00–18:00",
  submitter_name: "작가",
  relationship: "artist",
  contact_email: "artist@example.com",
};
test("validates organizer intake without a gallery identity", () => {
  assert.equal(validateDraft(draft), null);
  assert.equal(
    validateDraft({ ...draft, closing_date: "2026-09-01" }),
    "dates",
  );
  assert.equal(
    validateDraft({ ...draft, opening_date: "2026-02-30" }),
    "dates",
  );
  assert.equal(
    validateDraft({ ...draft, relationship: "visitor" }),
    "relationship",
  );
  assert.equal(validateDraft({ ...draft, contact_email: "bad" }), "contact");
  assert.equal(
    validateDraft({ ...draft, name_ko: "x".repeat(301) }),
    "details",
  );
});
test("bounds poster format and size before reserving upload", () => {
  assert.equal(
    validatePoster({ type: "image/png", size: 100, name: "poster.png" }),
    null,
  );
  for (const file of [
    { type: "image/svg+xml", size: 100, name: "x.svg" },
    { type: "image/png", size: 0, name: "x.png" },
    { type: "image/png", size: 10485761, name: "x.png" },
  ])
    assert.equal(validatePoster(file), "poster");
});
test("rejects unknown or inconsistent server responses", () => {
  const record = {
    id: "00000000-0000-4000-8000-000000000001",
    status: "accepted",
    published: false,
    payload: draft,
    review_notes: "",
    submitted_at: "2026-09-30T00:00:00Z",
    exhibition_id: null,
  };
  assert.equal(readRegistration(record).published, false);
  assert.throws(() => readRegistration({ ...record, status: "published" }));
  assert.throws(() => readRegistration({ ...record, published: "false" }));
  assert.throws(() =>
    readRegistration({ ...record, status: "submitted", published: true }),
  );
  assert.equal(statusLabel(record, "ko"), "승인됨 · 공개 준비 중");
  assert.equal(statusLabel({ ...record, published: true }, "ko"), "공개 완료");
  assert.equal(statusLabel({ ...record, status: "rejected" }, "ko"), "반려");
});
test("all public entry points distinguish exhibition registration from gallery management", () => {
  const base = fs.readFileSync(
    path.join(__dirname, "../_includes/base.html"),
    "utf8",
  );
  const home = fs.readFileSync(
    path.join(__dirname, "../_includes/submit-exhibition.html"),
    "utf8",
  );
  const chooser = fs.readFileSync(
    path.join(__dirname, "../submit/index.html"),
    "utf8",
  );
  assert.match(base, /href="\/submit\/" class="site-nav__link"/);
  for (const html of [home, chooser]) {
    assert.match(html, /전시 등록/);
    assert.match(html, /갤러리 등록/);
    assert.match(html, /\/submit\/exhibition\//);
    assert.match(html, /site.galleryWorkspaceUrl/);
  }
  assert.match(chooser, /갤러리 등록 없이/);
});
