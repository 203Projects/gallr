#!/usr/bin/env node

import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const COMPATIBILITY_FIELDS = Object.freeze([
  "id",
  "name_ko",
  "name_en",
  "venue_name_ko",
  "venue_name_en",
  "city_ko",
  "city_en",
  "region_ko",
  "region_en",
  "opening_date",
  "closing_date",
  "is_featured",
  "latitude",
  "longitude",
  "description_ko",
  "description_en",
  "address_ko",
  "address_en",
  "cover_image_url",
  "hours",
  "contact",
  "reception_date",
  "opening_time",
  "event_id",
  "editor_id",
  "is_homepage_featured",
  "ticket_url",
  "updated_at",
]);

const REQUIRED_TEXT_FIELDS = Object.freeze([
  "id",
  "name_ko",
  "venue_name_ko",
  "city_ko",
  "region_ko",
]);

const NON_NULL_TEXT_FIELDS = new Set([
  "name_ko",
  "name_en",
  "venue_name_ko",
  "venue_name_en",
  "city_ko",
  "city_en",
  "region_ko",
  "region_en",
  "description_ko",
  "description_en",
  "address_ko",
  "address_en",
]);

const OPTIONAL_TEXT_FIELDS = new Set([
  "cover_image_url",
  "hours",
  "contact",
  "opening_time",
  "event_id",
  "editor_id",
  "ticket_url",
]);

const BOOLEAN_FIELDS = new Set(["is_featured", "is_homepage_featured"]);
const NUMBER_FIELDS = new Set(["latitude", "longitude"]);
const DATE_FIELDS = new Set(["opening_date", "closing_date"]);
const TIMESTAMP_FIELDS = new Set(["reception_date", "updated_at"]);
const URL_FIELDS = Object.freeze(["cover_image_url", "ticket_url"]);
const REQUIRED_SHEET_HEADERS = Object.freeze([
  "name_ko",
  "venue_name_ko",
  "city_ko",
  "region_ko",
  "opening_date",
  "closing_date",
]);

const ISSUE_COLUMNS = Object.freeze([
  "severity",
  "code",
  "source",
  "source_row_number",
  "id",
  "field",
  "message",
  "legacy_value",
  "sheet_value",
]);

const RECONCILIATION_COLUMNS = Object.freeze([
  "match_key",
  "status",
  "legacy_source_row_number",
  "sheet_source_row_number",
  "legacy_id",
  "sheet_explicit_id",
  "legacy_gas_generated_id",
  "sheet_gas_generated_id",
  "gas_id_matches_authoritative",
  "mismatch_fields",
]);

function isBlank(value) {
  return value === null || value === undefined || String(value).trim() === "";
}

function sourceRowNumber(rawRow, fallback) {
  const candidate = Number(rawRow?.source_row_number);
  return Number.isInteger(candidate) && candidate > 0 ? candidate : fallback;
}

function normalizeText(value, nullable) {
  if (value === null || value === undefined) return nullable ? null : "";
  const normalized = String(value).trim();
  return nullable && normalized === "" ? null : normalized;
}

function normalizeBoolean(value) {
  if (value === true || value === 1) return true;
  if (value === false || value === 0 || value === null || value === undefined) {
    return false;
  }
  const normalized = String(value).trim().toLowerCase();
  if (normalized === "true" || normalized === "1" || normalized === "yes") return true;
  if (normalized === "false" || normalized === "0" || normalized === "no" || normalized === "") {
    return false;
  }
  // Retain invalid source text in a blocked bundle so server-side validation
  // can independently reject it rather than seeing a lossy false default.
  return String(value).trim();
}

function isRecognizedBoolean(value) {
  if (value === null || value === undefined || value === "") return true;
  if (value === true || value === false || value === 1 || value === 0) return true;
  return ["true", "false", "1", "0", "yes", "no"].includes(
    String(value).trim().toLowerCase(),
  );
}

function normalizeNumber(value) {
  if (isBlank(value)) return null;
  const normalized = Number(value);
  // As with booleans, keep invalid source text visible in bundle.json. A valid
  // run never contains this fallback because validation marks it as an error.
  return Number.isFinite(normalized) ? normalized : String(value).trim();
}

function normalizeDate(value) {
  if (isBlank(value)) return null;
  return String(value).trim().replaceAll(".", "-");
}

function normalizeTimestamp(value) {
  if (isBlank(value)) return null;
  return String(value).trim();
}

function rawField(rawRow, field) {
  return Object.prototype.hasOwnProperty.call(rawRow, field) ? rawRow[field] : undefined;
}

function normalizeField(rawRow, field) {
  const value = rawField(rawRow, field);
  if (field === "id") {
    // The database identifier is the authoritative identity. Do not regenerate,
    // case-fold, or trim it while preparing a migration bundle.
    return value === null || value === undefined ? "" : String(value);
  }
  if (NON_NULL_TEXT_FIELDS.has(field)) return normalizeText(value, false);
  if (OPTIONAL_TEXT_FIELDS.has(field)) return normalizeText(value, true);
  if (BOOLEAN_FIELDS.has(field)) return normalizeBoolean(value);
  if (NUMBER_FIELDS.has(field)) return normalizeNumber(value);
  if (DATE_FIELDS.has(field)) return normalizeDate(value);
  if (TIMESTAMP_FIELDS.has(field)) return normalizeTimestamp(value);
  throw new Error(`No normalizer configured for compatibility field: ${field}`);
}

/** Reproduce SyncExhibitions.gs generateId() for diagnostics only. */
export function gasGeneratedId(nameKo, venueNameKo, cityKo, openingDate) {
  const raw = `${nameKo ?? ""}|${venueNameKo ?? ""}|${cityKo ?? ""}|${openingDate ?? ""}`
    .toLowerCase()
    .trim();
  // The live Apps Script Utilities.computeDigest(algorithm, String) overload
  // substitutes non-ASCII code points with "?". Preserve that historical
  // behavior exactly during reconciliation: changing it here would hide real
  // production ID collisions without changing the running sync.
  const gasDigestInput = Array.from(raw, (character) =>
    character.codePointAt(0) <= 0x7f ? character : "?",
  ).join("");
  return createHash("sha256")
    .update(gasDigestInput, "ascii")
    .digest("hex")
    .slice(0, 16);
}

export function normalizeLegacyRow(rawRow, fallbackRowNumber = 1) {
  if (rawRow === null || typeof rawRow !== "object" || Array.isArray(rawRow)) {
    throw new TypeError(`Source row ${fallbackRowNumber} must be a JSON object`);
  }

  const normalized = { source_row_number: sourceRowNumber(rawRow, fallbackRowNumber) };
  for (const field of COMPATIBILITY_FIELDS) {
    normalized[field] = normalizeField(rawRow, field);
  }

  const generatedId = gasGeneratedId(
    normalized.name_ko,
    normalized.venue_name_ko,
    normalized.city_ko,
    normalized.opening_date,
  );
  // Keep the staged row itself at exactly 29 fields: source_row_number plus
  // the 28-field compatibility contract. Diagnostics remain available to this
  // process and are emitted in reports, but JSON.stringify does not include them.
  Object.defineProperty(normalized, "diagnostics", {
    enumerable: false,
    value: Object.freeze({
      gas_generated_id: generatedId,
      gas_id_matches_authoritative: normalized.id === generatedId,
    }),
  });
  return normalized;
}

function isRealIsoDate(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value ?? "");
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
  );
}

function isValidTimestamp(value) {
  if (typeof value !== "string" || value.trim() === "") return false;
  const datePrefix = value.slice(0, 10);
  if (!isRealIsoDate(datePrefix)) return false;
  return Number.isFinite(Date.parse(value));
}

function validHttpUrl(value) {
  try {
    const parsed = new URL(value);
    return (parsed.protocol === "http:" || parsed.protocol === "https:") && parsed.hostname !== "";
  } catch {
    return false;
  }
}

function normalizeTimeZone(value) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError("sheetTimeZone must be a non-empty IANA time zone");
  }
  const normalized = value.trim();
  try {
    new Intl.DateTimeFormat("en", { timeZone: normalized }).format(new Date(0));
  } catch {
    throw new TypeError(`Invalid IANA Sheet time zone: ${normalized}`);
  }
  return normalized;
}

function calendarDateInTimeZone(value, timeZone) {
  if (!isValidTimestamp(value)) return null;
  const parts = new Intl.DateTimeFormat("en", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(value));
  const part = (type) => parts.find((candidate) => candidate.type === type)?.value;
  return `${part("year")}-${part("month")}-${part("day")}`;
}

function makeIssue({
  severity = "error",
  code,
  source,
  row,
  field = "",
  message,
  legacyValue = null,
  sheetValue = null,
}) {
  return {
    severity,
    code,
    source,
    source_row_number: row?.source_row_number ?? "",
    id: row?.id ?? "",
    field,
    message,
    legacy_value: legacyValue,
    sheet_value: sheetValue,
  };
}

function validateOneRow(row, rawRow, source) {
  const issues = [];

  const requiredTextFields =
    source === "sheet_csv"
      ? REQUIRED_TEXT_FIELDS.filter((field) => field !== "id")
      : REQUIRED_TEXT_FIELDS;
  for (const field of requiredTextFields) {
    if (String(row[field] ?? "").trim() === "") {
      issues.push(
        makeIssue({
          code: "required_field_missing",
          source,
          row,
          field,
          message: `${field} is required`,
          legacyValue: source === "legacy_json" ? rawField(rawRow, field) : null,
          sheetValue: source === "sheet_csv" ? rawField(rawRow, field) : null,
        }),
      );
    }
  }

  if (row.id !== row.id.trim()) {
    issues.push(
      makeIssue({
        code: "id_has_outer_whitespace",
        source,
        row,
        field: "id",
        message: "Database id must not have leading or trailing whitespace",
        legacyValue: source === "legacy_json" ? row.id : null,
        sheetValue: source === "sheet_csv" ? row.id : null,
      }),
    );
  }

  for (const field of ["opening_date", "closing_date"]) {
    if (row[field] === null) {
      issues.push(
        makeIssue({
          code: "required_field_missing",
          source,
          row,
          field,
          message: `${field} is required`,
        }),
      );
    } else if (!isRealIsoDate(row[field])) {
      issues.push(
        makeIssue({
          code: "invalid_date",
          source,
          row,
          field,
          message: `${field} must be a real calendar date in YYYY-MM-DD format`,
          legacyValue: source === "legacy_json" ? row[field] : null,
          sheetValue: source === "sheet_csv" ? row[field] : null,
        }),
      );
    }
  }

  if (
    isRealIsoDate(row.opening_date) &&
    isRealIsoDate(row.closing_date) &&
    row.closing_date < row.opening_date
  ) {
    issues.push(
      makeIssue({
        code: "invalid_date_order",
        source,
        row,
        field: "closing_date",
        message: "closing_date must be on or after opening_date",
        legacyValue: source === "legacy_json" ? row.closing_date : null,
        sheetValue: source === "sheet_csv" ? row.closing_date : null,
      }),
    );
  }

  if (row.reception_date !== null) {
    const validReception =
      isRealIsoDate(row.reception_date) || isValidTimestamp(row.reception_date);
    if (!validReception) {
      issues.push(
        makeIssue({
          code: "invalid_timestamp",
          source,
          row,
          field: "reception_date",
          message: "reception_date must be a real ISO date or timestamp",
          legacyValue: source === "legacy_json" ? row.reception_date : null,
          sheetValue: source === "sheet_csv" ? row.reception_date : null,
        }),
      );
    }
  }

  if (source === "legacy_json" && row.updated_at === null) {
    issues.push(
      makeIssue({
        code: "required_field_missing",
        source,
        row,
        field: "updated_at",
        message: "updated_at is required by the compatibility contract",
      }),
    );
  } else if (row.updated_at !== null && !isValidTimestamp(row.updated_at)) {
    issues.push(
      makeIssue({
        code: "invalid_timestamp",
        source,
        row,
        field: "updated_at",
        message: "updated_at must be a real ISO timestamp",
        legacyValue: source === "legacy_json" ? row.updated_at : null,
        sheetValue: source === "sheet_csv" ? row.updated_at : null,
      }),
    );
  }

  for (const field of ["latitude", "longitude"]) {
    const raw = rawField(rawRow, field);
    if (!isBlank(raw) && typeof row[field] !== "number") {
      issues.push(
        makeIssue({
          code: "invalid_number",
          source,
          row,
          field,
          message: `${field} must be a finite number`,
          legacyValue: source === "legacy_json" ? raw : null,
          sheetValue: source === "sheet_csv" ? raw : null,
        }),
      );
    }
  }

  if ((row.latitude === null) !== (row.longitude === null)) {
    issues.push(
      makeIssue({
        code: "incomplete_coordinate_pair",
        source,
        row,
        field: "latitude,longitude",
        message: "latitude and longitude must either both be present or both be null",
        legacyValue:
          source === "legacy_json" ? `${row.latitude ?? ""}|${row.longitude ?? ""}` : null,
        sheetValue:
          source === "sheet_csv" ? `${row.latitude ?? ""}|${row.longitude ?? ""}` : null,
      }),
    );
  }
  if (
    typeof row.latitude === "number" &&
    (row.latitude < -90 || row.latitude > 90)
  ) {
    issues.push(
      makeIssue({
        code: "coordinate_out_of_range",
        source,
        row,
        field: "latitude",
        message: "latitude must be between -90 and 90",
        legacyValue: source === "legacy_json" ? row.latitude : null,
        sheetValue: source === "sheet_csv" ? row.latitude : null,
      }),
    );
  }
  if (
    typeof row.longitude === "number" &&
    (row.longitude < -180 || row.longitude > 180)
  ) {
    issues.push(
      makeIssue({
        code: "coordinate_out_of_range",
        source,
        row,
        field: "longitude",
        message: "longitude must be between -180 and 180",
        legacyValue: source === "legacy_json" ? row.longitude : null,
        sheetValue: source === "sheet_csv" ? row.longitude : null,
      }),
    );
  }

  for (const field of URL_FIELDS) {
    const sheetFilenameAllowed =
      source === "sheet_csv" && field === "cover_image_url" && row[field] !== null;
    if (row[field] !== null && !validHttpUrl(row[field]) && !sheetFilenameAllowed) {
      issues.push(
        makeIssue({
          code: "invalid_url_scheme",
          source,
          row,
          field,
          message: `${field} must use the http or https scheme`,
          legacyValue: source === "legacy_json" ? row[field] : null,
          sheetValue: source === "sheet_csv" ? row[field] : null,
        }),
      );
    }
  }

  for (const field of BOOLEAN_FIELDS) {
    const raw = rawField(rawRow, field);
    if (!isRecognizedBoolean(raw)) {
      issues.push(
        makeIssue({
          code: "invalid_boolean",
          source,
          row,
          field,
          message: `${field} must be true/false, 1/0, or yes/no`,
          legacyValue: source === "legacy_json" ? raw : null,
          sheetValue: source === "sheet_csv" ? raw : null,
        }),
      );
    }
  }

  if (source === "legacy_json" && !row.diagnostics.gas_id_matches_authoritative) {
    issues.push(
      makeIssue({
        severity: "warning",
        code: "gas_id_mismatch",
        source,
        row,
        field: "id",
        message:
          "Authoritative database id differs from the current four-field GAS diagnostic id; the database id will be preserved",
        legacyValue: row.id,
        sheetValue: row.diagnostics.gas_generated_id,
      }),
    );
  }

  return issues;
}

function duplicateIssues(rows, source, idSelector = (row) => row.id) {
  const groups = new Map();
  for (const row of rows) {
    const id = idSelector(row);
    if (id === null || id === undefined || id === "") continue;
    const group = groups.get(id) ?? [];
    group.push(row);
    groups.set(id, group);
  }

  const issues = [];
  for (const [id, group] of groups) {
    if (group.length < 2) continue;
    const rowNumbers = group.map((row) => row.source_row_number).join(", ");
    for (const row of group) {
      issues.push(
        makeIssue({
          code: "duplicate_id",
          source,
          row,
          field: "id",
          message: `Duplicate id ${id} appears on source rows ${rowNumbers}`,
          legacyValue: source === "legacy_json" ? id : null,
          sheetValue: source === "sheet_csv" ? id : null,
        }),
      );
    }
  }
  return issues;
}

export function validateNormalizedRows(
  entries,
  source = "legacy_json",
  { includeDuplicates = true } = {},
) {
  const issues = [];
  for (const entry of entries) {
    issues.push(...validateOneRow(entry.row, entry.raw, source));
  }
  if (includeDuplicates) {
    issues.push(...duplicateIssues(entries.map((entry) => entry.row), source));
  }
  return issues;
}

export function parseLegacyJson(text) {
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new SyntaxError(`Legacy JSON is not valid JSON: ${error.message}`);
  }

  const rows = Array.isArray(parsed) ? parsed : parsed?.rows;
  if (!Array.isArray(rows)) {
    throw new TypeError('Legacy JSON must be an array or an object containing a "rows" array');
  }
  const suppliedSnapshotAt = Array.isArray(parsed) ? null : parsed.source_snapshot_at ?? null;
  if (suppliedSnapshotAt !== null && !isValidTimestamp(String(suppliedSnapshotAt))) {
    throw new TypeError("source_snapshot_at must be a valid ISO timestamp when supplied");
  }
  return { rows, sourceSnapshotAt: suppliedSnapshotAt };
}

/** RFC 4180-style CSV parser with BOM, CRLF, escaped quote, and multiline support. */
export function parseCsv(text) {
  const input = String(text).replace(/^\uFEFF/, "");
  const records = [];
  let record = [];
  let field = "";
  let quoted = false;

  const pushField = () => {
    record.push(field);
    field = "";
  };
  const pushRecord = () => {
    pushField();
    records.push(record);
    record = [];
  };

  for (let index = 0; index < input.length; index += 1) {
    const char = input[index];
    if (quoted) {
      if (char === '"') {
        if (input[index + 1] === '"') {
          field += '"';
          index += 1;
        } else {
          quoted = false;
        }
      } else {
        field += char;
      }
      continue;
    }

    if (char === '"' && field === "") {
      quoted = true;
    } else if (char === ",") {
      pushField();
    } else if (char === "\n") {
      pushRecord();
    } else if (char === "\r") {
      if (input[index + 1] === "\n") index += 1;
      pushRecord();
    } else {
      field += char;
    }
  }
  if (quoted) throw new SyntaxError("Sheet CSV has an unterminated quoted field");
  if (field !== "" || record.length > 0) pushRecord();

  if (records.length === 0) throw new TypeError("Sheet CSV is empty");
  // Mirrors SyncExhibitions.gs buildHeaderMap(): header matching is
  // case-insensitive and ignores outer whitespace.
  const headers = records[0].map((header) => String(header).trim().toLowerCase());
  if (headers.some((header) => header === "")) {
    throw new TypeError("Sheet CSV contains an empty header");
  }
  const duplicateHeaders = headers.filter((header, index) => headers.indexOf(header) !== index);
  if (duplicateHeaders.length > 0) {
    throw new TypeError(`Sheet CSV contains duplicate headers: ${[...new Set(duplicateHeaders)].join(", ")}`);
  }

  const rows = [];
  for (let index = 1; index < records.length; index += 1) {
    const values = records[index];
    if (values.every((value) => value === "")) continue;
    if (values.length > headers.length) {
      throw new TypeError(`Sheet CSV row ${index + 1} has more values than headers`);
    }
    const row = { source_row_number: index + 1 };
    for (let column = 0; column < headers.length; column += 1) {
      row[headers[column]] = values[column] ?? "";
    }
    rows.push(row);
  }
  return { headers, rows };
}

function isSheetPublishable(rawRow, headerSet) {
  const statusApproved = String(rawRow.status ?? "").trim().toLowerCase() === "approved";
  const isFormSourced = [...headerSet].some(
    (header) => /^image_url_[1-5]$/.test(header) && !isBlank(rawRow[header]),
  );
  if (isFormSourced) return statusApproved;
  if (!headerSet.has("status")) return true;
  return statusApproved;
}

function normalizeEntries(rawRows, source, headerSet = null) {
  return rawRows.map((raw, index) => {
    const fallback = source === "sheet_csv" ? index + 2 : index + 1;
    return {
      raw,
      row: normalizeLegacyRow(raw, fallback),
      publishable: source !== "sheet_csv" || isSheetPublishable(raw, headerSet),
    };
  });
}

function valueForCsv(value) {
  if (value === null || value === undefined) return "";
  if (Array.isArray(value)) return value.join("|");
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function csvEscape(value) {
  const text = valueForCsv(value);
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

export function toCsv(columns, rows) {
  const lines = [columns.map(csvEscape).join(",")];
  for (const row of rows) {
    lines.push(columns.map((column) => csvEscape(row[column])).join(","));
  }
  return `${lines.join("\n")}\n`;
}

function sheetMatchKey(row, headerSet) {
  if (headerSet.has("id") && row.id !== "") return row.id;
  return row.diagnostics.gas_generated_id;
}

function filenameMatchesPublicUrl(sheetValue, legacyValue) {
  if (sheetValue === null || legacyValue === null || validHttpUrl(sheetValue)) return false;
  try {
    const publicUrl = new URL(legacyValue);
    const finalSegment = decodeURIComponent(publicUrl.pathname.split("/").at(-1) ?? "");
    return finalSegment === sheetValue;
  } catch {
    return false;
  }
}

function equivalentValue(field, legacyValue, sheetValue, sheetTimeZone) {
  if (legacyValue === sheetValue) return true;
  if (field === "cover_image_url" && filenameMatchesPublicUrl(sheetValue, legacyValue)) {
    return true;
  }
  if (field === "reception_date") {
    if (isRealIsoDate(sheetValue) && isValidTimestamp(legacyValue)) {
      return calendarDateInTimeZone(legacyValue, sheetTimeZone) === sheetValue;
    }
    if (isValidTimestamp(legacyValue) && isValidTimestamp(sheetValue)) {
      return Date.parse(legacyValue) === Date.parse(sheetValue);
    }
  }
  return false;
}

export function reconcileRows(
  legacyEntries,
  sheetEntries,
  headers,
  { sheetTimeZone },
) {
  const headerSet = new Set(headers);
  const publishableSheetEntries = sheetEntries.filter((entry) => entry.publishable);
  const sheetByKey = new Map();
  for (const entry of publishableSheetEntries) {
    const key = sheetMatchKey(entry.row, headerSet);
    const group = sheetByKey.get(key) ?? [];
    group.push(entry);
    sheetByKey.set(key, group);
  }

  const issues = [];
  const reconciliation = [];
  const consumedSheetEntries = new Set();
  const comparableFields = COMPATIBILITY_FIELDS.filter(
    (field) => field !== "id" && headerSet.has(field),
  );

  for (const legacyEntry of legacyEntries) {
    const legacy = legacyEntry.row;
    let matchKey = legacy.id;
    let matches = sheetByKey.get(matchKey) ?? [];
    if (matches.length === 0 && legacy.diagnostics.gas_generated_id !== legacy.id) {
      // A historical/manual database id remains authoritative. When the Sheet
      // has no explicit id, its computed GAS id may still locate the source row
      // for comparison; it never changes the id written to bundle.json.
      matchKey = legacy.diagnostics.gas_generated_id;
      matches = (sheetByKey.get(matchKey) ?? []).filter(
        (entry) => !headerSet.has("id") || entry.row.id === "",
      );
    }
    if (matches.length === 0) {
      reconciliation.push({
        match_key: legacy.id,
        status: "legacy_only",
        legacy_source_row_number: legacy.source_row_number,
        sheet_source_row_number: "",
        legacy_id: legacy.id,
        sheet_explicit_id: "",
        legacy_gas_generated_id: legacy.diagnostics.gas_generated_id,
        sheet_gas_generated_id: "",
        gas_id_matches_authoritative: legacy.diagnostics.gas_id_matches_authoritative,
        mismatch_fields: "",
      });
      issues.push(
        makeIssue({
          code: "legacy_row_missing_from_sheet",
          source: "reconciliation",
          row: legacy,
          field: "id",
          message: "Legacy public row has no publishable Sheet row with the same authoritative/GAS id",
          legacyValue: legacy.id,
        }),
      );
      continue;
    }

    if (matches.length > 1) {
      for (const match of matches) consumedSheetEntries.add(match);
      reconciliation.push({
        match_key: matchKey,
        status: "ambiguous_sheet_match",
        legacy_source_row_number: legacy.source_row_number,
        sheet_source_row_number: matches.map((entry) => entry.row.source_row_number).join("|"),
        legacy_id: legacy.id,
        sheet_explicit_id: matches.map((entry) => entry.row.id).filter(Boolean).join("|"),
        legacy_gas_generated_id: legacy.diagnostics.gas_generated_id,
        sheet_gas_generated_id: matches
          .map((entry) => entry.row.diagnostics.gas_generated_id)
          .join("|"),
        gas_id_matches_authoritative: legacy.diagnostics.gas_id_matches_authoritative,
        mismatch_fields: "",
      });
      issues.push(
        makeIssue({
          code: "ambiguous_sheet_match",
          source: "reconciliation",
          row: legacy,
          field: "id",
          message: `Multiple publishable Sheet rows match legacy id ${legacy.id}`,
          legacyValue: legacy.id,
          sheetValue: matches.map((entry) => entry.row.source_row_number).join(", "),
        }),
      );
      continue;
    }

    const sheetEntry = matches[0];
    consumedSheetEntries.add(sheetEntry);
    const mismatchFields = comparableFields.filter(
      (field) =>
        !equivalentValue(field, legacy[field], sheetEntry.row[field], sheetTimeZone),
    );
    const status = mismatchFields.length === 0 ? "matched" : "mismatched";
    reconciliation.push({
      match_key: matchKey,
      status,
      legacy_source_row_number: legacy.source_row_number,
      sheet_source_row_number: sheetEntry.row.source_row_number,
      legacy_id: legacy.id,
      sheet_explicit_id: headerSet.has("id") ? sheetEntry.row.id : "",
      legacy_gas_generated_id: legacy.diagnostics.gas_generated_id,
      sheet_gas_generated_id: sheetEntry.row.diagnostics.gas_generated_id,
      gas_id_matches_authoritative: legacy.diagnostics.gas_id_matches_authoritative,
      mismatch_fields: mismatchFields,
    });

    for (const field of mismatchFields) {
      issues.push(
        makeIssue({
          code: "sheet_public_field_mismatch",
          source: "reconciliation",
          row: legacy,
          field,
          message: `${field} differs between the legacy public row and publishable Sheet row`,
          legacyValue: legacy[field],
          sheetValue: sheetEntry.row[field],
        }),
      );
    }
  }

  for (const sheetEntry of publishableSheetEntries) {
    if (consumedSheetEntries.has(sheetEntry)) continue;
    const sheet = sheetEntry.row;
    const key = sheetMatchKey(sheet, headerSet);
    reconciliation.push({
      match_key: key,
      status: "sheet_only",
      legacy_source_row_number: "",
      sheet_source_row_number: sheet.source_row_number,
      legacy_id: "",
      sheet_explicit_id: headerSet.has("id") ? sheet.id : "",
      legacy_gas_generated_id: "",
      sheet_gas_generated_id: sheet.diagnostics.gas_generated_id,
      gas_id_matches_authoritative: "",
      mismatch_fields: "",
    });
    issues.push(
      makeIssue({
        code: "sheet_row_missing_from_legacy",
        source: "reconciliation",
        row: sheet,
        field: "id",
        message: "Publishable Sheet row has no legacy public row with its explicit/GAS id",
        sheetValue: key,
      }),
    );
  }

  for (const sheetEntry of sheetEntries.filter((entry) => !entry.publishable)) {
    const sheet = sheetEntry.row;
    reconciliation.push({
      match_key: sheetMatchKey(sheet, headerSet),
      status: "sheet_not_publishable",
      legacy_source_row_number: "",
      sheet_source_row_number: sheet.source_row_number,
      legacy_id: "",
      sheet_explicit_id: headerSet.has("id") ? sheet.id : "",
      legacy_gas_generated_id: "",
      sheet_gas_generated_id: sheet.diagnostics.gas_generated_id,
      gas_id_matches_authoritative: "",
      mismatch_fields: "",
    });
    issues.push(
      makeIssue({
        severity: "info",
        code: "sheet_row_not_publishable",
        source: "sheet_csv",
        row: sheet,
        field: "status",
        message: "Sheet row is excluded by the current GAS approval gate",
        sheetValue: sheetEntry.raw.status ?? "",
      }),
    );
  }

  return { reconciliation, issues };
}

function hashBytes(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function isoNow(now) {
  const value = now instanceof Date ? now : new Date(now);
  if (!Number.isFinite(value.getTime())) throw new TypeError("Report time must be valid");
  return value.toISOString();
}

function summarize({ issues, reconciliation, legacyCount, sheetCount, excludedSheetCount }) {
  const countSeverity = (severity) => issues.filter((issue) => issue.severity === severity).length;
  const countStatus = (status) =>
    reconciliation.filter((entry) => entry.status === status).length;
  const errorCount = countSeverity("error");
  return {
    dry_run: true,
    import_ready: errorCount === 0,
    compatibility_field_count: COMPATIBILITY_FIELDS.length,
    counts: {
      legacy_rows: legacyCount,
      sheet_rows: sheetCount,
      sheet_rows_excluded_by_approval_gate: excludedSheetCount,
      issues: issues.length,
      errors: errorCount,
      warnings: countSeverity("warning"),
      info: countSeverity("info"),
      matched: countStatus("matched"),
      mismatched: countStatus("mismatched"),
      legacy_only: countStatus("legacy_only"),
      sheet_only: countStatus("sheet_only"),
      ambiguous_sheet_match: countStatus("ambiguous_sheet_match"),
    },
  };
}

export function buildDryRun({
  legacyRows,
  legacySourceFileName,
  legacySourceSha256,
  sourceSnapshotAt,
  sheetRows = null,
  sheetHeaders = [],
  sheetSourceFileName = null,
  sheetSourceSha256 = null,
  sheetTimeZone = null,
  now = new Date(),
}) {
  if (!/^[a-f0-9]{64}$/.test(legacySourceSha256 ?? "")) {
    throw new TypeError("legacySourceSha256 must be 64 lowercase hexadecimal characters");
  }
  if (sheetRows !== null && !/^[a-f0-9]{64}$/.test(sheetSourceSha256 ?? "")) {
    throw new TypeError("sheetSourceSha256 must be 64 lowercase hexadecimal characters");
  }
  if (sheetRows === null && sheetTimeZone !== null) {
    throw new TypeError("sheetTimeZone requires Sheet rows");
  }
  const normalizedSheetTimeZone =
    sheetRows === null ? null : normalizeTimeZone(sheetTimeZone);
  const reportTime = isoNow(now);
  const headerSet = new Set(sheetHeaders);
  const legacyEntries = normalizeEntries(legacyRows, "legacy_json");
  const sheetEntries = sheetRows === null ? [] : normalizeEntries(sheetRows, "sheet_csv", headerSet);

  const issues = validateNormalizedRows(legacyEntries, "legacy_json");
  if (sourceSnapshotAt === null || sourceSnapshotAt === undefined) {
    issues.push(
      makeIssue({
        code: "missing_source_snapshot_at",
        source: "legacy_json",
        row: null,
        field: "source_snapshot_at",
        message:
          "Wrap the export with its database snapshot timestamp before staging",
      }),
    );
  }
  if (legacyEntries.length === 0) {
    issues.push(
      makeIssue({
        code: "empty_legacy_export",
        source: "legacy_json",
        row: null,
        message: "Legacy export contains no rows; an empty bundle is not import-ready",
      }),
    );
  }
  if (sheetRows !== null) {
    for (const field of REQUIRED_SHEET_HEADERS) {
      if (!headerSet.has(field)) {
        issues.push(
          makeIssue({
            code: "missing_sheet_header",
            source: "sheet_csv",
            row: null,
            field,
            message: `Sheet CSV is missing required GAS header ${field}`,
          }),
        );
      }
    }
    const publishable = sheetEntries.filter((entry) => entry.publishable);
    issues.push(
      ...validateNormalizedRows(publishable, "sheet_csv", { includeDuplicates: false }),
    );
    issues.push(
      ...duplicateIssues(
        publishable.map((entry) => entry.row),
        "sheet_csv",
        (row) => sheetMatchKey(row, headerSet),
      ),
    );
  }

  let reconciliation = [];
  if (sheetRows !== null) {
    const result = reconcileRows(legacyEntries, sheetEntries, sheetHeaders, {
      sheetTimeZone: normalizedSheetTimeZone,
    });
    reconciliation = result.reconciliation;
    issues.push(...result.issues);
  } else {
    reconciliation = legacyEntries.map(({ row }) => ({
      match_key: row.id,
      status: "not_compared",
      legacy_source_row_number: row.source_row_number,
      sheet_source_row_number: "",
      legacy_id: row.id,
      sheet_explicit_id: "",
      legacy_gas_generated_id: row.diagnostics.gas_generated_id,
      sheet_gas_generated_id: "",
      gas_id_matches_authoritative: row.diagnostics.gas_id_matches_authoritative,
      mismatch_fields: "",
    }));
  }

  const bundle = {
    schema_version: 1,
    source_system: "legacy_public_exhibitions",
    source_file_name: legacySourceFileName,
    source_snapshot_at:
      sourceSnapshotAt === null || sourceSnapshotAt === undefined
        ? null
        : isoNow(sourceSnapshotAt),
    source_sha256: legacySourceSha256,
    row_count: legacyEntries.length,
    rows: legacyEntries.map((entry) => entry.row),
  };

  const summary = {
    schema_version: 1,
    generated_at: reportTime,
    source: {
      source_system: bundle.source_system,
      file_name: legacySourceFileName,
      snapshot_at: bundle.source_snapshot_at,
      sha256: legacySourceSha256,
    },
    sheet_source:
      sheetRows === null
        ? null
        : {
            file_name: sheetSourceFileName,
            sha256: sheetSourceSha256,
            time_zone: normalizedSheetTimeZone,
          },
    ...summarize({
      issues,
      reconciliation,
      legacyCount: legacyEntries.length,
      sheetCount: sheetEntries.length,
      excludedSheetCount: sheetEntries.filter((entry) => !entry.publishable).length,
    }),
  };

  return { bundle, summary, issues, reconciliation };
}

export function parseArgs(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--help" || argument === "-h") return { help: true };
    if (
      ![
        "--legacy-json",
        "--sheet-csv",
        "--sheet-timezone",
        "--output-dir",
      ].includes(argument)
    ) {
      throw new TypeError(`Unknown argument: ${argument}`);
    }
    if (Object.prototype.hasOwnProperty.call(values, argument)) {
      throw new TypeError(`Argument supplied more than once: ${argument}`);
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--")) {
      throw new TypeError(`Missing value for ${argument}`);
    }
    values[argument] = value;
    index += 1;
  }
  for (const required of ["--legacy-json", "--output-dir"]) {
    if (!values[required]) throw new TypeError(`${required} is required`);
  }
  if (values["--sheet-csv"] && !values["--sheet-timezone"]) {
    throw new TypeError("--sheet-timezone is required when --sheet-csv is supplied");
  }
  if (!values["--sheet-csv"] && values["--sheet-timezone"]) {
    throw new TypeError("--sheet-timezone requires --sheet-csv");
  }
  const sheetTimeZone = values["--sheet-timezone"]
    ? normalizeTimeZone(values["--sheet-timezone"])
    : null;
  return {
    help: false,
    legacyJson: values["--legacy-json"],
    sheetCsv: values["--sheet-csv"] ?? null,
    sheetTimeZone,
    outputDir: values["--output-dir"],
  };
}

export const HELP_TEXT = `Usage:
  node scripts/legacy-import/legacy-import.mjs \\
    --legacy-json <legacy-exhibitions.json> \\
    [--sheet-csv <sheet-export.csv> --sheet-timezone <IANA-time-zone>] \\
    --output-dir <report-directory>

Writes bundle.json, summary.json, issues.csv, and reconciliation.csv.
This command performs no network or database operations.
`;

export async function writeDryRunReports(outputDir, report) {
  await mkdir(outputDir, { recursive: true });
  const files = {
    "bundle.json": `${JSON.stringify(report.bundle, null, 2)}\n`,
    "summary.json": `${JSON.stringify(report.summary, null, 2)}\n`,
    "issues.csv": toCsv(ISSUE_COLUMNS, report.issues),
    "reconciliation.csv": toCsv(RECONCILIATION_COLUMNS, report.reconciliation),
  };
  await Promise.all(
    Object.entries(files).map(([name, contents]) =>
      writeFile(path.join(outputDir, name), contents, "utf8"),
    ),
  );
  return Object.keys(files).map((name) => path.join(outputDir, name));
}

export async function runCli(argv, { now = new Date() } = {}) {
  const args = parseArgs(argv);
  if (args.help) return { help: true, report: null, files: [] };

  const legacyBytes = await readFile(args.legacyJson);
  const legacyInput = parseLegacyJson(legacyBytes.toString("utf8"));

  let parsedSheet = null;
  let sheetBytes = null;
  if (args.sheetCsv !== null) {
    sheetBytes = await readFile(args.sheetCsv);
    parsedSheet = parseCsv(sheetBytes.toString("utf8"));
  }

  const report = buildDryRun({
    legacyRows: legacyInput.rows,
    legacySourceFileName: path.basename(args.legacyJson),
    legacySourceSha256: hashBytes(legacyBytes),
    sourceSnapshotAt: legacyInput.sourceSnapshotAt,
    sheetRows: parsedSheet?.rows ?? null,
    sheetHeaders: parsedSheet?.headers ?? [],
    sheetSourceFileName: args.sheetCsv === null ? null : path.basename(args.sheetCsv),
    sheetSourceSha256: sheetBytes === null ? null : hashBytes(sheetBytes),
    sheetTimeZone: args.sheetTimeZone,
    now,
  });
  const files = await writeDryRunReports(args.outputDir, report);
  return { help: false, report, files };
}

async function main() {
  try {
    const result = await runCli(process.argv.slice(2));
    if (result.help) {
      process.stdout.write(HELP_TEXT);
      return;
    }
    process.stdout.write(`${JSON.stringify(result.report.summary, null, 2)}\n`);
    if (!result.report.summary.import_ready) process.exitCode = 2;
  } catch (error) {
    process.stderr.write(`legacy-import: ${error.message}\n\n${HELP_TEXT}`);
    process.exitCode = 1;
  }
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : null;
if (invokedPath !== null && fileURLToPath(import.meta.url) === invokedPath) {
  await main();
}
