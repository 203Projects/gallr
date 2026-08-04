import assert from "node:assert/strict";
import test from "node:test";

import {
  LEGACY_PROJECT_REF,
  SEOUL_PROJECT_REF,
  buildSnapshot,
  diffResource,
  readConfig,
  runMirror,
} from "./legacy-mobile-mirror.mjs";

test("configuration fails closed on swapped or unreviewed targets", () => {
  const base = {
    GALLR_SEOUL_SUPABASE_URL: `https://${SEOUL_PROJECT_REF}.supabase.co`,
    GALLR_SEOUL_SECRET_KEY: "source-secret",
    GALLR_LEGACY_SUPABASE_URL: `https://${LEGACY_PROJECT_REF}.supabase.co`,
    GALLR_LEGACY_SECRET_KEY: "target-secret",
    GALLR_LEGACY_MIRROR_REASON: "test change record",
  };

  assert.equal(readConfig(base).sourceRef, SEOUL_PROJECT_REF);
  assert.equal(
    readConfig({
      ...base,
      GALLR_LEGACY_SUPABASE_URL: `${LEGACY_PROJECT_REF}.supabase.co`,
    }).targetUrl,
    `https://${LEGACY_PROJECT_REF}.supabase.co`,
  );
  assert.throws(
    () => readConfig({
      ...base,
      GALLR_LEGACY_SUPABASE_URL: base.GALLR_SEOUL_SUPABASE_URL,
    }),
    /legacy target does not match the reviewed Singapore project/,
  );
  assert.throws(
    () => readConfig({ ...base, GALLR_LEGACY_MIRROR_REASON: "" }),
    /change reason is required/,
  );
});

test("snapshot resources and rows are deterministically ordered", () => {
  const snapshot = buildSnapshot({
    exhibitions: [{ id: "z" }, { id: "a" }],
    events: [{ id: "event-b" }, { id: "event-a" }],
    editors: [{ id: "editor-b" }, { id: "editor-a" }],
  });

  assert.deepEqual(snapshot.exhibitions.map((row) => row.id), ["a", "z"]);
  assert.deepEqual(snapshot.events.map((row) => row.id), ["event-a", "event-b"]);
  assert.deepEqual(snapshot.editors.map((row) => row.id), ["editor-a", "editor-b"]);
});

test("resource diff reports inserted, updated, and deleted IDs without row data", () => {
  assert.deepEqual(
    diffResource(
      [{ id: "same", value: 1 }, { id: "changed", value: 2 }, { id: "new", value: 3 }],
      [{ id: "same", value: 1 }, { id: "changed", value: 1 }, { id: "old", value: 4 }],
    ),
    {
      source: 3,
      target: 3,
      insert: 1,
      update: 1,
      delete: 1,
      changed_fields: { value: 1 },
    },
  );
});

test("dry run reads both projects and never invokes the target RPC", async () => {
  const calls = [];
  const fakeFetch = async (url, options = {}) => {
    calls.push({ url: String(url), method: options.method ?? "GET" });
    const table = new URL(url).pathname.split("/").at(-1);
    const rows = table === "exhibitions"
      ? [{ id: "show", name_ko: "전시" }]
      : [];
    return new Response(JSON.stringify(rows), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  };
  const env = {
    GALLR_SEOUL_SUPABASE_URL: `https://${SEOUL_PROJECT_REF}.supabase.co`,
    GALLR_SEOUL_SECRET_KEY: "source-secret",
    GALLR_LEGACY_SUPABASE_URL: `https://${LEGACY_PROJECT_REF}.supabase.co`,
    GALLR_LEGACY_SECRET_KEY: "target-secret",
    GALLR_LEGACY_MIRROR_REASON: "test change record",
  };

  const result = await runMirror({ env, apply: false, fetchImpl: fakeFetch });

  assert.equal(result.mode, "dry-run");
  assert.equal(calls.length, 6);
  assert.ok(calls.every((call) => call.method === "GET"));
  assert.ok(calls.every((call) => !call.url.includes("service_replace")));
});

test("apply sends exactly one complete snapshot to the guarded RPC", async () => {
  const calls = [];
  const fakeFetch = async (url, options = {}) => {
    calls.push({ url: String(url), options });
    if (String(url).includes("/rpc/service_replace_legacy_mobile_catalog")) {
      return new Response(JSON.stringify({ status: "applied", exhibition_count: 1 }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    const table = new URL(url).pathname.split("/").at(-1);
    return new Response(JSON.stringify(table === "exhibitions" ? [{ id: "show" }] : []), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  };
  const env = {
    GALLR_SEOUL_SUPABASE_URL: `https://${SEOUL_PROJECT_REF}.supabase.co`,
    GALLR_SEOUL_SECRET_KEY: "source-secret",
    GALLR_LEGACY_SUPABASE_URL: `https://${LEGACY_PROJECT_REF}.supabase.co`,
    GALLR_LEGACY_SECRET_KEY: "target-secret",
    GALLR_LEGACY_MIRROR_REASON: "test change record",
  };

  const result = await runMirror({ env, apply: true, fetchImpl: fakeFetch });
  const rpc = calls.find((call) => call.url.includes("/rpc/service_replace_legacy_mobile_catalog"));

  assert.equal(result.mode, "apply");
  assert.equal(calls.length, 4);
  assert.equal(rpc.options.method, "POST");
  const body = JSON.parse(rpc.options.body);
  assert.equal(body.p_source_project_ref, SEOUL_PROJECT_REF);
  assert.equal(body.p_snapshot.exhibitions.length, 1);
  assert.deepEqual(body.p_snapshot.events, []);
  assert.deepEqual(body.p_snapshot.editors, []);
});
