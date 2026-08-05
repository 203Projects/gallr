import { createLegacyCatalogMirrorBackend } from "./backend.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const sourceUrl = "https://oqrvbstopuppznxqoonp.supabase.co";
const targetUrl = "https://yhuhjxswjbrtmbpbrciq.supabase.co";
const receiverUrl = `${targetUrl}/functions/v1/legacy-catalog-mirror-receiver`;

Deno.test("backend reads only reviewed catalogue resources and applies one snapshot", async () => {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const backend = createLegacyCatalogMirrorBackend({
    SUPABASE_URL: sourceUrl,
    SUPABASE_SECRET_KEY: "source-secret",
    LEGACY_CATALOG_RECEIVER_URL: receiverUrl,
    LEGACY_CATALOG_RECEIVER_TOKEN: "receiver-token-with-enough-entropy-123456",
    LEGACY_CATALOG_MIRROR_REASON: "test automation",
  }, (input, init) => {
    const url = String(input);
    calls.push({ url, init });
    if (url === receiverUrl) {
      return Promise.resolve(
        new Response(JSON.stringify({ status: "applied" }), { status: 200 }),
      );
    }
    const resource = new URL(url).pathname.split("/").at(-1);
    const rows = resource === "exhibitions" ? [{ id: "show" }] : [];
    return Promise.resolve(new Response(JSON.stringify(rows), { status: 200 }));
  });

  await backend.mirror("outbox");

  assert(calls.length === 4, "unexpected request count");
  assert(
    calls.slice(0, 3).every((call) => new URL(call.url).origin === sourceUrl),
    "catalogue was read from an unreviewed source",
  );
  const apply = calls[3];
  assert(apply.url === receiverUrl, "snapshot sent to wrong receiver");
  assert(apply.init?.method === "POST", "snapshot was not POSTed");
  const body = JSON.parse(String(apply.init?.body));
  assert(
    body.p_source_project_ref === "oqrvbstopuppznxqoonp",
    "wrong source ref",
  );
  assert(body.p_snapshot.exhibitions.length === 1, "snapshot was incomplete");
});

Deno.test("backend keeps replicated event images on the Singapore storage origin", async () => {
  let received: Record<string, unknown> | undefined;
  const backend = createLegacyCatalogMirrorBackend({
    SUPABASE_URL: sourceUrl,
    SUPABASE_SECRET_KEY: "source-secret",
    LEGACY_CATALOG_RECEIVER_URL: receiverUrl,
    LEGACY_CATALOG_RECEIVER_TOKEN: "receiver-token-with-enough-entropy-123456",
    LEGACY_CATALOG_MIRROR_REASON: "test automation",
  }, (input, init) => {
    const url = String(input);
    if (url === receiverUrl) {
      received = JSON.parse(String(init?.body));
      return Promise.resolve(
        new Response(JSON.stringify({ status: "applied" }), { status: 200 }),
      );
    }
    const resource = new URL(url).pathname.split("/").at(-1);
    const rows = resource === "exhibitions"
      ? [{ id: "show" }]
      : resource === "events"
      ? [{
        id: "event",
        cover_image_url:
          `${sourceUrl}/storage/v1/object/public/event-images/hero.png`,
      }]
      : [];
    return Promise.resolve(new Response(JSON.stringify(rows), { status: 200 }));
  });

  await backend.mirror("outbox");

  const snapshot = received?.p_snapshot as {
    events: Array<{ cover_image_url: string }>;
  };
  assert(
    snapshot.events[0].cover_image_url ===
      `${targetUrl}/storage/v1/object/public/event-images/hero.png`,
    "event media was not localized to Singapore storage",
  );
});

Deno.test("backend refuses swapped projects and empty source catalogues", async () => {
  let message = "";
  try {
    createLegacyCatalogMirrorBackend({
      SUPABASE_URL: targetUrl,
      SUPABASE_SECRET_KEY: "source-secret",
      LEGACY_CATALOG_RECEIVER_URL:
        `${sourceUrl}/functions/v1/legacy-catalog-mirror-receiver`,
      LEGACY_CATALOG_RECEIVER_TOKEN:
        "receiver-token-with-enough-entropy-123456",
      LEGACY_CATALOG_MIRROR_REASON: "test automation",
    }, () => Promise.resolve(new Response("[]")));
  } catch (error) {
    message = error instanceof Error ? error.message : String(error);
  }
  assert(
    message === "Mirror project configuration is invalid.",
    "swapped projects accepted",
  );

  const backend = createLegacyCatalogMirrorBackend({
    SUPABASE_URL: sourceUrl,
    SUPABASE_SECRET_KEY: "source-secret",
    LEGACY_CATALOG_RECEIVER_URL: receiverUrl,
    LEGACY_CATALOG_RECEIVER_TOKEN: "receiver-token-with-enough-entropy-123456",
    LEGACY_CATALOG_MIRROR_REASON: "test automation",
  }, () => Promise.resolve(new Response("[]", { status: 200 })));
  await backend.mirror("outbox").then(
    () => {
      throw new Error("empty source catalogue accepted");
    },
    (error) => {
      assert(error instanceof Error, "missing backend error");
      assert(
        error.message === "Source catalogue is empty.",
        "unexpected empty error",
      );
    },
  );
});
