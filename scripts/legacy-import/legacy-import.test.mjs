import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  COMPATIBILITY_FIELDS,
  buildDryRun,
  gasGeneratedId,
  normalizeLegacyRow,
  parseArgs,
  parseCsv,
  parseLegacyJson,
  runCli,
  validateNormalizedRows,
} from "./legacy-import.mjs";

const HASH = "0".repeat(64);
const NOW = new Date("2026-07-21T08:00:00.000Z");

function legacyRow(overrides = {}) {
  const base = {
    name_ko: "전시이름",
    name_en: "Exhibition",
    venue_name_ko: "갤러리이름",
    venue_name_en: "Gallery",
    city_ko: "서울",
    city_en: "Seoul",
    region_ko: "종로구",
    region_en: "Jongno-gu",
    opening_date: "2026-04-01",
    closing_date: "2026-05-01",
    is_featured: false,
    latitude: 37.57,
    longitude: 126.98,
    description_ko: "설명",
    description_en: "Description",
    address_ko: "서울시 종로구",
    address_en: "Jongno-gu, Seoul",
    cover_image_url: "https://example.test/cover.jpg",
    hours: null,
    contact: null,
    reception_date: null,
    opening_time: null,
    event_id: null,
    editor_id: null,
    is_homepage_featured: false,
    ticket_url: null,
    updated_at: "2026-07-20T12:00:00.000Z",
  };
  base.id = gasGeneratedId(
    base.name_ko,
    base.venue_name_ko,
    base.city_ko,
    base.opening_date,
  );
  return { ...base, ...overrides };
}

function entries(rows, offset = 1) {
  return rows.map((raw, index) => ({
    raw,
    row: normalizeLegacyRow(raw, index + offset),
  }));
}

test("GAS diagnostic hash matches the current four-field first-8-byte contract", () => {
  assert.equal(
    gasGeneratedId("전시이름", "갤러리이름", "서울", "2026-04-01"),
    "a05df2e502291128",
  );
  assert.equal(
    gasGeneratedId("전시이름", "갤러리이름", "서울", "2026-04-01"),
    gasGeneratedId("전시이름", "갤러리이름", "서울", "2026-04-01"),
  );
});

test("normalization preserves the database id and emits exactly 29 staged fields", () => {
  const normalized = normalizeLegacyRow(
    legacyRow({
      id: " AUTHORITATIVE-ID ",
      name_en: null,
      hours: "   ",
      is_featured: "yes",
      latitude: "37.5",
    }),
    42,
  );

  assert.equal(normalized.id, " AUTHORITATIVE-ID ");
  assert.equal(normalized.source_row_number, 42);
  assert.equal(normalized.name_en, "");
  assert.equal(normalized.hours, null);
  assert.equal(normalized.is_featured, true);
  assert.equal(normalized.latitude, 37.5);
  assert.equal(normalized.diagnostics.gas_id_matches_authoritative, false);
  assert.deepEqual(Object.keys(normalized), ["source_row_number", ...COMPATIBILITY_FIELDS]);
  assert.equal(JSON.stringify(normalized).includes("gas_generated_id"), false);
});

test("validation catches required values, real dates/order, coordinates, URLs, and duplicates", () => {
  const duplicate = legacyRow({ id: "same-id" });
  const invalid = legacyRow({
    id: "same-id",
    name_ko: "",
    opening_date: "2026-03-10",
    closing_date: "2026-03-01",
    latitude: 91,
    longitude: "not-a-number",
    reception_date: "2026-02-29T10:00:00Z",
    cover_image_url: "file:///tmp/cover.jpg",
    ticket_url: "javascript:alert(1)",
    is_featured: "maybe",
    updated_at: "not-a-timestamp",
  });
  const badCalendarDate = legacyRow({
    id: "bad-calendar-date",
    opening_date: "2026-02-29",
    longitude: null,
  });

  const issues = validateNormalizedRows(entries([duplicate, invalid, badCalendarDate]));
  const codes = new Set(issues.map((issue) => issue.code));
  for (const expected of [
    "required_field_missing",
    "invalid_date",
    "invalid_date_order",
    "incomplete_coordinate_pair",
    "coordinate_out_of_range",
    "invalid_number",
    "invalid_boolean",
    "invalid_timestamp",
    "invalid_url_scheme",
    "duplicate_id",
  ]) {
    assert(codes.has(expected), `missing issue code ${expected}`);
  }
  assert.equal(issues.filter((issue) => issue.code === "duplicate_id").length, 2);
  const normalizedInvalid = entries([invalid])[0].row;
  assert.equal(normalizedInvalid.longitude, "not-a-number");
  assert.equal(normalizedInvalid.is_featured, "maybe");
});

test("CSV parser handles BOM, CRLF, escaped quotes, commas, and multiline fields", () => {
  const parsed = parseCsv(
    '\uFEFFname_ko,description_ko,opening_date\r\n"전시, 하나","첫 줄\n둘째 ""인용""",2026.04.01\r\n',
  );
  assert.deepEqual(parsed.headers, ["name_ko", "description_ko", "opening_date"]);
  assert.equal(parsed.rows.length, 1);
  assert.equal(parsed.rows[0].source_row_number, 2);
  assert.equal(parsed.rows[0].name_ko, "전시, 하나");
  assert.equal(parsed.rows[0].description_ko, '첫 줄\n둘째 "인용"');
});

test("CSV headers mirror GAS lowercase/trim normalization and detect normalized duplicates", () => {
  const parsed = parseCsv(" Name_Ko , OPENING_DATE \n전시,2026-04-01\n");
  assert.deepEqual(parsed.headers, ["name_ko", "opening_date"]);
  assert.equal(parsed.rows[0].name_ko, "전시");
  assert.throws(
    () => parseCsv("Name_Ko, name_ko \nA,B\n"),
    /duplicate headers: name_ko/,
  );
});

test("legacy JSON accepts both supported envelopes and rejects other shapes", () => {
  const unwrapped = parseLegacyJson(JSON.stringify([legacyRow()]));
  assert.equal(unwrapped.rows.length, 1);
  assert.equal(unwrapped.sourceSnapshotAt, null);
  const wrapped = parseLegacyJson(
    JSON.stringify({
      source_snapshot_at: "2026-07-20T00:00:00Z",
      rows: [legacyRow()],
    }),
  );
  assert.equal(wrapped.rows.length, 1);
  assert.equal(wrapped.sourceSnapshotAt, "2026-07-20T00:00:00Z");
  assert.throws(() => parseLegacyJson('{"not_rows":[]}'), /"rows" array/);
});

test("an unwrapped export remains inspectable but is never import-ready", () => {
  const parsed = parseLegacyJson(JSON.stringify([legacyRow()]));
  const report = buildDryRun({
    legacyRows: parsed.rows,
    legacySourceFileName: "legacy.json",
    legacySourceSha256: HASH,
    sourceSnapshotAt: parsed.sourceSnapshotAt,
    now: NOW,
  });

  assert.equal(report.bundle.source_snapshot_at, null);
  assert.equal(report.summary.import_ready, false);
  assert(
    report.issues.some((issue) => issue.code === "missing_source_snapshot_at"),
  );
});

test("empty legacy exports and missing required Sheet headers block readiness", () => {
  const report = buildDryRun({
    legacyRows: [],
    legacySourceFileName: "legacy.json",
    legacySourceSha256: HASH,
    sourceSnapshotAt: NOW,
    sheetRows: [],
    sheetHeaders: ["name_ko"],
    sheetSourceFileName: "sheet.csv",
    sheetSourceSha256: "1".repeat(64),
    sheetTimeZone: "America/Los_Angeles",
    now: NOW,
  });
  assert.equal(report.summary.import_ready, false);
  assert(report.issues.some((issue) => issue.code === "empty_legacy_export"));
  assert(report.issues.some((issue) => issue.code === "missing_sheet_header"));
});

test("reconciliation respects the GAS approval gate and reports field differences", () => {
  const first = legacyRow();
  const second = legacyRow({
    name_ko: "두번째 전시",
    id: gasGeneratedId("두번째 전시", "갤러리이름", "서울", "2026-04-01"),
  });
  const csv = parseCsv(
    [
      "name_ko,name_en,venue_name_ko,city_ko,region_ko,opening_date,closing_date,cover_image_url,status",
      "전시이름,Changed English,갤러리이름,서울,종로구,2026-04-01,2026-05-01,cover.jpg,approved",
      "두번째 전시,,갤러리이름,서울,종로구,2026-04-01,2026-05-01,,pending",
      "시트 전용,,갤러리이름,서울,종로구,2026-04-01,2026-05-01,,approved",
    ].join("\n"),
  );

  const report = buildDryRun({
    legacyRows: [first, second],
    legacySourceFileName: "legacy.json",
    legacySourceSha256: HASH,
    sourceSnapshotAt: NOW,
    sheetRows: csv.rows,
    sheetHeaders: csv.headers,
    sheetSourceFileName: "sheet.csv",
    sheetSourceSha256: "1".repeat(64),
    sheetTimeZone: "America/Los_Angeles",
    now: NOW,
  });

  assert.equal(report.summary.counts.mismatched, 1);
  assert.equal(report.summary.counts.legacy_only, 1);
  assert.equal(report.summary.counts.sheet_only, 1);
  assert.equal(report.summary.counts.sheet_rows_excluded_by_approval_gate, 1);
  assert(
    report.issues.some(
      (issue) => issue.code === "sheet_public_field_mismatch" && issue.field === "name_en",
    ),
  );
  assert(
    report.reconciliation.some((entry) => entry.status === "sheet_not_publishable"),
  );
  assert.deepEqual(
    report.reconciliation.find((entry) => entry.status === "mismatched").mismatch_fields,
    ["name_en"],
  );
  assert.equal(
    report.issues.some(
      (issue) =>
        issue.source === "sheet_csv" && ["id", "updated_at"].includes(issue.field),
    ),
    false,
  );
});

test("Sheet reconciliation may locate by GAS diagnostic without replacing database id", () => {
  const csv = parseCsv(
    [
      "name_ko,venue_name_ko,city_ko,region_ko,opening_date,closing_date",
      "전시이름,갤러리이름,서울,종로구,2026-04-01,2026-05-01",
    ].join("\n"),
  );
  const report = buildDryRun({
    legacyRows: [legacyRow({ id: "stable-database-id" })],
    legacySourceFileName: "legacy.json",
    legacySourceSha256: HASH,
    sourceSnapshotAt: NOW,
    sheetRows: csv.rows,
    sheetHeaders: csv.headers,
    sheetSourceFileName: "sheet.csv",
    sheetSourceSha256: "1".repeat(64),
    sheetTimeZone: "America/Los_Angeles",
    now: NOW,
  });

  assert.equal(report.bundle.rows[0].id, "stable-database-id");
  assert.equal(report.summary.counts.matched, 1);
  assert.equal(report.summary.counts.legacy_only, 0);
  assert.equal(report.summary.counts.sheet_only, 0);
  assert.equal(report.reconciliation[0].match_key, "a05df2e502291128");
  assert(report.issues.some((issue) => issue.code === "gas_id_mismatch"));
});

test("Sheet reconciliation uses its calendar timezone and GAS cover filename behavior", () => {
  const csv = parseCsv(
    [
      "name_ko,venue_name_ko,city_ko,region_ko,opening_date,closing_date,reception_date,cover_image_url",
      "전시이름,갤러리이름,서울,종로구,2026-04-01,2026-05-01,2026-06-19,0295_n:a.jpg",
    ].join("\n"),
  );
  const report = buildDryRun({
    legacyRows: [
      legacyRow({
        reception_date: "2026-06-20T01:00:00+00:00",
        cover_image_url:
          "https://example.test/storage/v1/object/public/exhibition-images/0295_n%3Aa.jpg",
      }),
    ],
    legacySourceFileName: "legacy.json",
    legacySourceSha256: HASH,
    sourceSnapshotAt: NOW,
    sheetRows: csv.rows,
    sheetHeaders: csv.headers,
    sheetSourceFileName: "sheet.csv",
    sheetSourceSha256: "1".repeat(64),
    sheetTimeZone: "America/Los_Angeles",
    now: NOW,
  });

  assert.equal(report.summary.counts.matched, 1);
  assert.equal(report.summary.counts.mismatched, 0);
  assert.equal(report.summary.sheet_source.time_zone, "America/Los_Angeles");
  assert.equal(
    report.issues.some(
      (issue) =>
        issue.code === "sheet_public_field_mismatch" ||
        issue.code === "invalid_url_scheme",
    ),
    false,
  );
});

test("build without a Sheet still emits GAS diagnostics in reconciliation", () => {
  const row = legacyRow({ id: "database-id-wins" });
  const report = buildDryRun({
    legacyRows: [row],
    legacySourceFileName: "legacy.json",
    legacySourceSha256: HASH,
    sourceSnapshotAt: NOW,
    now: NOW,
  });
  assert.equal(report.reconciliation[0].status, "not_compared");
  assert.equal(report.reconciliation[0].legacy_id, "database-id-wins");
  assert.match(report.reconciliation[0].legacy_gas_generated_id, /^[a-f0-9]{16}$/);
  assert.equal(Object.keys(report.bundle.rows[0]).length, 29);
});

test("CLI parser is strict", () => {
  assert.deepEqual(parseArgs(["--help"]), { help: true });
  assert.throws(() => parseArgs([]), /--legacy-json is required/);
  assert.throws(
    () => parseArgs(["--legacy-json", "legacy.json", "--wat", "nope"]),
    /Unknown argument/,
  );
  assert.throws(
    () =>
      parseArgs([
        "--legacy-json",
        "legacy.json",
        "--sheet-csv",
        "sheet.csv",
        "--output-dir",
        "review",
      ]),
    /--sheet-timezone is required/,
  );
  assert.throws(
    () =>
      parseArgs([
        "--legacy-json",
        "legacy.json",
        "--sheet-csv",
        "sheet.csv",
        "--sheet-timezone",
        "Not_A_Real_Zone",
        "--output-dir",
        "review",
      ]),
    /Invalid IANA Sheet time zone/,
  );
  assert.throws(
    () =>
      parseArgs([
        "--legacy-json",
        "legacy.json",
        "--sheet-timezone",
        "America/Los_Angeles",
        "--output-dir",
        "review",
      ]),
    /--sheet-timezone requires --sheet-csv/,
  );
});

test("CLI writes exactly the four review artifacts without external services", async (t) => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "gallr-legacy-import-test-"));
  t.after(async () => rm(temp, { recursive: true, force: true }));
  const input = path.join(temp, "legacy.json");
  const output = path.join(temp, "reports");
  await writeFile(
    input,
    JSON.stringify({ source_snapshot_at: "2026-07-20T00:00:00Z", rows: [legacyRow()] }),
    "utf8",
  );

  const result = await runCli(
    ["--legacy-json", input, "--output-dir", output],
    { now: NOW },
  );
  assert.equal(result.report.summary.import_ready, true);
  assert.deepEqual((await readdir(output)).sort(), [
    "bundle.json",
    "issues.csv",
    "reconciliation.csv",
    "summary.json",
  ]);

  const bundle = JSON.parse(await readFile(path.join(output, "bundle.json"), "utf8"));
  assert.equal(bundle.schema_version, 1);
  assert.equal(bundle.source_system, "legacy_public_exhibitions");
  assert.equal(bundle.source_file_name, "legacy.json");
  assert.equal(bundle.source_snapshot_at, "2026-07-20T00:00:00.000Z");
  assert.match(bundle.source_sha256, /^[a-f0-9]{64}$/);
  assert.equal(bundle.row_count, 1);
  assert.deepEqual(Object.keys(bundle.rows[0]), ["source_row_number", ...COMPATIBILITY_FIELDS]);

  const reconciliation = await readFile(path.join(output, "reconciliation.csv"), "utf8");
  assert.match(reconciliation, /not_compared/);
  assert.match(reconciliation, /a05df2e502291128/);
});

test("CLI blocks an unwrapped export instead of inventing a snapshot time", async (t) => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "gallr-legacy-import-test-"));
  t.after(async () => rm(temp, { recursive: true, force: true }));
  const input = path.join(temp, "legacy-array.json");
  const output = path.join(temp, "reports");
  await writeFile(input, JSON.stringify([legacyRow()]), "utf8");

  const result = await runCli(
    ["--legacy-json", input, "--output-dir", output],
    { now: NOW },
  );

  assert.equal(result.report.bundle.source_snapshot_at, null);
  assert.equal(result.report.summary.import_ready, false);
  assert(
    result.report.issues.some(
      (issue) => issue.code === "missing_source_snapshot_at",
    ),
  );
});
