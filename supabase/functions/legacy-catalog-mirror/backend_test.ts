import { createLegacyCatalogMirrorBackend } from "./backend.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const sourceUrl = "https://oqrvbstopuppznxqoonp.supabase.co";
const targetUrl = "https://yhuhjxswjbrtmbpbrciq.supabase.co";
const receiverUrl = `${targetUrl}/functions/v1/legacy-catalog-mirror-receiver`;

Deno.test("backend reads both installed-client catalogues and applies one snapshot", async () => {
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
    const rows = resource === "exhibitions"
      ? [{ id: "legacy-show" }]
      : resource === "exhibition_catalog_v2"
      ? [{
        id: "canonical-show",
        country_code: "KR",
        city_ko: "서울",
        city_en: "Seoul",
        gallery_id: "6c808761-287f-429c-a14a-bd2b4689fea5",
        artists: [{
          id: "d79286e1-e87d-4cf0-a6b0-0f89d54d2f75",
          name_ko: "작가",
          name_en: "Artist",
        }],
        art_terms: [{
          id: "medium:painting",
          category: "medium",
          name_ko: "회화",
          name_en: "Painting",
        }],
        content_checksum_sha256: "a".repeat(64),
      }]
      : [];
    return Promise.resolve(new Response(JSON.stringify(rows), { status: 200 }));
  });

  await backend.mirror("outbox");

  assert(calls.length === 5, "unexpected request count");
  assert(
    calls.slice(0, 4).every((call) => new URL(call.url).origin === sourceUrl),
    "catalogue was read from an unreviewed source",
  );
  const canonicalRequest = calls.find((call) =>
    new URL(call.url).pathname.endsWith("/exhibition_catalog_v2")
  );
  assert(canonicalRequest, "canonical-v2 catalogue was not read");
  assert(
    new URL(canonicalRequest.url).searchParams.get("select")?.includes(
      "content_checksum_sha256",
    ),
    "canonical-v2 integrity checksum was omitted",
  );
  assert(
    new URL(canonicalRequest.url).searchParams.get("select")?.includes(
      "country_code",
    ),
    "canonical-v2 country identity was omitted",
  );
  // The canonical checksum hashes the whole row. Dropping gallery_id from the
  // projection silently breaks checksum parity on every mirrored row, which is
  // exactly how the Aug 2026 mirror outage happened.
  assert(
    new URL(canonicalRequest.url).searchParams.get("select")?.includes(
      "gallery_id",
    ),
    "canonical-v2 gallery identity was omitted",
  );
  assert(
    new URL(canonicalRequest.url).searchParams.get("select")?.includes(
      "artists",
    ) && new URL(canonicalRequest.url).searchParams.get("select")?.includes(
      "art_terms",
    ),
    "canonical-v2 art metadata was omitted",
  );
  const legacyRequest = calls.find((call) =>
    new URL(call.url).pathname.endsWith("/exhibitions")
  );
  assert(legacyRequest, "legacy catalogue was not read");
  assert(
    new URL(legacyRequest.url).searchParams.get("select")?.includes(
      "country_code",
    ),
    "legacy country identity was omitted",
  );
  const apply = calls[4];
  assert(apply.url === receiverUrl, "snapshot sent to wrong receiver");
  assert(apply.init?.method === "POST", "snapshot was not POSTed");
  const body = JSON.parse(String(apply.init?.body));
  assert(
    body.p_source_project_ref === "oqrvbstopuppznxqoonp",
    "wrong source ref",
  );
  assert(body.p_snapshot.exhibitions.length === 1, "snapshot was incomplete");
  assert(
    body.p_snapshot.exhibition_catalog_v2[0].city_en === "Seoul",
    "canonical-v2 city normalization was omitted",
  );
  assert(
    body.p_snapshot.exhibition_catalog_v2[0].country_code === "KR",
    "canonical-v2 country identity was omitted from the snapshot",
  );
  assert(
    body.p_snapshot.exhibition_catalog_v2[0].gallery_id ===
      "6c808761-287f-429c-a14a-bd2b4689fea5",
    "canonical-v2 gallery identity was omitted from the snapshot",
  );
  assert(
    body.p_snapshot.exhibition_catalog_v2[0].content_checksum_sha256 ===
      "a".repeat(64),
    "canonical-v2 checksum was omitted",
  );
  assert(
    body.p_snapshot.exhibition_catalog_v2[0].artists[0].name_en === "Artist" &&
      body.p_snapshot.exhibition_catalog_v2[0].art_terms[0].id ===
        "medium:painting",
    "canonical-v2 art metadata was omitted from the snapshot",
  );
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
    const rows = resource === "exhibitions" ||
        resource === "exhibition_catalog_v2"
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

Deno.test("backend refuses an empty canonical-v2 source catalogue", async () => {
  const backend = createLegacyCatalogMirrorBackend({
    SUPABASE_URL: sourceUrl,
    SUPABASE_SECRET_KEY: "source-secret",
    LEGACY_CATALOG_RECEIVER_URL: receiverUrl,
    LEGACY_CATALOG_RECEIVER_TOKEN: "receiver-token-with-enough-entropy-123456",
    LEGACY_CATALOG_MIRROR_REASON: "test automation",
  }, (input) => {
    const resource = new URL(String(input)).pathname.split("/").at(-1);
    const rows = resource === "exhibitions" ? [{ id: "legacy-show" }] : [];
    return Promise.resolve(new Response(JSON.stringify(rows), { status: 200 }));
  });

  await backend.mirror("outbox").then(
    () => {
      throw new Error("empty canonical-v2 source catalogue accepted");
    },
    (error) => {
      assert(error instanceof Error, "missing backend error");
      assert(
        error.message === "Source canonical-v2 catalogue is empty.",
        "unexpected canonical-v2 empty error",
      );
    },
  );
});
