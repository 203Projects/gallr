import type { PromotedPlacement, PromotionBackend } from "./backend.ts";
import { createPromotionHandler } from "./handler.ts";

function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}

class Backend implements PromotionBackend {
  calls: Array<{ viewerDigest: string; cityKo: string; regionKo: string }> = [];
  result: PromotedPlacement | null = {
    promotion_id: "promotion-one",
    exhibition_id: "between-seasons",
    name_ko: "계절 사이",
    name_en: "Between Seasons",
    venue_name_ko: "아틀리에 한남",
    venue_name_en: "Atelier Hannam",
    city_ko: "서울",
    city_en: "Seoul",
    region_ko: "용산구",
    region_en: "Yongsan-gu",
    opening_date: "2026-08-08",
    closing_date: "2026-09-14",
    cover_image_url: null,
    disclosure: "paid_placement",
  };
  select(viewerDigest: string, cityKo: string, regionKo: string) {
    this.calls.push({ viewerDigest, cityKo, regionKo });
    return Promise.resolve(this.result);
  }
}

function handler(backend: Backend, logs: unknown[] = []) {
  return createPromotionHandler({
    env: (name) =>
      name === "PROMOTION_DELIVERY_ENABLED"
        ? "true"
        : name === "PROMOTION_ALLOWED_ORIGINS"
        ? "https://gallrmap.com,app://gallr"
        : "test",
    digest: () => Promise.resolve("a".repeat(64)),
    log: (value) => logs.push(value),
    createBackend: () => backend,
  });
}

function request(
  payload: unknown = {
    installation_key: "local-installation-key-1234",
    city_ko: "서울",
    region_ko: "용산구",
  },
  origin = "https://gallrmap.com",
  method = "POST",
) {
  return new Request(
    "https://project.supabase.co/functions/v1/promoted-nearby",
    {
      method,
      headers: { Origin: origin, "Content-Type": "application/json" },
      body: method === "POST" ? JSON.stringify(payload) : undefined,
    },
  );
}

Deno.test("hashes the installation key and returns one disclosed placement", async () => {
  const backend = new Backend();
  const response = await handler(backend)(request());
  const body = await response.json();
  assert(response.status === 200, "expected placement response");
  assert(body.placement.name_en === "Between Seasons", "placement missing");
  assert(body.placement.disclosure === "paid_placement", "disclosure missing");
  assert(backend.calls[0].viewerDigest === "a".repeat(64), "digest not passed");
  assert(
    JSON.stringify(backend.calls).includes("local-installation-key") === false,
    "raw installation key reached backend",
  );
  assert(
    response.headers.get("cache-control") === "no-store",
    "response cached",
  );
});

Deno.test("forwards the hosted secret key map to the promotion backend", async () => {
  const backend = new Backend();
  const environments: Record<string, string>[] = [];
  const promotion = createPromotionHandler({
    env: (name) =>
      name === "PROMOTION_DELIVERY_ENABLED"
        ? "true"
        : name === "PROMOTION_ALLOWED_ORIGINS"
        ? "https://gallrmap.com"
        : name === "SUPABASE_SECRET_KEYS"
        ? '{"default":"secret"}'
        : undefined,
    digest: () => Promise.resolve("a".repeat(64)),
    log: () => {},
    createBackend: (value) => {
      environments.push(value);
      return backend;
    },
  });

  assert((await promotion(request())).status === 200, "promotion call failed");
  assert(
    environments[0]?.SUPABASE_SECRET_KEYS === '{"default":"secret"}',
    "secret map not forwarded",
  );
});

for (const deliverySetting of [undefined, "false"] as const) {
  Deno.test(
    `returns no placement before digest or backend work when delivery is ${
      deliverySetting ?? "absent"
    }`,
    async () => {
      const backend = new Backend();
      let digestCalls = 0;
      let backendCreations = 0;
      const promotion = createPromotionHandler({
        env: (name) =>
          name === "PROMOTION_DELIVERY_ENABLED"
            ? deliverySetting
            : name === "PROMOTION_ALLOWED_ORIGINS"
            ? "https://gallrmap.com"
            : undefined,
        digest: () => {
          digestCalls += 1;
          return Promise.resolve("a".repeat(64));
        },
        log: () => {},
        createBackend: () => {
          backendCreations += 1;
          return backend;
        },
      });

      const response = await promotion(request());

      assert(
        response.status === 204,
        "disabled delivery did not return no content",
      );
      assert(
        (await response.text()) === "",
        "disabled delivery leaked a response body",
      );
      assert(
        digestCalls === 0,
        "disabled delivery hashed the installation key",
      );
      assert(backendCreations === 0, "disabled delivery constructed a backend");
      assert(
        backend.calls.length === 0,
        "disabled delivery selected a placement",
      );
    },
  );
}

Deno.test("returns an opaque no-content response when capped or irrelevant", async () => {
  const backend = new Backend();
  backend.result = null;
  const response = await handler(backend)(request());
  assert(response.status === 204, "expected no placement response");
  assert((await response.text()) === "", "empty response leaked data");
});

Deno.test("requires coarse locality and rejects precise or extra targeting", async () => {
  const backend = new Backend();
  const missing = await handler(backend)(request({
    installation_key: "local-installation-key-1234",
    city_ko: "",
    region_ko: "",
  }));
  assert(missing.status === 400, "empty locality accepted");
  const precise = await handler(backend)(request({
    installation_key: "local-installation-key-1234",
    city_ko: "서울",
    latitude: 37.5,
    longitude: 127,
  }));
  assert(precise.status === 400, "precise coordinates accepted");
  assert(backend.calls.length === 0, "invalid targeting reached backend");
});

Deno.test("rejects foreign origins, malformed keys, methods, and oversized input", async () => {
  const backend = new Backend();
  assert(
    (await handler(backend)(request(undefined, "https://attacker.invalid")))
      .status === 403,
    "foreign origin accepted",
  );
  assert(
    (await handler(backend)(request({
      installation_key: "short",
      city_ko: "서울",
      region_ko: "",
    }))).status === 400,
    "short key accepted",
  );
  assert(
    (await handler(backend)(request(undefined, undefined, "GET"))).status ===
      405,
    "GET accepted",
  );
  assert(
    (await handler(backend)(request({
      installation_key: "x".repeat(600),
      city_ko: "서울",
      region_ko: "",
    }))).status === 413,
    "oversized input accepted",
  );
  assert(backend.calls.length === 0, "invalid request reached backend");
});

Deno.test("logs only structured outcomes and never the installation key", async () => {
  const logs: unknown[] = [];
  await handler(new Backend(), logs)(request());
  const encoded = JSON.stringify(logs);
  assert(encoded.includes("promotion.delivery"), "structured event missing");
  assert(!encoded.includes("local-installation-key"), "raw key was logged");
});

Deno.test("answers CORS preflight without constructing a backend", async () => {
  const backend = new Backend();
  const response = await handler(backend)(
    request(undefined, undefined, "OPTIONS"),
  );
  assert(response.status === 204, "preflight failed");
  assert(backend.calls.length === 0, "preflight touched backend");
});
