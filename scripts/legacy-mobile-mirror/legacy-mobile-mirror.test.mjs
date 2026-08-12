import assert from "node:assert/strict";
import test from "node:test";

import {
  buildSnapshot,
  diffResource,
  LEGACY_PROJECT_REF,
  readConfig,
  runMirror,
  SEOUL_PROJECT_REF,
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
    () =>
      readConfig({
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
    exhibition_catalog_v2: [{ id: "canonical-z" }, { id: "canonical-a" }],
    events: [{ id: "event-b" }, { id: "event-a" }],
    editors: [{ id: "editor-b" }, { id: "editor-a" }],
  });

  assert.deepEqual(snapshot.exhibitions.map((row) => row.id), ["a", "z"]);
  assert.deepEqual(
    snapshot.exhibition_catalog_v2.map((row) => row.id),
    ["canonical-a", "canonical-z"],
  );
  assert.deepEqual(snapshot.events.map((row) => row.id), [
    "event-a",
    "event-b",
  ]);
  assert.deepEqual(snapshot.editors.map((row) => row.id), [
    "editor-a",
    "editor-b",
  ]);
});

test("resource diff reports inserted, updated, and deleted IDs without row data", () => {
  assert.deepEqual(
    diffResource(
      [{ id: "same", value: 1 }, { id: "changed", value: 2 }, {
        id: "new",
        value: 3,
      }],
      [{ id: "same", value: 1 }, { id: "changed", value: 1 }, {
        id: "old",
        value: 4,
      }],
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
    const rows = table === "exhibitions" || table === "exhibition_catalog_v2"
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
  assert.equal(calls.length, 8);
  assert.ok(calls.every((call) => call.method === "GET"));
  assert.ok(calls.every((call) => !call.url.includes("service_replace")));
  const exhibitionReads = calls.filter((call) =>
    ["exhibitions", "exhibition_catalog_v2"].includes(
      new URL(call.url).pathname.split("/").at(-1),
    )
  );
  assert.equal(exhibitionReads.length, 4);
  assert.ok(exhibitionReads.every((call) =>
    new URL(call.url).searchParams.get("select").split(",").includes(
      "country_code",
    )
  ));
});

test("regional event-image hosts compare as the same target-local media", async () => {
  const fakeFetch = async (url) => {
    const parsed = new URL(url);
    const table = parsed.pathname.split("/").at(-1);
    if (table === "exhibitions" || table === "exhibition_catalog_v2") {
      return new Response(JSON.stringify([{ id: "show" }]), { status: 200 });
    }
    if (table === "events") {
      const projectRef = parsed.hostname.split(".")[0];
      return new Response(
        JSON.stringify([{
          id: "event",
          cover_image_url:
            `https://${projectRef}.supabase.co/storage/v1/object/public/event-images/hero.png`,
        }]),
        { status: 200 },
      );
    }
    return new Response("[]", { status: 200 });
  };
  const env = {
    GALLR_SEOUL_SUPABASE_URL: `https://${SEOUL_PROJECT_REF}.supabase.co`,
    GALLR_SEOUL_SECRET_KEY: "source-secret",
    GALLR_LEGACY_SUPABASE_URL: `https://${LEGACY_PROJECT_REF}.supabase.co`,
    GALLR_LEGACY_SECRET_KEY: "target-secret",
    GALLR_LEGACY_MIRROR_REASON: "test change record",
  };

  const result = await runMirror({ env, apply: false, fetchImpl: fakeFetch });

  assert.deepEqual(result.diff.events, {
    source: 1,
    target: 1,
    insert: 0,
    update: 0,
    delete: 0,
    changed_fields: {},
  });
});

test("apply sends exactly one complete snapshot to the guarded RPC", async () => {
  const calls = [];
  const fakeFetch = async (url, options = {}) => {
    calls.push({ url: String(url), options });
    if (String(url).includes("/rpc/service_replace_legacy_mobile_catalog")) {
      return new Response(
        JSON.stringify({ status: "applied", exhibition_count: 1 }),
        {
          status: 200,
          headers: { "content-type": "application/json" },
        },
      );
    }
    const table = new URL(url).pathname.split("/").at(-1);
    return new Response(
      JSON.stringify(
        table === "exhibitions" || table === "exhibition_catalog_v2"
          ? [{ id: "show" }]
          : [],
      ),
      {
        status: 200,
        headers: { "content-type": "application/json" },
      },
    );
  };
  const env = {
    GALLR_SEOUL_SUPABASE_URL: `https://${SEOUL_PROJECT_REF}.supabase.co`,
    GALLR_SEOUL_SECRET_KEY: "source-secret",
    GALLR_LEGACY_SUPABASE_URL: `https://${LEGACY_PROJECT_REF}.supabase.co`,
    GALLR_LEGACY_SECRET_KEY: "target-secret",
    GALLR_LEGACY_MIRROR_REASON: "test change record",
  };

  const result = await runMirror({ env, apply: true, fetchImpl: fakeFetch });
  const rpc = calls.find((call) =>
    call.url.includes("/rpc/service_replace_legacy_mobile_catalog")
  );

  assert.equal(result.mode, "apply");
  assert.equal(calls.length, 5);
  assert.equal(rpc.options.method, "POST");
  const body = JSON.parse(rpc.options.body);
  assert.equal(body.p_source_project_ref, SEOUL_PROJECT_REF);
  assert.equal(body.p_snapshot.exhibitions.length, 1);
  assert.equal(body.p_snapshot.exhibition_catalog_v2.length, 1);
  assert.deepEqual(body.p_snapshot.events, []);
  assert.deepEqual(body.p_snapshot.editors, []);
});

test("apply keeps replicated event images on the Singapore storage origin", async () => {
  const calls = [];
  const fakeFetch = async (url, options = {}) => {
    calls.push({ url: String(url), options });
    if (String(url).includes("/rpc/service_replace_legacy_mobile_catalog")) {
      return new Response(
        JSON.stringify({ status: "applied", exhibition_count: 1 }),
        {
          status: 200,
        },
      );
    }
    const table = new URL(url).pathname.split("/").at(-1);
    const rows = table === "exhibitions" || table === "exhibition_catalog_v2"
      ? [{ id: "show" }]
      : table === "events"
      ? [{
        id: "event",
        cover_image_url:
          `https://${SEOUL_PROJECT_REF}.supabase.co/storage/v1/object/public/event-images/hero.png`,
      }]
      : [];
    return new Response(JSON.stringify(rows), { status: 200 });
  };
  const env = {
    GALLR_SEOUL_SUPABASE_URL: `https://${SEOUL_PROJECT_REF}.supabase.co`,
    GALLR_SEOUL_SECRET_KEY: "source-secret",
    GALLR_LEGACY_SUPABASE_URL: `https://${LEGACY_PROJECT_REF}.supabase.co`,
    GALLR_LEGACY_SECRET_KEY: "target-secret",
    GALLR_LEGACY_MIRROR_REASON: "test change record",
  };

  await runMirror({ env, apply: true, fetchImpl: fakeFetch });
  const rpc = calls.find((call) =>
    call.url.includes("service_replace_legacy_mobile_catalog")
  );
  const body = JSON.parse(rpc.options.body);

  assert.equal(
    body.p_snapshot.events[0].cover_image_url,
    `https://${LEGACY_PROJECT_REF}.supabase.co/storage/v1/object/public/event-images/hero.png`,
  );
});
