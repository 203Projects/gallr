// Network-free tests for the venue registry. Run: node --test scripts/venue-registry/venue-registry.test.mjs
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { registryProblems, run } from "./venue-registry.mjs";

function venue(overrides = {}) {
  return {
    name_ko: "우양미술관",
    name_en: "Wooyang Art Museum",
    kind: "museum",
    city_ko: "경북",
    region_ko: "경주시",
    website: "https://www.wooyangmuseum.org/",
    exhibitions_page: "https://www.wooyangmuseum.org/current",
    instagram: "https://www.instagram.com/wooyangmuseum/",
    facebook: null,
    youtube: null,
    naver_blog: "https://blog.naver.com/wymuseum",
    x: null,
    other_links: [],
    status: "active",
    last_checked: "2026-11-04",
    notes: "",
    ...overrides,
  };
}

const registryText = (venues) => JSON.stringify({ format_version: 1, venues });
const gallery = venue({
  name_ko: "갤러리현대",
  name_en: "Gallery Hyundai",
  kind: "gallery",
  city_ko: "서울",
  region_ko: "종로구",
  website: "https://www.galleryhyundai.com/",
  exhibitions_page: null,
  instagram: null,
  naver_blog: null,
  last_checked: null,
});
const valid = registryText([gallery, venue()]);

test("the committed venues.json is valid", () => {
  const text = readFileSync(new URL("./venues.json", import.meta.url), "utf8");
  const result = run(["check"], { registryText: text });
  assert.equal(result.exitCode, 0, result.stderr);
});

test("check accepts a valid registry", () => {
  const result = run(["check"], { registryText: valid });
  assert.equal(result.exitCode, 0, result.stderr);
  assert.equal(result.stdout, "venues.json is valid: 2 venues\n");
});

test("check reports every problem with the venue name", () => {
  const problems = registryProblems(
    JSON.parse(
      registryText([
        venue({
          website: "wooyangmuseum.org",
          instagram: "https://instagram.com/wooyangmuseum",
          kind: "cafe",
          last_checked: "2026-02-30",
          other_links: ["ftp://example.com"],
        }),
      ]),
    ),
  );
  assert.deepEqual(problems, [
    "우양미술관: kind must be one of museum, gallery, art_space, foundation, other",
    "우양미술관: website must be null or an http(s) URL",
    "우양미술관: instagram must look like https://www.instagram.com/<handle>/",
    "우양미술관: other_links must be a list of http(s) URLs",
    "우양미술관: last_checked must be null or YYYY-MM-DD",
  ]);
});

test("check rejects missing and unknown keys, duplicates and unsorted names", () => {
  const { notes, ...withoutNotes } = venue();
  const problems = registryProblems(
    JSON.parse(registryText([venue(), { ...withoutNotes, tiktok: null }, gallery])),
  );
  assert.deepEqual(problems, [
    "우양미술관: missing notes",
    "우양미술관: unknown tiktok",
    "우양미술관: notes must be a string",
    "우양미술관: listed twice",
    "갤러리현대: out of order (sort by name_ko)",
  ]);
  assert.equal(notes, "");
});

test("check fails with exit 2 on malformed JSON or a wrong format version", () => {
  assert.equal(run(["check"], { registryText: "{" }).exitCode, 2);
  const wrongVersion = run(["check"], { registryText: JSON.stringify({ format_version: 2, venues: [] }) });
  assert.equal(wrongVersion.exitCode, 2);
  assert.match(wrongVersion.stderr, /format_version must be 1/);
});

test("diff lists catalogue venues missing from the registry and exits 1", () => {
  const catalogue = JSON.stringify({
    rows: [
      { venue_name_ko: "갤러리현대 ", city_ko: "서울", region_ko: "종로구" },
      { venue_name_ko: "국제갤러리", city_ko: "서울", region_ko: "종로구" },
    ],
  });
  const result = run(["diff"], { registryText: valid, stdinText: catalogue });
  assert.equal(result.exitCode, 1);
  assert.equal(
    result.stdout,
    [
      "Catalogue venues missing from venues.json: 1",
      "- 국제갤러리 (서울 종로구)",
      "",
      "Tracked venues with no catalogue exhibition yet: 1",
      "- 우양미술관",
      "",
    ].join("\n"),
  );
});

test("diff exits 0 when every catalogue venue is tracked, and 2 on bad input", () => {
  const catalogue = JSON.stringify({ rows: [{ venue_name_ko: "갤러리현대" }, { venue_name_ko: "우양미술관" }] });
  assert.equal(run(["diff"], { registryText: valid, stdinText: catalogue }).exitCode, 0);
  assert.equal(run(["diff"], { registryText: valid, stdinText: "[]" }).exitCode, 2);
});

test("sweep lists unchecked venues first, with their links, and leaves out closed venues", () => {
  const closed = venue({ name_ko: "폐관갤러리", status: "closed", last_checked: null });
  const result = run(["sweep"], { registryText: registryText([gallery, venue(), closed]) });
  assert.equal(result.exitCode, 0, result.stderr);
  assert.equal(
    result.stdout,
    [
      "- [ ] **갤러리현대** (Gallery Hyundai) · gallery · 서울 종로구 · checked never — [site](https://www.galleryhyundai.com/)",
      "- [ ] **우양미술관** (Wooyang Art Museum) · museum · 경북 경주시 · checked 2026-11-04 — " +
        "[site](https://www.wooyangmuseum.org/) · [exhibitions](https://www.wooyangmuseum.org/current) · " +
        "[Instagram](https://www.instagram.com/wooyangmuseum/) · [blog](https://blog.naver.com/wymuseum)",
      "",
    ].join("\n"),
  );
});

test("sweep filters by kind and city and honours the limit", () => {
  assert.match(run(["sweep", "--kind", "museum"], { registryText: valid }).stdout, /^- \[ \] \*\*우양미술관\*\*[^\n]*\n$/);
  assert.match(run(["sweep", "--city", "서울"], { registryText: valid }).stdout, /^- \[ \] \*\*갤러리현대\*\*[^\n]*\n$/);
  assert.equal(run(["sweep", "--limit", "1"], { registryText: valid }).stdout.split("\n").length, 2);
  assert.equal(run(["sweep", "--kind", "cafe"], { registryText: valid }).exitCode, 2);
  assert.equal(run(["sweep", "--limit", "0"], { registryText: valid }).exitCode, 2);
});

test("mark-checked returns the updated registry and rejects unknown names", () => {
  const result = run(["mark-checked", "--date", "2026-11-05", "갤러리현대"], { registryText: valid });
  assert.equal(result.exitCode, 0, result.stderr);
  const updated = JSON.parse(result.write);
  assert.equal(updated.venues[0].last_checked, "2026-11-05");
  assert.equal(updated.venues[1].last_checked, "2026-11-04");

  const unknown = run(["mark-checked", "--date", "2026-11-05", "없는갤러리"], { registryText: valid });
  assert.equal(unknown.exitCode, 2);
  assert.equal(unknown.write, null);
  assert.equal(run(["mark-checked", "--date", "2026-13-01", "갤러리현대"], { registryText: valid }).exitCode, 2);
});

test("an unknown command exits 2 with usage", () => {
  const result = run(["publish"], { registryText: valid });
  assert.equal(result.exitCode, 2);
  assert.match(result.stderr, /^unknown command publish\nUsage:/);
});
