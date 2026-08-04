import type { ImpactBackend } from "./backend.ts";
import { createImpactHandler } from "./handler.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

class RecordingBackend implements ImpactBackend {
  ids: string[] = [];
  result = true;

  record(exhibitionId: string): Promise<boolean> {
    this.ids.push(exhibitionId);
    return Promise.resolve(this.result);
  }
}

function handler(backend: RecordingBackend) {
  return createImpactHandler({
    env: (name) =>
      name === "IMPACT_ALLOWED_ORIGINS" ? "https://gallrmap.com" : "test",
    createBackend: () => backend,
  });
}

function request(
  body = JSON.stringify({ exhibition_id: "exhibition-one" }),
  origin = "https://gallrmap.com",
  method = "POST",
): Request {
  return new Request(
    "https://project.supabase.co/functions/v1/record-exhibition-view",
    {
      method,
      headers: { Origin: origin, "Content-Type": "application/json" },
      body: method === "POST" ? body : undefined,
    },
  );
}

Deno.test("records only the canonical exhibition ID and returns no body", async () => {
  const backend = new RecordingBackend();
  const response = await handler(backend)(request());
  assert(response.status === 204, "expected recorded response");
  assert((await response.text()) === "", "response leaked a payload");
  assert(backend.ids.join(",") === "exhibition-one", "wrong ID recorded");
  assert(
    response.headers.get("access-control-allow-origin") ===
      "https://gallrmap.com",
    "CORS origin missing",
  );
});

Deno.test("forwards the hosted secret key map to the impact backend", async () => {
  const backend = new RecordingBackend();
  const environments: Record<string, string>[] = [];
  const impact = createImpactHandler({
    env: (name) =>
      name === "IMPACT_ALLOWED_ORIGINS"
        ? "https://gallrmap.com"
        : name === "SUPABASE_SECRET_KEYS"
        ? '{"default":"secret"}'
        : undefined,
    createBackend: (value) => {
      environments.push(value);
      return backend;
    },
  });

  assert((await impact(request())).status === 204, "impact call failed");
  assert(
    environments[0]?.SUPABASE_SECRET_KEYS === '{"default":"secret"}',
    "secret map not forwarded",
  );
});

Deno.test("does not reveal whether a valid ID was counted", async () => {
  const backend = new RecordingBackend();
  backend.result = false;
  const response = await handler(backend)(request());
  assert(response.status === 204, "expected opaque accepted response");
  assert((await response.text()) === "", "response leaked a payload");
});

Deno.test("rejects foreign origins before touching the backend", async () => {
  const backend = new RecordingBackend();
  const response = await handler(backend)(
    request(undefined, "https://attacker.invalid"),
  );
  assert(response.status === 403, "foreign origin was accepted");
  assert(backend.ids.length === 0, "foreign origin touched backend");
  assert(
    response.headers.get("access-control-allow-origin") === null,
    "foreign CORS reflected",
  );
});

Deno.test("rejects extra fields, malformed IDs, methods, and oversized requests", async () => {
  const backend = new RecordingBackend();
  const invalid = await handler(backend)(request(JSON.stringify({
    exhibition_id: "exhibition-one",
    visitor_id: "must-not-exist",
  })));
  assert(invalid.status === 400, "extra visitor field was accepted");

  const spaced = await handler(backend)(
    request(JSON.stringify({ exhibition_id: " bad " })),
  );
  assert(spaced.status === 400, "non-canonical ID was accepted");

  const get = await handler(backend)(request(undefined, undefined, "GET"));
  assert(get.status === 405, "GET was accepted");

  const large = await handler(backend)(request(JSON.stringify({
    exhibition_id: "x".repeat(600),
  })));
  assert(large.status === 413, "oversized body was accepted");
  assert(backend.ids.length === 0, "invalid requests touched backend");
});

Deno.test("answers a valid CORS preflight without creating a backend", async () => {
  const backend = new RecordingBackend();
  const response = await handler(backend)(
    request(undefined, undefined, "OPTIONS"),
  );
  assert(response.status === 204, "preflight failed");
  assert(backend.ids.length === 0, "preflight touched backend");
});
