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
vm.runInContext(`${source}\nmodule.exports = { shouldSyncRow, buildHeaderMap };`, sandbox);

const { shouldSyncRow, buildHeaderMap } = sandbox.module.exports;
const headerMap = buildHeaderMap(["status", "name_ko"]);

assert.equal(shouldSyncRow(["approved", "전시"], headerMap), true);
assert.equal(shouldSyncRow(["pending", "전시"], headerMap), false);
assert.equal(shouldSyncRow(["", "전시"], headerMap), false);
assert.equal(shouldSyncRow(["APPROVED", "전시"], headerMap), true);
assert.equal(shouldSyncRow(["전시"], buildHeaderMap(["name_ko"])), true);

console.log("[gas-sync-status.test] all tests passed");
