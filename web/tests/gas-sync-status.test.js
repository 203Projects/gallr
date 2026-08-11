const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const source = fs.readFileSync(path.resolve(__dirname, "../../gas/SyncExhibitions.gs"), "utf8");
const sandbox = {
  module: { exports: {} },
  Logger: { log: () => {} },
  Utilities: {
    DigestAlgorithm: { SHA_256: "SHA_256" },
    computeDigest: () => Array.from({ length: 32 }, (_, i) => i),
  },
  PropertiesService: { getScriptProperties: () => ({ getProperty: () => "https://stub.supabase.co" }) },
  Session: { getScriptTimeZone: () => "Asia/Seoul" },
};

vm.createContext(sandbox);
// Rely on the file's own guarded module.exports block (no source injection).
vm.runInContext(source, sandbox);

const { shouldSyncRow, buildHeaderMap, isFormSourcedRow, buildPostgrestIdList } = sandbox.module.exports;

// Legacy curated rows (no image_url_* columns) — prior behaviour preserved.
const headerMap = buildHeaderMap(["status", "name_ko"]);
assert.equal(shouldSyncRow(["approved", "전시"], headerMap), true);
assert.equal(shouldSyncRow(["pending", "전시"], headerMap), false);
assert.equal(shouldSyncRow(["", "전시"], headerMap), false);
assert.equal(shouldSyncRow(["APPROVED", "전시"], headerMap), true);
assert.equal(shouldSyncRow(["전시"], buildHeaderMap(["name_ko"])), true);

// Fail-closed (S5): a form-sourced row (image_url_1 populated) must have
// status=approved even when there is NO status column on the sheet.
const formNoStatus = buildHeaderMap(["name_ko", "image_url_1"]);
assert.equal(isFormSourcedRow(["전시", "https://x/img.jpg"], formNoStatus), true);
assert.equal(
  shouldSyncRow(["전시", "https://x/img.jpg"], formNoStatus),
  false,
  "form-sourced row with no status column must NOT auto-publish"
);

// Form-sourced row WITH a status column still gated on approved.
const formWithStatus = buildHeaderMap(["status", "name_ko", "image_url_1"]);
assert.equal(shouldSyncRow(["approved", "전시", "https://x/img.jpg"], formWithStatus), true);
assert.equal(shouldSyncRow(["pending", "전시", "https://x/img.jpg"], formWithStatus), false);
assert.equal(shouldSyncRow(["", "전시", "https://x/img.jpg"], formWithStatus), false);

// Empty image_url cell → treated as a normal (non-form) row.
assert.equal(isFormSourcedRow(["전시", ""], formNoStatus), false);
assert.equal(shouldSyncRow(["전시", ""], formNoStatus), true);

// Exhibition sync must not create a read gap where event detail pages and map pins
// briefly lose every participant while the sheet is being reinserted.
assert.ok(
  source.includes("upsertExhibitions(uniqueRows, supabaseUrl, serviceKey)"),
  "SyncExhibitions must upsert before deleting stale rows"
);
assert.ok(
  !source.includes("deleteAllExhibitions(supabaseUrl, serviceKey);"),
  "SyncExhibitions must not delete every row before insert"
);
assert.equal(
  buildPostgrestIdList(["a", "id,with)paren"]),
  "%22a%22,%22id%2Cwith)paren%22"
);
assert.match(
  source,
  /var KNOWN_COLUMNS = \[[\s\S]*['"]country_code['"]/,
  "SyncExhibitions must include country_code in its explicit Supabase column allowlist"
);

console.log("[gas-sync-status.test] all tests passed");
