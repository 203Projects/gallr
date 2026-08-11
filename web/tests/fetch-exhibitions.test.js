// Unit tests for scripts/fetch-exhibitions.js — use a deterministic PostgREST stub.
// Run: node tests/fetch-exhibitions.test.js

const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");
const os = require("os");
const crypto = require("crypto");

const ROOT = path.join(__dirname, "..");
const SCRIPT = path.join(ROOT, "scripts", "fetch-exhibitions.js");
const API_HEADERS_MODULE = path.join(ROOT, "scripts", "supabase-api-headers.js");
const API_KEY_MODULE = path.join(ROOT, "scripts", "supabase-public-api-key.js");
const SOURCE_MODULE = path.join(ROOT, "scripts", "lib", "exhibition-reader-source.js");

delete process.env.VERCEL;
delete process.env.GALLR_REQUIRE_LIVE_DATA;
delete process.env.GALLR_EXHIBITION_SOURCE;
const {
  CountMismatchError,
  ReaderIntegrityMismatchError,
  buildExhibitionPageUrl,
  buildReaderIntegrityUrl,
  checksumExhibitionContent,
  checksumExhibitionIds,
  fetchAllExhibitions,
  fetchAllExhibitionsOnce,
  parseReaderIntegrityBody,
  pickFeatured,
  postgrestFilterLiteral,
  sortForPresentation,
} = require(SCRIPT);
const {
  CANONICAL_V2_EXHIBITION_READER_SOURCE,
  LEGACY_EXHIBITION_READER_SOURCE,
  resolveExhibitionReaderSource,
} = require(SOURCE_MODULE);

function row(i, overrides = {}) {
  return {
    id: `id-${i}-aaaa-bbbb`,
    name_ko: `전시 ${i}`,
    name_en: `Show ${i}`,
    venue_name_ko: `갤러리 ${i}`,
    venue_name_en: `Gallery ${i}`,
    city_ko: i % 2 === 0 ? "서울" : "부산",
    address_ko: `주소 ${i}`,
    opening_date: "2026-04-01",
    closing_date: "2026-08-01",
    cover_image_url: `https://stub/exhibitions/${i}.jpg`,
    description_ko: i === 1 ? "한글 설명" : "",
    description_en: i === 1 ? "English description" : "",
    ticket_url: i === 1 ? "https://tickets.example/1" : null,
    is_featured: i === 1,
    ...overrides,
  };
}

function pagedRow(i, overrides = {}) {
  return row(String(i).padStart(4, "0"), overrides);
}

function contentChecksum(value) {
  return crypto.createHash("sha256").update(String(value), "utf8").digest("hex");
}

function canonicalRow(i, overrides = {}) {
  return pagedRow(i, {
    content_checksum_sha256: contentChecksum(`canonical-content-${i}`),
    ...overrides,
  });
}

function stubResponse(body, { status = 200, total = 0, start = 0, url = "" } = {}) {
  const contentRange = Array.isArray(body) && body.length > 0
    ? `${start}-${start + body.length - 1}/${total}`
    : `*/${total}`;
  return {
    ok: status >= 200 && status < 300,
    status,
    url,
    headers: {
      get(name) {
        return name.toLowerCase() === "content-range" ? contentRange : null;
      },
    },
    json: async () => body,
  };
}

function decodePostgrestFilterLiteral(value) {
  if (!(value.startsWith('"') && value.endsWith('"'))) return value;
  let decoded = "";
  for (let index = 1; index < value.length - 1; index += 1) {
    if (value[index] === "\\" && index + 1 < value.length - 1) index += 1;
    decoded += value[index];
  }
  return decoded;
}

function createKeysetFetch(rows, {
  serverCap = 500,
  rowsByAttempt = [rows],
  totalByAttempt,
  integrityRowsByAttempt = rowsByAttempt,
  integrityBodyByAttempt,
  integrityStatusByAttempt = [],
} = {}) {
  let attempt = 0;
  const calls = [];
  const pageCalls = [];
  const integrityCalls = [];
  const fetchImpl = async (url, options) => {
    const parsed = new URL(url);
    const isLegacyIntegrity = parsed.pathname.endsWith("/rpc/exhibition_reader_integrity");
    const isCanonicalIntegrity = parsed.pathname.endsWith(
      "/rpc/exhibition_catalog_v2_integrity"
    );
    if (isLegacyIntegrity || isCanonicalIntegrity) {
      const attemptIndex = Math.max(attempt - 1, 0);
      const integrityRows = integrityRowsByAttempt[
        Math.min(attemptIndex, integrityRowsByAttempt.length - 1)
      ];
      const configuredBody = integrityBodyByAttempt && integrityBodyByAttempt[
        Math.min(attemptIndex, integrityBodyByAttempt.length - 1)
      ];
      const body = configuredBody === undefined
        ? [{
          row_count: integrityRows.length,
          id_checksum_sha256: checksumExhibitionIds(integrityRows),
          ...(isCanonicalIntegrity
            ? { catalog_checksum_sha256: checksumExhibitionContent(integrityRows) }
            : {}),
        }]
        : configuredBody;
      const status = integrityStatusByAttempt[
        Math.min(attemptIndex, integrityStatusByAttempt.length - 1)
      ] || 200;
      const call = { kind: "integrity", url, options, parsed, attempt };
      calls.push(call);
      integrityCalls.push(call);
      return stubResponse(body, { status, total: 1, url });
    }

    const cursorFilter = parsed.searchParams.get("id");
    if (cursorFilter === null) attempt += 1;
    const attemptIndex = attempt - 1;
    const attemptRows = rowsByAttempt[Math.min(attemptIndex, rowsByAttempt.length - 1)];
    const afterId = cursorFilter === null
      ? null
      : decodePostgrestFilterLiteral(cursorFilter.replace(/^gt\./, ""));
    const requestedLimit = Number(parsed.searchParams.get("limit"));
    const pageLimit = Math.min(requestedLimit, serverCap);
    const start = afterId === null
      ? 0
      : attemptRows.findIndex((candidate) => candidate.id > afterId);
    const safeStart = start === -1 ? attemptRows.length : start;
    const page = attemptRows.slice(safeStart, safeStart + pageLimit);
    const totals = totalByAttempt || rowsByAttempt.map((attemptSet) => attemptSet.length);
    const total = totals[Math.min(attemptIndex, totals.length - 1)];
    const call = { kind: "page", url, options, parsed, afterId, page, attempt };
    calls.push(call);
    pageCalls.push(call);
    return stubResponse(page, { total, start: safeStart, url });
  };
  fetchImpl.calls = calls;
  fetchImpl.pageCalls = pageCalls;
  fetchImpl.integrityCalls = integrityCalls;
  return fetchImpl;
}

async function withStubbedFetch(fetchImpl, fn) {
  const original = global.fetch;
  global.fetch = fetchImpl;
  try {
    await fn();
  } finally {
    global.fetch = original;
  }
}

async function withEnv(values, fn) {
  const previous = {};
  for (const [name, value] of Object.entries(values)) {
    previous[name] = process.env[name];
    if (value === undefined) delete process.env[name];
    else process.env[name] = value;
  }
  try {
    await fn();
  } finally {
    for (const [name, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  }
}

async function inTempDir(fn) {
  const originalCwd = process.cwd();
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fetch-exh-"));
  fs.mkdirSync(path.join(dir, "scripts", "lib"), { recursive: true });
  fs.copyFileSync(SCRIPT, path.join(dir, "scripts", "fetch-exhibitions.js"));
  fs.copyFileSync(API_HEADERS_MODULE, path.join(dir, "scripts", "supabase-api-headers.js"));
  fs.copyFileSync(API_KEY_MODULE, path.join(dir, "scripts", "supabase-public-api-key.js"));
  fs.copyFileSync(path.join(ROOT, "scripts", "lib", "status.js"), path.join(dir, "scripts", "lib", "status.js"));
  fs.copyFileSync(path.join(ROOT, "scripts", "lib", "slug.js"), path.join(dir, "scripts", "lib", "slug.js"));
  fs.copyFileSync(SOURCE_MODULE, path.join(dir, "scripts", "lib", "exhibition-reader-source.js"));
  fs.writeFileSync(
    path.join(dir, "scripts", "exhibitions-seed.json"),
    JSON.stringify({ exhibitions: [row(99)] })
  );
  try {
    await fn(dir);
  } finally {
    process.chdir(originalCwd);
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

function loadTempScript(dir) {
  const script = path.join(dir, "scripts", "fetch-exhibitions.js");
  delete require.cache[require.resolve(script)];
  return require(script);
}

(async () => {
  // Reader selection is a closed source pair. Unset/blank remains legacy and
  // unknown values cannot become arbitrary PostgREST resources.
  {
    assert.equal(resolveExhibitionReaderSource(), LEGACY_EXHIBITION_READER_SOURCE);
    assert.equal(resolveExhibitionReaderSource(""), LEGACY_EXHIBITION_READER_SOURCE);
    assert.equal(resolveExhibitionReaderSource(" legacy "), LEGACY_EXHIBITION_READER_SOURCE);
    assert.equal(
      resolveExhibitionReaderSource("canonical-v2"),
      CANONICAL_V2_EXHIBITION_READER_SOURCE
    );
    for (const invalid of ["canonical", "exhibition_catalog_v2", "LEGACY", 2, {}]) {
      assert.throws(
        () => resolveExhibitionReaderSource(invalid),
        /invalid GALLR_EXHIBITION_SOURCE/
      );
    }

    const legacyPage = new URL(buildExhibitionPageUrl("https://stub"));
    const canonicalPage = new URL(buildExhibitionPageUrl("https://stub", {
      readerSource: CANONICAL_V2_EXHIBITION_READER_SOURCE,
    }));
    const canonicalCompatibilityPage = new URL(buildExhibitionPageUrl("https://stub", {
      readerSource: CANONICAL_V2_EXHIBITION_READER_SOURCE,
      includeCredits: false,
    }));
    const canonicalIntegrity = new URL(buildReaderIntegrityUrl("https://stub", {
      readerSource: CANONICAL_V2_EXHIBITION_READER_SOURCE,
    }));
    assert.equal(legacyPage.pathname, "/rest/v1/exhibitions");
    assert.equal(
      legacyPage.searchParams.get("select").includes("content_checksum_sha256"),
      false
    );
    assert.equal(canonicalPage.pathname, "/rest/v1/exhibition_catalog_v2");
    assert.equal(
      canonicalPage.searchParams.get("select").split(",").at(-1),
      "content_checksum_sha256"
    );
    assert.equal(
      canonicalCompatibilityPage.searchParams.get("select").includes("credits_ko"),
      false
    );
    assert.equal(
      canonicalCompatibilityPage.searchParams.get("select").includes("credits_en"),
      false
    );
    assert.equal(
      canonicalCompatibilityPage.searchParams.get("select").split(",").at(-1),
      "content_checksum_sha256"
    );
    assert.equal(
      canonicalIntegrity.pathname,
      "/rest/v1/rpc/exhibition_catalog_v2_integrity"
    );
    assert.equal(
      postgrestFilterLiteral('id.with,reserved:(chars)\\and"quote'),
      'id.with,reserved:(chars)\\and"quote'
    );
    const reservedValue = 'id.with,reserved:(chars)\\and"quote';
    const reservedPage = new URL(buildExhibitionPageUrl("https://stub", {
      afterId: reservedValue,
      filters: [["event_id", `eq.${reservedValue}`]],
    }));
    assert.equal(
      reservedPage.searchParams.get("id"),
      `gt.${postgrestFilterLiteral(reservedValue)}`
    );
    assert.equal(
      reservedPage.searchParams.get("event_id"),
      `eq.${postgrestFilterLiteral(reservedValue)}`
    );
    const encodedReservedPage = reservedPage.toString();
    for (const encoded of ["%2C", "%28", "%29", "%3A", "%5C", "%22"]) {
      assert.ok(
        encodedReservedPage.includes(encoded),
        `URLSearchParams must encode reserved scalar content as ${encoded}`
      );
    }
  }

  // A rolling schema deployment can temporarily expose the previous catalog
  // shape. Retry the complete read without the two new optional credit fields
  // only when PostgREST explicitly reports one of those columns as undefined.
  {
    const rows = [canonicalRow(1), canonicalRow(2)];
    const pageCalls = [];
    const integrityCalls = [];
    let compatibilityRetryCount = 0;
    const fetchImpl = async (url, options) => {
      const parsed = new URL(url);
      if (parsed.pathname.endsWith("/rpc/exhibition_catalog_v2_integrity")) {
        integrityCalls.push({ parsed, options });
        return stubResponse([{
          row_count: rows.length,
          id_checksum_sha256: checksumExhibitionIds(rows),
          catalog_checksum_sha256: checksumExhibitionContent(rows),
        }], { total: 1, url });
      }

      pageCalls.push({ parsed, options });
      const selected = parsed.searchParams.get("select").split(",");
      if (selected.includes("credits_ko") || selected.includes("credits_en")) {
        return stubResponse({
          code: "42703",
          details: null,
          hint: null,
          message: "column exhibition_catalog_v2.credits_ko does not exist",
        }, { status: 400, url });
      }

      const afterId = parsed.searchParams.get("id");
      return afterId === null
        ? stubResponse(rows, { total: rows.length, url })
        : stubResponse([], { total: rows.length, start: rows.length, url });
    };

    const result = await fetchAllExhibitions({
      baseUrl: "https://stub",
      key: "stub",
      fetchImpl,
      readerSource: CANONICAL_V2_EXHIBITION_READER_SOURCE,
      onOptionalColumnsUnavailable: () => { compatibilityRetryCount += 1; },
    });

    assert.deepEqual(result, rows);
    assert.equal(compatibilityRetryCount, 1);
    assert.equal(pageCalls.length, 3, "one failed page plus a complete two-page retry");
    assert.equal(
      pageCalls.filter((call) => call.parsed.searchParams.get("select").includes("credits_ko")).length,
      1
    );
    assert.ok(pageCalls.slice(1).every(
      (call) => !call.parsed.searchParams.get("select").includes("credits_ko")
        && !call.parsed.searchParams.get("select").includes("credits_en")
    ));
    assert.equal(integrityCalls.length, 1, "the compatibility retry retains integrity validation");
  }

  // Fetches all 1,205 rows despite a server cap below the requested 500,
  // keeps filters/cursors on every page, and terminates only on an empty page.
  {
    const rows = Array.from({ length: 1205 }, (_, index) => pagedRow(index + 1));
    const fetchImpl = createKeysetFetch(rows, { serverCap: 173 });
    const result = await fetchAllExhibitionsOnce({
      baseUrl: "https://stub.example/base/",
      key: "stub key",
      fetchImpl,
      filters: [
        ["event_id", "eq.한 남"],
        ["is_featured", "eq.true"],
      ],
    });

    assert.equal(result.length, 1205);
    assert.equal(new Set(result.map((item) => item.id)).size, 1205);
    assert.equal(fetchImpl.pageCalls.length, 8, "seven capped data pages plus one empty page");
    assert.equal(fetchImpl.pageCalls.at(-1).page.length, 0, "must observe an explicit empty page");
    assert.equal(fetchImpl.pageCalls[0].parsed.searchParams.get("order"), "id.asc");
    assert.equal(fetchImpl.pageCalls[0].parsed.searchParams.get("limit"), "500");
    assert.equal(fetchImpl.pageCalls[0].options.headers.Prefer, "count=exact");
    assert.equal(fetchImpl.pageCalls[1].options.headers.Prefer, undefined);
    assert.equal(fetchImpl.pageCalls[0].parsed.searchParams.get("id"), null);
    assert.equal(
      fetchImpl.pageCalls[1].parsed.searchParams.get("id"),
      `gt.${rows[172].id}`
    );
    for (const call of fetchImpl.pageCalls) {
      assert.equal(call.parsed.searchParams.get("event_id"), "eq.한 남");
      assert.equal(call.parsed.searchParams.get("is_featured"), "eq.true");
      assert.ok(!call.url.includes("한 남"), "query values must be URL encoded");
    }
    assert.equal(fetchImpl.integrityCalls.length, 1);
    const integrityCall = fetchImpl.integrityCalls[0];
    assert.equal(integrityCall.parsed.searchParams.get("p_event_id"), "한 남");
    assert.equal(integrityCall.parsed.searchParams.get("p_featured_only"), "true");
    assert.ok(!integrityCall.url.includes("한 남"), "typed RPC arguments must be URL encoded");
    assert.ok(
      fetchImpl.calls.every((call) => call.options.redirect === "error"),
      "every data and integrity request must reject redirects"
    );
  }

  // Every response must expose the exact requested final URL. This rejects
  // cross-origin and same-origin redirects even if a fetch implementation
  // ignores redirect:"error".
  for (const { finalUrl, expectedError } of [
    { finalUrl: "", expectedError: /missing its final URL/ },
    { finalUrl: "https://redirected.example/rest/v1/exhibitions", expectedError: /changed origin/ },
    { finalUrl: "https://stub.example/rest/v1/other", expectedError: /differs from the requested URL/ },
  ]) {
    let observedOptions;
    await assert.rejects(
      fetchAllExhibitionsOnce({
        baseUrl: "https://stub.example",
        key: "stub",
        fetchImpl: async (_url, options) => {
          observedOptions = options;
          return stubResponse([], { total: 0, url: finalUrl });
        },
      }),
      expectedError
    );
    assert.equal(observedOptions.redirect, "error");
  }

  // The former 1,000-row truncation boundary is complete on both sides.
  for (const { count, expectedCalls } of [
    { count: 999, expectedCalls: 3 },
    { count: 1000, expectedCalls: 3 },
    { count: 1001, expectedCalls: 4 },
  ]) {
    const rows = Array.from({ length: count }, (_, index) => pagedRow(index + 1));
    const fetchImpl = createKeysetFetch(rows);
    const result = await fetchAllExhibitionsOnce({
      baseUrl: "https://stub",
      key: "stub",
      fetchImpl,
    });
    assert.equal(result.length, count, `${count} rows must be returned without truncation`);
    assert.equal(new Set(result.map((item) => item.id)).size, count);
    assert.equal(fetchImpl.pageCalls.length, expectedCalls);
    assert.equal(fetchImpl.pageCalls.at(-1).page.length, 0, `${count} rows must end on an empty page`);
    assert.equal(fetchImpl.integrityCalls.length, 1);
  }

  // Checksum framing matches the database's UTF-8 byte-length-prefixed stream.
  {
    assert.equal(
      checksumExhibitionIds(["integrity-a", "integrity-b", "integrity-c"]),
      "53043595cba7eb4efae06a02df2562f8579967e5de1678defe18a81a4ef17e49"
    );
    assert.equal(
      checksumExhibitionIds(["a", "한"]),
      "8ecc8bcfdc9a3be0293571877b083ca85a2476903e9f5e5a48571e182c3c0658"
    );
    assert.equal(
      checksumExhibitionContent([
        { id: "a", content_checksum_sha256: "0".repeat(64) },
        { id: "한", content_checksum_sha256: "f".repeat(64) },
      ]),
      "724ef22d4634c028b7d328bbec538783a6bed1ddb1807d8ff88b8d7bc458b3ff"
    );
  }

  // Presentation ordering is opening_date DESC with verified transport-order ties.
  {
    const sorted = sortForPresentation([
      row("z", { opening_date: "2026-06-01" }),
      row("d", { opening_date: null }),
      row("a", { opening_date: "2026-06-01" }),
      row("c", { opening_date: "2026-07-01" }),
    ]);
    assert.deepEqual(
      sorted.map((item) => item.id),
      ["id-c-aaaa-bbbb", "id-z-aaaa-bbbb", "id-a-aaaa-bbbb", "id-d-aaaa-bbbb"]
    );
    assert.equal(
      pickFeatured([
        row("z", { is_featured: true }),
        row("a", { is_featured: true }),
      ]),
      "id-z-aaaa-bbbb",
      "featured ties preserve verified transport order"
    );
  }

  // Duplicate/non-advancing cursors are rejected instead of looping or truncating.
  {
    let call = 0;
    const fetchImpl = async (url) => {
      call += 1;
      if (call === 1) return stubResponse([pagedRow(1), pagedRow(2)], { total: 3, url });
      return stubResponse([pagedRow(2), pagedRow(3)], { total: 3, start: 2, url });
    };
    await assert.rejects(
      fetchAllExhibitionsOnce({ baseUrl: "https://stub", key: "stub", fetchImpl }),
      /duplicate exhibition id/
    );
  }

  // PostgreSQL owns keyset ordering; a valid DB sequence need not match JS lexical order.
  {
    const databaseOrderedRows = [row("z"), row("a"), row("m")];
    const cursors = [];
    let pageCall = 0;
    const fetchImpl = async (url) => {
      const parsed = new URL(url);
      if (parsed.pathname.endsWith("/rpc/exhibition_reader_integrity")) {
        return stubResponse([{
          row_count: databaseOrderedRows.length,
          id_checksum_sha256: checksumExhibitionIds(databaseOrderedRows),
        }], { total: 1, url });
      }
      cursors.push(parsed.searchParams.get("id"));
      pageCall += 1;
      if (pageCall === 1) {
        return stubResponse(databaseOrderedRows.slice(0, 2), { total: 3, url });
      }
      if (pageCall === 2) {
        return stubResponse(databaseOrderedRows.slice(2), { total: 3, start: 2, url });
      }
      return stubResponse([], { total: 3, start: 3, url });
    };
    const result = await fetchAllExhibitionsOnce({
      baseUrl: "https://stub",
      key: "stub",
      fetchImpl,
    });
    assert.deepEqual(
      result.map((item) => item.id),
      databaseOrderedRows.map((item) => item.id)
    );
    assert.deepEqual(
      cursors,
      [
        null,
        `gt.${databaseOrderedRows[1].id}`,
        `gt.${databaseOrderedRows[2].id}`,
      ]
    );
  }

  // Malformed pages and missing/blank IDs fail closed.
  {
    await assert.rejects(
      fetchAllExhibitionsOnce({
        baseUrl: "https://stub",
        key: "stub",
        fetchImpl: async (url) => stubResponse({ rows: [] }, { total: 0, url }),
      }),
      /non-array response/
    );
    for (const invalidId of [undefined, "", "   "]) {
      await assert.rejects(
        fetchAllExhibitionsOnce({
          baseUrl: "https://stub",
          key: "stub",
          fetchImpl: async (url) => stubResponse(
            [{ ...pagedRow(1), id: invalidId }],
            { total: 1, url }
          ),
        }),
        /missing or blank id/
      );
    }
  }

  // An exact-count mismatch restarts from page one once; a second mismatch fails.
  {
    const rows = [pagedRow(1), pagedRow(2), pagedRow(3)];
    const recoveredFetch = createKeysetFetch(rows, { serverCap: 2, totalByAttempt: [4, 3] });
    let retryCount = 0;
    const result = await fetchAllExhibitions({
      baseUrl: "https://stub",
      key: "stub",
      fetchImpl: recoveredFetch,
      onCountMismatch: () => { retryCount += 1; },
    });
    assert.equal(result.length, 3);
    assert.equal(retryCount, 1);
    assert.equal(recoveredFetch.pageCalls.filter((call) => call.afterId === null).length, 2);

    const failedFetch = createKeysetFetch(rows, { serverCap: 2, totalByAttempt: [4, 4] });
    await assert.rejects(
      fetchAllExhibitions({ baseUrl: "https://stub", key: "stub", fetchImpl: failedFetch }),
      (error) => error instanceof CountMismatchError && error.expected === 4 && error.actual === 3
    );
    assert.equal(failedFetch.pageCalls.filter((call) => call.afterId === null).length, 2);
  }

  // A same-count membership replacement is detected by checksum and fully retried.
  {
    const originalRows = [pagedRow(1), pagedRow(2), pagedRow(3)];
    const replacementRows = [pagedRow(1), pagedRow(2), pagedRow(4)];
    const recoveredFetch = createKeysetFetch(originalRows, {
      serverCap: 2,
      rowsByAttempt: [originalRows, replacementRows],
      integrityRowsByAttempt: [replacementRows, replacementRows],
    });
    let retryCount = 0;
    const result = await fetchAllExhibitions({
      baseUrl: "https://stub",
      key: "stub",
      fetchImpl: recoveredFetch,
      onIntegrityMismatch: (error) => {
        retryCount += 1;
        assert.ok(error instanceof ReaderIntegrityMismatchError);
        assert.equal(error.field, "id_checksum_sha256");
      },
    });
    assert.deepEqual(result.map((item) => item.id), replacementRows.map((item) => item.id));
    assert.equal(retryCount, 1);
    assert.equal(recoveredFetch.pageCalls.filter((call) => call.afterId === null).length, 2);
    assert.equal(recoveredFetch.integrityCalls.length, 2);

    const failedFetch = createKeysetFetch(originalRows, {
      serverCap: 2,
      rowsByAttempt: [originalRows, originalRows],
      integrityRowsByAttempt: [replacementRows, replacementRows],
    });
    await assert.rejects(
      fetchAllExhibitions({ baseUrl: "https://stub", key: "stub", fetchImpl: failedFetch }),
      (error) => error instanceof ReaderIntegrityMismatchError
        && error.field === "id_checksum_sha256"
    );
    assert.equal(failedFetch.pageCalls.filter((call) => call.afterId === null).length, 2);
    assert.equal(failedFetch.integrityCalls.length, 2);
  }

  // Canonical v2 detects a same-ID field generation change, retries every page,
  // and then verifies the resource/RPC pair plus the catalog checksum.
  {
    const originalRows = [canonicalRow(1), canonicalRow(2), canonicalRow(3)];
    const changedRows = originalRows.map((item, index) => index === 1
      ? {
        ...item,
        name_ko: "수정된 전시",
        content_checksum_sha256: contentChecksum("canonical-content-2-revised"),
      }
      : item);
    const recoveredFetch = createKeysetFetch(originalRows, {
      serverCap: 2,
      rowsByAttempt: [originalRows, changedRows],
      integrityRowsByAttempt: [changedRows, changedRows],
    });
    let mismatch;
    const result = await fetchAllExhibitions({
      baseUrl: "https://stub",
      key: "stub",
      fetchImpl: recoveredFetch,
      readerSource: CANONICAL_V2_EXHIBITION_READER_SOURCE,
      onIntegrityMismatch: (error) => { mismatch = error; },
    });
    assert.ok(mismatch instanceof ReaderIntegrityMismatchError);
    assert.equal(mismatch.field, "catalog_checksum_sha256");
    assert.deepEqual(
      result.map((item) => item.content_checksum_sha256),
      changedRows.map((item) => item.content_checksum_sha256)
    );
    assert.equal(recoveredFetch.pageCalls.filter((call) => call.afterId === null).length, 2);
    assert.equal(recoveredFetch.integrityCalls.length, 2);
    assert.ok(recoveredFetch.pageCalls.every(
      (call) => call.parsed.pathname.endsWith("/exhibition_catalog_v2")
    ));
    assert.ok(recoveredFetch.integrityCalls.every(
      (call) => call.parsed.pathname.endsWith("/rpc/exhibition_catalog_v2_integrity")
    ));

    const permanentlyStaleFetch = createKeysetFetch(originalRows, {
      serverCap: 2,
      rowsByAttempt: [originalRows, originalRows],
      integrityRowsByAttempt: [changedRows, changedRows],
    });
    await assert.rejects(
      fetchAllExhibitions({
        baseUrl: "https://stub",
        key: "stub",
        fetchImpl: permanentlyStaleFetch,
        readerSource: CANONICAL_V2_EXHIBITION_READER_SOURCE,
      }),
      (error) => error instanceof ReaderIntegrityMismatchError &&
        error.field === "catalog_checksum_sha256"
    );
    assert.equal(permanentlyStaleFetch.integrityCalls.length, 2);
  }

  // Canonical rows must carry valid lowercase per-row checksums before any
  // prefix can be returned. Malformed transport data is not retryable.
  for (const invalidChecksum of [undefined, null, "", "ABC", "A".repeat(64)]) {
    let fetchCalls = 0;
    await assert.rejects(
      fetchAllExhibitions({
        baseUrl: "https://stub",
        key: "stub",
        readerSource: CANONICAL_V2_EXHIBITION_READER_SOURCE,
        fetchImpl: async (url) => {
          fetchCalls += 1;
          return stubResponse([
            canonicalRow(1, { content_checksum_sha256: invalidChecksum }),
          ], { total: 1, url });
        },
      }),
      /invalid content_checksum_sha256/
    );
    assert.equal(fetchCalls, 1);
  }

  // Malformed snapshot RPC payloads fail closed and are not treated as retryable mismatches.
  {
    const checksum = checksumExhibitionIds([]);
    for (const invalidBody of [
      [],
      [{ row_count: 0, id_checksum_sha256: checksum }, { row_count: 0, id_checksum_sha256: checksum }],
      [null],
      [{}],
      [{ row_count: "0", id_checksum_sha256: checksum }],
      [{ row_count: -1, id_checksum_sha256: checksum }],
      [{ row_count: 0, id_checksum_sha256: checksum.toUpperCase() }],
      [{ row_count: 0, id_checksum_sha256: "abc" }],
    ]) {
      assert.throws(() => parseReaderIntegrityBody(invalidBody), /reader integrity RPC/);
    }

    const rows = [pagedRow(1)];
    const fetchImpl = createKeysetFetch(rows, {
      integrityBodyByAttempt: [[{ row_count: 1, id_checksum_sha256: "malformed" }]],
    });
    await assert.rejects(
      fetchAllExhibitions({ baseUrl: "https://stub", key: "stub", fetchImpl }),
      /checksum must be 64 lowercase hexadecimal characters/
    );
    assert.equal(fetchImpl.integrityCalls.length, 1, "malformed RPC responses are not retried");

    const canonicalRows = [canonicalRow(1)];
    const canonicalIdChecksum = checksumExhibitionIds(canonicalRows);
    const canonicalCatalogChecksum = checksumExhibitionContent(canonicalRows);
    for (const invalidBody of [
      [{ row_count: 1, id_checksum_sha256: canonicalIdChecksum }],
      [{
        row_count: 1,
        id_checksum_sha256: canonicalIdChecksum,
        catalog_checksum_sha256: "invalid",
      }],
      [{
        row_count: 1,
        id_checksum_sha256: canonicalIdChecksum,
        catalog_checksum_sha256: canonicalCatalogChecksum,
        unexpected: true,
      }],
    ]) {
      assert.throws(
        () => parseReaderIntegrityBody(invalidBody, {
          readerSource: CANONICAL_V2_EXHIBITION_READER_SOURCE,
        }),
        /reader integrity RPC/
      );
    }
  }

  // Integrity scopes accept only the typed filters implemented by the RPC contract.
  for (const filters of [
    [["event_id", "neq.event"]],
    [["is_featured", "eq.false"]],
    [["venue_name_ko", "eq.갤러리"]],
  ]) {
    let fetchCalls = 0;
    await assert.rejects(
      fetchAllExhibitionsOnce({
        baseUrl: "https://stub",
        key: "stub",
        filters,
        fetchImpl: async () => { fetchCalls += 1; },
      }),
      /exhibition filter|integrity filter/
    );
    assert.equal(fetchCalls, 0, "invalid integrity scope must fail before page one");
  }

  // The exact count is mandatory; a missing Content-Range cannot silently pass.
  {
    await assert.rejects(
      fetchAllExhibitionsOnce({
        baseUrl: "https://stub",
        key: "stub",
        fetchImpl: async (url) => ({
          ok: true,
          status: 200,
          url,
          headers: new Headers(),
          json: async () => [],
        }),
      }),
      /missing or malformed Content-Range/
    );
  }

  // Writes enriched, presentation-sorted data after all pages complete.
  await inTempDir(async (dir) => {
    const rows = [
      row(1, { opening_date: "2026-04-01" }),
      row(2, { opening_date: "2026-06-01" }),
      row(3, { opening_date: "2026-05-01" }),
    ].sort((a, b) => a.id < b.id ? -1 : 1);
    const fetchImpl = createKeysetFetch(rows, { serverCap: 2 });
    await withEnv(
      { VERCEL: undefined, SUPABASE_URL: "https://stub", SUPABASE_PUBLISHABLE_KEY: "stub" },
      async () => withStubbedFetch(fetchImpl, async () => {
        process.chdir(dir);
        await loadTempScript(dir).run("2026-06-15");
      })
    );
    const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
    assert.equal(out.exhibitions.length, 3);
    assert.equal(out.source, "supabase");
    assert.equal(out.readerSource, "legacy");
    assert.deepEqual(out.exhibitions.map((item) => item.id), [rows[1].id, rows[2].id, rows[0].id]);
    for (const exhibition of out.exhibitions) {
      assert.match(exhibition.slug, /^show-\d+-id-\d+$/);
      assert.ok(["current", "opening_soon", "closing_soon", "closed"].includes(exhibition.status));
    }
    assert.equal(out.featuredId, "id-1-aaaa-bbbb");
  });

  // The canonical build records its source but strips the transport-only row
  // checksum from generated exhibition data.
  await inTempDir(async (dir) => {
    const rows = [canonicalRow(1), canonicalRow(2), canonicalRow(3)];
    const fetchImpl = createKeysetFetch(rows, { serverCap: 2 });
    await withEnv(
      {
        VERCEL: undefined,
        SUPABASE_URL: "https://stub",
        SUPABASE_PUBLISHABLE_KEY: "stub",
        GALLR_EXHIBITION_SOURCE: "canonical-v2",
      },
      async () => withStubbedFetch(fetchImpl, async () => {
        process.chdir(dir);
        await loadTempScript(dir).run("2026-06-15");
      })
    );
    const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
    assert.equal(out.source, "supabase");
    assert.equal(out.readerSource, "canonical-v2");
    assert.equal(out.exhibitions.length, 3);
    assert.ok(out.exhibitions.every(
      (exhibition) => !("content_checksum_sha256" in exhibition)
    ));
    assert.ok(fetchImpl.pageCalls.every(
      (call) => call.parsed.pathname.endsWith("/exhibition_catalog_v2")
    ));
  });

  // A count-and-checksum-verified empty catalog is valid in dev and production.
  for (const vercel of [undefined, "1"]) {
    await inTempDir(async (dir) => {
      const fetchImpl = createKeysetFetch([]);
      await withEnv(
        { VERCEL: vercel, SUPABASE_URL: "https://stub", SUPABASE_PUBLISHABLE_KEY: "stub" },
        async () => withStubbedFetch(fetchImpl, async () => {
          process.chdir(dir);
          await loadTempScript(dir).run("2026-06-15");
        })
      );
      const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
      assert.equal(fetchImpl.pageCalls.length, 1, "empty catalog terminates on page one");
      assert.equal(fetchImpl.pageCalls[0].page.length, 0);
      assert.equal(fetchImpl.integrityCalls.length, 1);
      assert.equal(out.source, "supabase");
      assert.deepEqual(out.exhibitions, []);
      assert.equal(out.featuredId, null);
    });
  }

  // A page-two failure writes only the existing development seed, never a partial prefix.
  await inTempDir(async (dir) => {
    let call = 0;
    const fetchImpl = async (url) => {
      call += 1;
      if (call === 1) return stubResponse([row(1), row(2)], { total: 3, url });
      return stubResponse([], { status: 503, total: 3, url });
    };
    await withEnv(
      { VERCEL: undefined, SUPABASE_URL: "https://stub", SUPABASE_PUBLISHABLE_KEY: "stub" },
      async () => withStubbedFetch(fetchImpl, async () => {
        process.chdir(dir);
        await loadTempScript(dir).run("2026-06-15");
      })
    );
    const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
    assert.equal(out.source, "seed");
    assert.deepEqual(out.exhibitions.map((item) => item.id), ["id-99-aaaa-bbbb"]);
  });

  // An RPC failure after the empty terminal page also discards the fetched prefix.
  await inTempDir(async (dir) => {
    const rows = [row(1), row(2), row(3)];
    const fetchImpl = createKeysetFetch(rows, { serverCap: 2, integrityStatusByAttempt: [503] });
    await withEnv(
      { VERCEL: undefined, SUPABASE_URL: "https://stub", SUPABASE_PUBLISHABLE_KEY: "stub" },
      async () => withStubbedFetch(fetchImpl, async () => {
        process.chdir(dir);
        await loadTempScript(dir).run("2026-06-15");
      })
    );
    const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
    assert.equal(fetchImpl.pageCalls.at(-1).page.length, 0);
    assert.equal(fetchImpl.integrityCalls.length, 1);
    assert.equal(out.source, "seed");
    assert.deepEqual(out.exhibitions.map((item) => item.id), ["id-99-aaaa-bbbb"]);
  });

  // A production page-two failure exits before creating any fallback/partial output.
  await inTempDir(async (dir) => {
    let call = 0;
    const fetchImpl = async (url) => {
      call += 1;
      if (call === 1) return stubResponse([row(1), row(2)], { total: 3, url });
      return stubResponse([], { status: 503, total: 3, url });
    };
    let exitCode;
    const originalExit = process.exit;
    await withEnv(
      { VERCEL: "1", SUPABASE_URL: "https://stub", SUPABASE_PUBLISHABLE_KEY: "stub" },
      async () => withStubbedFetch(fetchImpl, async () => {
        process.exit = (code) => {
          exitCode = code;
          throw new Error("exit");
        };
        process.chdir(dir);
        try {
          await loadTempScript(dir).run("2026-06-15");
        } catch (error) {
          assert.equal(error.message, "exit");
        } finally {
          process.exit = originalExit;
        }
      })
    );
    assert.equal(exitCode, 1);
    assert.equal(fs.existsSync(path.join(dir, "_data", "exhibitions.json")), false);
  });

  // Missing env vars retain the current development fallback policy.
  await inTempDir(async (dir) => {
    await withEnv(
      { VERCEL: undefined, SUPABASE_URL: undefined, SUPABASE_PUBLISHABLE_KEY: undefined },
      async () => {
        process.chdir(dir);
        await loadTempScript(dir).run();
      }
    );
    const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
    assert.equal(out.source, "seed");
    assert.equal(out.readerSource, "legacy");
    assert.equal(out.exhibitions.length, 1);
  });

  // A mistyped source is a configuration failure even when local seed fallback
  // would otherwise be allowed.
  await inTempDir(async (dir) => {
    await withEnv(
      {
        VERCEL: undefined,
        SUPABASE_URL: undefined,
        SUPABASE_PUBLISHABLE_KEY: undefined,
        GALLR_EXHIBITION_SOURCE: "canonical-v3",
      },
      async () => {
        process.chdir(dir);
        await assert.rejects(loadTempScript(dir).run(), /invalid GALLR_EXHIBITION_SOURCE/);
      }
    );
    assert.equal(fs.existsSync(path.join(dir, "_data", "exhibitions.json")), false);
  });

  // A production build with missing env vars still exits non-zero.
  await inTempDir(async (dir) => {
    let exitCode;
    const originalExit = process.exit;
    await withEnv(
      { VERCEL: "1", SUPABASE_URL: undefined, SUPABASE_PUBLISHABLE_KEY: undefined },
      async () => {
        process.exit = (code) => {
          exitCode = code;
          throw new Error("exit");
        };
        process.chdir(dir);
        try {
          await loadTempScript(dir).run();
        } catch (error) {
          assert.equal(error.message, "exit");
        } finally {
          process.exit = originalExit;
        }
      }
    );
    assert.equal(exitCode, 1);
  });

  // Featured fallback remains deterministic when no row is explicitly featured.
  await inTempDir(async (dir) => {
    const rows = [
      row(1, { is_featured: false, opening_date: "2026-01-01", closing_date: "2026-12-31" }),
      row(2, { is_featured: false, opening_date: "2026-04-01", closing_date: "2026-12-31" }),
      row(3, { is_featured: false, opening_date: "2026-05-01", closing_date: "2026-12-31" }),
    ];
    const fetchImpl = createKeysetFetch(rows);
    await withEnv(
      { VERCEL: undefined, SUPABASE_URL: "https://stub", SUPABASE_PUBLISHABLE_KEY: "stub" },
      async () => withStubbedFetch(fetchImpl, async () => {
        process.chdir(dir);
        await loadTempScript(dir).run("2026-06-15");
      })
    );
    const out = JSON.parse(fs.readFileSync(path.join(dir, "_data", "exhibitions.json"), "utf8"));
    assert.equal(out.featuredId, "id-3-aaaa-bbbb");
  });

  console.log("[fetch-exhibitions.test] all tests passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
