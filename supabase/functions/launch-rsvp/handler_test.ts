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
