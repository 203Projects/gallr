#!/usr/bin/env node
// Build-time fetcher for the multi-page catalog.
//
// Reads SUPABASE_URL + SUPABASE_ANON_KEY, fetches every row from the
// exhibitions table with duplicate-safe keyset pagination, enriches each
// with `slug` and `status`, and writes _data/exhibitions.json. Falls back to
// scripts/exhibitions-seed.json when env vars are missing or the fetch fails
// (local development and deliberately offline test jobs).
//
// Vercel and GALLR_REQUIRE_LIVE_DATA=1 builds exit non-zero on fallback —
// shipping placeholder data when live evidence is required is worse than a
// failed build.

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { classify } = require("./lib/status.js");
const { buildSlug } = require("./lib/slug.js");
const {
  ExhibitionReaderSourceConfigurationError,
  LEGACY_EXHIBITION_READER_SOURCE,
  assertExhibitionReaderSource,
  resolveExhibitionReaderSource,
} = require("./lib/exhibition-reader-source.js");

const ROOT = path.join(__dirname, "..");
const SEED = path.join(ROOT, "scripts", "exhibitions-seed.json");
const OUTPUT_DIR = path.join(ROOT, "_data");
const OUTPUT = path.join(OUTPUT_DIR, "exhibitions.json");
const PAGE_SIZE = 500;

const BASE_SELECT_COLS = [
  "id", "name_ko", "name_en",
  "venue_name_ko", "venue_name_en",
  "city_ko", "address_ko",
  "latitude", "longitude",
  "opening_date", "closing_date",
  "cover_image_url",
  "description_ko", "description_en",
  "ticket_url", "is_featured",
];

const REQUIRE_LIVE_DATA =
  process.env.VERCEL === "1" || process.env.GALLR_REQUIRE_LIVE_DATA === "1";

class IntegrityMismatchError extends Error {}

class CountMismatchError extends IntegrityMismatchError {
  constructor(expected, actual) {
    super(`exact count mismatch: PostgREST reported ${expected}, fetched ${actual}`);
    this.name = "CountMismatchError";
    this.expected = expected;
    this.actual = actual;
  }
}

class ReaderIntegrityMismatchError extends IntegrityMismatchError {
  constructor(field, expected, actual) {
    super(`reader integrity ${field} mismatch: RPC reported ${expected}, fetched ${actual}`);
    this.name = "ReaderIntegrityMismatchError";
    this.field = field;
    this.expected = expected;
    this.actual = actual;
  }
}

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

function effectiveToday(todayOverride) {
  return todayOverride || process.env.GALLR_TEST_TODAY || todayIso();
}

function sortForPresentation(rows) {
  // Supabase rows arrive in verified database id.asc order. Keep that transport
  // order as the stable tie-break instead of reinterpreting text collation in JS.
  return rows
    .map((row, transportIndex) => ({
      row,
      transportIndex,
      openingDate: typeof row.opening_date === "string" ? row.opening_date : "",
    }))
    .sort((a, b) => {
      if (a.openingDate !== b.openingDate) return a.openingDate > b.openingDate ? -1 : 1;
      return a.transportIndex - b.transportIndex;
    })
    .map(({ row }) => row);
}

function enrich(rows, today) {
  return rows.map((r) => ({
    ...r,
    slug: buildSlug({ name_en: r.name_en, name_ko: r.name_ko, id: r.id }),
    status: classify(r.opening_date, r.closing_date, today),
  }));
}

function pickFeatured(exhibitions) {
  // The input retains verified database id.asc order (or seed file order in dev).
  const flagged = exhibitions.filter((e) => e.is_featured === true);

  if (flagged.length === 1) return flagged[0].id;
  if (flagged.length > 1) {
    console.warn(`[fetch-exhibitions] ${flagged.length} is_featured rows; using first in source order: ${flagged[0].id}`);
    return flagged[0].id;
  }
  // Fallback: most recently opened current exhibition. Equal dates retain the
  // verified source order through sortForPresentation's transport index.
  const current = sortForPresentation(exhibitions.filter((e) => e.status === "current"));
  if (current.length > 0) {
    console.warn(`[fetch-exhibitions] no is_featured row; falling back to most-recently-opened current: ${current[0].id}`);
    return current[0].id;
  }
  console.warn(`[fetch-exhibitions] no is_featured + no current rows; featuredId=null`);
  return null;
}

function filterEntries(filters) {
  if (!filters) return [];
  if (filters instanceof URLSearchParams) return [...filters.entries()];
  if (Array.isArray(filters)) return filters;
  return Object.entries(filters);
}

function postgrestFilterLiteral(value) {
  // Basic scalar operators such as eq/gt consume the remainder as the value;
  // surrounding quotes are not stripped there and would become literal data.
  // URLSearchParams owns percent-encoding of punctuation, quotes, backslashes,
  // spaces, and Unicode after the operator prefix has been attached.
  return String(value);
}

function checksumExhibitionIds(rowsOrIds) {
  const hash = crypto.createHash("sha256");
  for (const rowOrId of rowsOrIds) {
    const id = typeof rowOrId === "string" ? rowOrId : rowOrId && rowOrId.id;
    if (typeof id !== "string") throw new Error("cannot checksum an exhibition without a string id");
    hash.update(String(Buffer.byteLength(id, "utf8")), "utf8");
    hash.update(":", "utf8");
    hash.update(id, "utf8");
  }
  return hash.digest("hex");
}

function checksumExhibitionContent(rows) {
  const hash = crypto.createHash("sha256");
  for (const row of rows) {
    const id = row && row.id;
    const checksum = row && row.content_checksum_sha256;
    if (typeof id !== "string") {
      throw new Error("cannot checksum exhibition content without a string id");
    }
    if (typeof checksum !== "string" || !/^[0-9a-f]{64}$/.test(checksum)) {
      throw new Error(
        `cannot checksum exhibition '${id}' without a valid content_checksum_sha256`
      );
    }
    hash.update(String(Buffer.byteLength(id, "utf8")), "utf8");
    hash.update(":", "utf8");
    hash.update(id, "utf8");
    hash.update(String(Buffer.byteLength(checksum, "utf8")), "utf8");
    hash.update(":", "utf8");
    hash.update(checksum, "utf8");
  }
  return hash.digest("hex");
}

function selectColumnsForSource(readerSource) {
  const source = assertExhibitionReaderSource(readerSource);
  return source.integrityMode === "id-and-content"
    ? [...BASE_SELECT_COLS, "content_checksum_sha256"].join(",")
    : BASE_SELECT_COLS.join(",");
}

function buildReaderIntegrityUrl(
  baseUrl,
  { filters, readerSource = LEGACY_EXHIBITION_READER_SOURCE } = {}
) {
  const source = assertExhibitionReaderSource(readerSource);
  const endpoint = new URL(
    `${String(baseUrl).replace(/\/+$/, "")}/rest/v1/rpc/${source.integrityRpc}`
  );
  const params = new URLSearchParams();
  let hasEventFilter = false;
  let hasFeaturedFilter = false;

  for (const entry of filterEntries(filters)) {
    if (!Array.isArray(entry) || entry.length !== 2) {
      throw new Error("exhibition filters must contain [name, value] pairs");
    }
    const [rawName, rawValue] = entry;
    const name = String(rawName);
    const value = String(rawValue);

    if (name === "event_id") {
      if (hasEventFilter) throw new Error("duplicate event_id exhibition filter");
      if (!value.startsWith("eq.") || value.slice(3).length === 0) {
        throw new Error("event_id exhibition filter must use eq.<value>");
      }
      params.set("p_event_id", value.slice(3));
      hasEventFilter = true;
      continue;
    }

    if (name === "is_featured") {
      if (hasFeaturedFilter) throw new Error("duplicate is_featured exhibition filter");
      if (value !== "eq.true") {
        throw new Error("is_featured exhibition filter must be eq.true");
      }
      params.set("p_featured_only", "true");
      hasFeaturedFilter = true;
      continue;
    }

    throw new Error(`unsupported exhibition integrity filter: ${name}`);
  }

  endpoint.search = params.toString();
  return endpoint.toString();
}

function buildExhibitionPageUrl(
  baseUrl,
  {
    afterId,
    filters,
    pageSize = PAGE_SIZE,
    readerSource = LEGACY_EXHIBITION_READER_SOURCE,
  } = {}
) {
  if (!Number.isSafeInteger(pageSize) || pageSize <= 0) {
    throw new Error(`invalid exhibition page size: ${pageSize}`);
  }

  const source = assertExhibitionReaderSource(readerSource);
  const endpoint = new URL(
    `${String(baseUrl).replace(/\/+$/, "")}/rest/v1/${source.resource}`
  );
  const params = new URLSearchParams();
  params.set("select", selectColumnsForSource(source));
  params.set("order", "id.asc");
  params.set("limit", String(pageSize));

  for (const entry of filterEntries(filters)) {
    if (!Array.isArray(entry) || entry.length !== 2) {
      throw new Error("exhibition filters must contain [name, value] pairs");
    }
    const [rawName, rawValue] = entry;
    const name = String(rawName);
    const value = String(rawValue);
    if (["select", "order", "limit", "id"].includes(name)) {
      throw new Error(`reserved exhibition filter: ${name}`);
    }
    if (name === "event_id") {
      if (!value.startsWith("eq.") || value.slice(3).length === 0) {
        throw new Error("event_id exhibition filter must use eq.<value>");
      }
      params.append(name, `eq.${postgrestFilterLiteral(value.slice(3))}`);
      continue;
    }
    if (name === "is_featured" && value === "eq.true") {
      params.append(name, value);
      continue;
    }
    throw new Error(`unsupported exhibition filter: ${name}`);
  }

  if (afterId !== undefined && afterId !== null) {
    params.set("id", `gt.${postgrestFilterLiteral(afterId)}`);
  }
  endpoint.search = params.toString();
  return endpoint.toString();
}

function readHeader(response, name) {
  if (response.headers && typeof response.headers.get === "function") {
    return response.headers.get(name);
  }
  if (response.headers && typeof response.headers === "object") {
    const key = Object.keys(response.headers).find(
      (candidate) => candidate.toLowerCase() === name.toLowerCase()
    );
    return key ? response.headers[key] : null;
  }
  return null;
}

function parseExactCount(response) {
  const contentRange = readHeader(response, "content-range");
  const match = typeof contentRange === "string"
    ? contentRange.trim().match(/^(?:\d+-\d+|\*)\/(\d+)$/)
    : null;
  if (!match) {
    throw new Error("missing or malformed Content-Range for exact exhibition count");
  }
  const count = Number(match[1]);
  if (!Number.isSafeInteger(count) || count < 0) {
    throw new Error(`invalid exact exhibition count: ${match[1]}`);
  }
  return count;
}

function parseReaderIntegrityBody(
  body,
  { readerSource = LEGACY_EXHIBITION_READER_SOURCE } = {}
) {
  const source = assertExhibitionReaderSource(readerSource);
  if (!Array.isArray(body) || body.length !== 1) {
    throw new Error("reader integrity RPC must return a one-row array");
  }
  const row = body[0];
  if (!row || typeof row !== "object" || Array.isArray(row)) {
    throw new Error("reader integrity RPC row must be an object");
  }
  const keys = Object.keys(row).sort();
  const expectedKeys = source.integrityMode === "id-and-content"
    ? ["catalog_checksum_sha256", "id_checksum_sha256", "row_count"]
    : ["id_checksum_sha256", "row_count"];
  if (
    keys.length !== expectedKeys.length ||
    keys.some((key, index) => key !== expectedKeys[index])
  ) {
    throw new Error("reader integrity RPC row has an invalid shape");
  }
  if (!Number.isSafeInteger(row.row_count) || row.row_count < 0) {
    throw new Error("reader integrity RPC row_count must be a non-negative safe integer");
  }
  if (typeof row.id_checksum_sha256 !== "string" || !/^[0-9a-f]{64}$/.test(row.id_checksum_sha256)) {
    throw new Error("reader integrity RPC id checksum must be 64 lowercase hexadecimal characters");
  }
  if (
    source.integrityMode === "id-and-content" &&
    (
      typeof row.catalog_checksum_sha256 !== "string" ||
      !/^[0-9a-f]{64}$/.test(row.catalog_checksum_sha256)
    )
  ) {
    throw new Error(
      "reader integrity RPC catalog checksum must be 64 lowercase hexadecimal characters"
    );
  }
  return row;
}

async function fetchReaderIntegrity({
  endpoint,
  key,
  fetchImpl,
  readerSource = LEGACY_EXHIBITION_READER_SOURCE,
}) {
  let response;
  try {
    response = await fetchImpl(endpoint, {
      headers: { apikey: key, Authorization: `Bearer ${key}` },
    });
  } catch (error) {
    throw new Error(`reader integrity RPC fetch error: ${error.message}`);
  }
  if (!response || !response.ok) {
    const status = response && response.status !== undefined ? response.status : "unknown";
    throw new Error(`reader integrity RPC HTTP ${status}`);
  }

  let body;
  try {
    body = await response.json();
  } catch (error) {
    throw new Error(`reader integrity RPC invalid JSON: ${error.message}`);
  }
  return parseReaderIntegrityBody(body, { readerSource });
}

function validatePageRows(pageRows, seenIds, pageNumber, readerSource) {
  const source = assertExhibitionReaderSource(readerSource);
  let lastId = null;
  for (const row of pageRows) {
    const id = row && row.id;
    if (typeof id !== "string" || id.trim() === "") {
      throw new Error(`page ${pageNumber} contains an exhibition with a missing or blank id`);
    }
    if (seenIds.has(id)) {
      throw new Error(`page ${pageNumber} contains duplicate exhibition id: ${id}`);
    }
    if (
      source.integrityMode === "id-and-content" &&
      (
        typeof row.content_checksum_sha256 !== "string" ||
        !/^[0-9a-f]{64}$/.test(row.content_checksum_sha256)
      )
    ) {
      throw new Error(
        `page ${pageNumber} exhibition '${id}' has an invalid content_checksum_sha256`
      );
    }
    seenIds.add(id);
    lastId = id;
  }
  return lastId;
}

async function fetchAllExhibitionsOnce({
  baseUrl,
  key,
  fetchImpl = global.fetch,
  filters = [],
  pageSize = PAGE_SIZE,
  readerSource = LEGACY_EXHIBITION_READER_SOURCE,
}) {
  if (typeof fetchImpl !== "function") throw new Error("fetch implementation is unavailable");
  const source = assertExhibitionReaderSource(readerSource);
  const integrityEndpoint = buildReaderIntegrityUrl(baseUrl, {
    filters,
    readerSource: source,
  });

  const rows = [];
  const seenIds = new Set();
  let afterId = null;
  let expectedCount = null;
  let pageNumber = 1;

  while (true) {
    const endpoint = buildExhibitionPageUrl(baseUrl, {
      afterId,
      filters,
      pageSize,
      readerSource: source,
    });
    const headers = { apikey: key, Authorization: `Bearer ${key}` };
    if (pageNumber === 1) headers.Prefer = "count=exact";

    let response;
    try {
      response = await fetchImpl(endpoint, { headers });
    } catch (error) {
      throw new Error(`page ${pageNumber} fetch error: ${error.message}`);
    }
    if (!response || !response.ok) {
      const status = response && response.status !== undefined ? response.status : "unknown";
      throw new Error(`page ${pageNumber} HTTP ${status}`);
    }

    if (pageNumber === 1) expectedCount = parseExactCount(response);

    let pageRows;
    try {
      pageRows = await response.json();
    } catch (error) {
      throw new Error(`page ${pageNumber} invalid JSON: ${error.message}`);
    }
    if (!Array.isArray(pageRows)) {
      throw new Error(`page ${pageNumber} returned a non-array response`);
    }

    if (pageRows.length === 0) break;

    const nextId = validatePageRows(pageRows, seenIds, pageNumber, source);
    rows.push(...pageRows);
    afterId = nextId;
    pageNumber += 1;
  }

  if (rows.length !== expectedCount) throw new CountMismatchError(expectedCount, rows.length);
  const integrity = await fetchReaderIntegrity({
    endpoint: integrityEndpoint,
    key,
    fetchImpl,
    readerSource: source,
  });
  if (integrity.row_count !== rows.length) {
    throw new ReaderIntegrityMismatchError("row_count", integrity.row_count, rows.length);
  }
  const checksum = checksumExhibitionIds(rows);
  if (integrity.id_checksum_sha256 !== checksum) {
    throw new ReaderIntegrityMismatchError(
      "id_checksum_sha256",
      integrity.id_checksum_sha256,
      checksum
    );
  }
  if (source.integrityMode === "id-and-content") {
    const catalogChecksum = checksumExhibitionContent(rows);
    if (integrity.catalog_checksum_sha256 !== catalogChecksum) {
      throw new ReaderIntegrityMismatchError(
        "catalog_checksum_sha256",
        integrity.catalog_checksum_sha256,
        catalogChecksum
      );
    }
  }
  return rows;
}

async function fetchAllExhibitions(options) {
  try {
    return await fetchAllExhibitionsOnce(options);
  } catch (error) {
    if (!(error instanceof IntegrityMismatchError)) throw error;
    const onMismatch = options.onIntegrityMismatch || options.onCountMismatch;
    if (typeof onMismatch === "function") onMismatch(error);
    return fetchAllExhibitionsOnce(options);
  }
}

function writeFromSeed(
  reason,
  todayOverride,
  readerSource = LEGACY_EXHIBITION_READER_SOURCE
) {
  const source = assertExhibitionReaderSource(readerSource);
  if (REQUIRE_LIVE_DATA) {
    console.error(
      `[fetch-exhibitions] FATAL: production build cannot fall back to seed (${reason}).`
    );
    process.exit(1);
  }
  console.log(`[fetch-exhibitions] using seed fallback (${reason})`);
  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  const seed = JSON.parse(fs.readFileSync(SEED, "utf8"));
  const today = effectiveToday(todayOverride);
  const sourceOrdered = enrich(seed.exhibitions || [], today);
  const exhibitions = sortForPresentation(sourceOrdered);
  const out = {
    fetchedAt: new Date().toISOString(),
    source: "seed",
    readerSource: source.name,
    today,
    exhibitions,
    featuredId: pickFeatured(sourceOrdered),
  };
  fs.writeFileSync(OUTPUT, JSON.stringify(out, null, 2));
  console.log(`[fetch-exhibitions] wrote ${OUTPUT} from seed (${exhibitions.length} entries)`);
}

async function run(todayOverride) {
  const readerSource = resolveExhibitionReaderSource();
  const url = (process.env.SUPABASE_URL || "").trim();
  const key = (process.env.SUPABASE_ANON_KEY || "").trim();

  if (!url || !key) {
    writeFromSeed("env vars absent", todayOverride, readerSource);
    return;
  }

  const today = effectiveToday(todayOverride);
  let rows;
  try {
    rows = await fetchAllExhibitions({
      baseUrl: url,
      key,
      readerSource,
      onIntegrityMismatch: (error) => {
        console.warn(`[fetch-exhibitions] ${error.message}; retrying the complete fetch once`);
      },
    });
  } catch (err) {
    writeFromSeed(err.message, todayOverride, readerSource);
    return;
  }

  const sourceOrdered = enrich(
    rows.map(({ content_checksum_sha256: _transportChecksum, ...row }) => row),
    today
  );
  const exhibitions = sortForPresentation(sourceOrdered);
  const out = {
    fetchedAt: new Date().toISOString(),
    source: "supabase",
    readerSource: readerSource.name,
    today,
    exhibitions,
    featuredId: pickFeatured(sourceOrdered),
  };

  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  fs.writeFileSync(OUTPUT, JSON.stringify(out, null, 2));
  console.log(`[fetch-exhibitions] wrote ${OUTPUT} from supabase (${exhibitions.length} entries)`);
}

if (require.main === module) {
  run().catch((err) => {
    console.error("[fetch-exhibitions] unexpected error:", err);
    if (err instanceof ExhibitionReaderSourceConfigurationError) {
      process.exitCode = 1;
      return;
    }
    if (REQUIRE_LIVE_DATA) process.exit(1);
    writeFromSeed("unexpected error", undefined, resolveExhibitionReaderSource());
  });
}

module.exports = {
  CountMismatchError,
  IntegrityMismatchError,
  ReaderIntegrityMismatchError,
  buildExhibitionPageUrl,
  buildReaderIntegrityUrl,
  checksumExhibitionContent,
  checksumExhibitionIds,
  fetchAllExhibitions,
  fetchAllExhibitionsOnce,
  fetchReaderIntegrity,
  parseExactCount,
  parseReaderIntegrityBody,
  pickFeatured,
  postgrestFilterLiteral,
  run,
  sortForPresentation,
};
