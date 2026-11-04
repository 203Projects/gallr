import { assertBoundedBody } from "../_shared/body_test_helpers.ts";
import type {
  LaunchRsvpBackend,
  PublicLaunchKit,
  PublicRsvpInput,
} from "./backend.ts";
import { createLaunchRsvpHandler } from "./handler.ts";

function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}

const token = "b4000000-0000-4000-8000-000000000001";
const publicPresentation: PublicLaunchKit = {
  exhibition_id: "exhibition-one",
  name_ko: "전시",
  name_en: "Exhibition",
  venue_name_ko: "갤러리",
  venue_name_en: "Gallery",
  address_ko: "서울",
  address_en: "Seoul",
  cover_image_url: "https://cdn.example.invalid/cover.jpg",
  description_ko: "전시 설명",
  description_en: "Exhibition description",
  opening_date: "2026-08-20",
  closing_date: "2026-09-20",
  hours: "11:00-18:00",
  contact: "hello@gallery.example",
  reception_date: "2026-09-01",
  reception_start_time: "19:00",
};
class Backend implements LaunchRsvpBackend {
  inputs: PublicRsvpInput[] = [];
  get(): Promise<PublicLaunchKit> {
    return Promise.resolve(publicPresentation);
  }
  submit(input: PublicRsvpInput): Promise<boolean> {
    this.inputs.push(input);
    return Promise.resolve(true);
  }
}
function handler(backend: Backend) {
  return createLaunchRsvpHandler({
    env: (name) => name === "RSVP_HASH_SECRET" ? "x".repeat(32) : undefined,
    digest: () => Promise.resolve("a".repeat(64)),
    createBackend: () => backend,
  });
}
function request(
  method: string,
  body?: unknown,
  origin = "https://gallrmap.com",
) {
  return new Request(
    `https://project.supabase.co/functions/v1/launch-rsvp?token=${token}`,
    {
      method,
      headers: {
        Origin: origin,
        "Content-Type": "application/json",
        "X-Forwarded-For": "203.0.113.9",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    },
  );
}

Deno.test("loads only the public RSVP presentation", async () => {
  const response = await handler(new Backend())(request("GET"));
  const body = await response.json() as Record<string, unknown>;
  assert(response.status === 200 && body.launchKit, "public kit missing");
  const kit = body.launchKit as Record<string, unknown>;
  assert(
    kit.cover_image_url === "https://cdn.example.invalid/cover.jpg" &&
      kit.description_ko === "전시 설명" &&
      kit.opening_date === "2026-08-20" &&
      kit.closing_date === "2026-09-20" &&
      kit.hours === "11:00-18:00" &&
      kit.contact === "hello@gallery.example",
    "essential public exhibition details missing",
  );
  assert(
    !("gallery_id" in kit) && !("payment" in kit) && !("guests" in kit),
    "private fields leaked",
  );
});

Deno.test("forwards the hosted secret key map to the RSVP backend", async () => {
  const backend = new Backend();
  const environments: Record<string, string>[] = [];
  const rsvp = createLaunchRsvpHandler({
    env: (name) =>
      name === "SUPABASE_SECRET_KEYS" ? '{"default":"secret"}' : undefined,
    createBackend: (value) => {
      environments.push(value);
      return backend;
    },
  });

  assert((await rsvp(request("GET"))).status === 200, "RSVP load failed");
  assert(
    environments[0]?.SUPABASE_SECRET_KEYS === '{"default":"secret"}',
    "secret map not forwarded",
  );
});

Deno.test("submits bounded RSVP fields with only a keyed source digest", async () => {
  const backend = new Backend();
  const response = await handler(backend)(request("POST", {
    name: "Maya Chen",
    email: "maya@example.com",
    party_size: 2,
    privacy_acknowledged: true,
  }));
  assert(response.status === 204, "RSVP failed");
  assert(
    backend.inputs[0]?.sourceDigest === "a".repeat(64),
    "raw source persisted",
  );
  assert(
    !("ip" in (backend.inputs[0] as unknown as Record<string, unknown>)),
    "IP leaked",
  );
});

Deno.test("rejects foreign origins, invalid tokens, missing consent, and extra fields", async () => {
  const backend = new Backend();
  assert(
    (await handler(backend)(
      request("GET", undefined, "https://attacker.invalid"),
    )).status === 403,
    "origin accepted",
  );
  const invalidToken = new Request(
    "https://project.supabase.co/functions/v1/launch-rsvp?token=bad",
    { headers: { Origin: "https://gallrmap.com" } },
  );
  assert(
    (await handler(backend)(invalidToken)).status === 404,
    "token accepted",
  );
  assert(
    (await handler(backend)(
      request("POST", {
        name: "M",
        email: "m@example.com",
        party_size: 1,
        privacy_acknowledged: false,
      }),
    )).status === 400,
    "missing consent accepted",
  );
  assert(
    (await handler(backend)(
      request("POST", {
        name: "M",
        email: "m@example.com",
        party_size: 1,
        privacy_acknowledged: true,
        tracking_id: "x",
      }),
    )).status === 400,
    "tracking field accepted",
  );
  assert(backend.inputs.length === 0, "invalid requests touched backend");
});

Deno.test("UUID spellings share the same RSVP quota without merging other sources or kits", async () => {
  const canonical = "abcdefab-cdef-4abc-8def-abcdefabcdef";
  const spellings = [
    canonical,
    canonical.toUpperCase(),
    "AbCdEfAb-cDeF-4aBc-8DeF-aBcDeFaBcDeF",
    canonical.replace("a", "%61"),
  ];
  const buckets = new Map<string, number>();
  const inputs: PublicRsvpInput[] = [];
  const lookups: string[] = [];
  const rsvp = createLaunchRsvpHandler({
    env: (name) => name === "RSVP_HASH_SECRET" ? "x".repeat(32) : undefined,
    createBackend: () => ({
      get: (value) => {
        lookups.push(value);
        return Promise.resolve(publicPresentation);
      },
      submit: (input) => {
        inputs.push(input);
        const hits = (buckets.get(input.sourceDigest) ?? 0) + 1;
        buckets.set(input.sourceDigest, hits);
        if (hits > 10) throw new Error("launch_rsvp_rate_limited");
        return Promise.resolve(true);
      },
    }),
  });
  const call = (spelling: string, method = "POST", source = "203.0.113.9") =>
    rsvp(
      new Request(`https://example.invalid/?token=${spelling}`, {
        method,
        headers: {
          Origin: "https://gallrmap.com",
          "Content-Type": "application/json",
          "CF-Connecting-IP": source,
        },
        body: method === "POST"
          ? JSON.stringify({
            name: "Guest",
            email: `guest${inputs.length}@example.com`,
            party_size: 1,
            privacy_acknowledged: true,
          })
          : undefined,
      }),
    );
  for (let index = 0; index < 10; index += 1) {
    assert(
      (await call(spellings[index % spellings.length])).status === 204,
      "valid RSVP refused",
    );
  }
  assert(
    (await call(spellings[1])).status === 429,
    "case variant bypassed quota",
  );
  assert(
    new Set(inputs.map((input) => input.sourceDigest)).size === 1,
    "UUID spellings split quota identity",
  );
  assert(
    inputs.every((input) => input.token === canonical),
    "backend token was not canonical",
  );
  assert(
    (await call(canonical, "POST", "203.0.113.10")).status === 204,
    "another source shared the quota",
  );
  assert(
    (await call("bbcdefab-cdef-4abc-8def-abcdefabcdef")).status === 204,
    "another kit shared the quota",
  );
  for (const spelling of spellings) {
    assert(
      (await call(spelling, "GET")).status === 200,
      "valid invitation failed",
    );
  }
  assert(
    lookups.every((value) => value === canonical),
    "GET lookup was not canonical",
  );
});

Deno.test("bounds streamed bodies before buffering, preserving valid JSON", async () => {
  await assertBoundedBody(
    handler(new Backend()),
    request("POST", {
      name: "방문자",
      email: "guest@example.com",
      party_size: 1,
      privacy_acknowledged: true,
    }),
    2048,
    204,
    413,
    "request_too_large",
  );
});
