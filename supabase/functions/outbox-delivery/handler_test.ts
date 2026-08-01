import { createOutboxDeliveryHandler } from "./handler.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const token = "test-delivery-token-with-enough-entropy-123456";
const hook = "https://api.vercel.com/v1/integrations/deploy/example/project";

type FetchCall = { url: string; init?: RequestInit };

function buildHandler(overrides: {
  configuredToken?: string;
  configuredHook?: string;
  fetchStatus?: number;
} = {}) {
  const calls: FetchCall[] = [];
  const handler = createOutboxDeliveryHandler({
    env: (name) => {
      if (name === "OUTBOX_DELIVERY_TOKEN") {
        return overrides.configuredToken ?? token;
      }
      if (name === "VERCEL_DEPLOY_HOOK_URL") {
        return overrides.configuredHook ?? hook;
      }
      return undefined;
    },
    fetch: (input, init) => {
      calls.push({ url: String(input), init });
      return Promise.resolve(
        new Response(null, {
          status: overrides.fetchStatus ?? 201,
        }),
      );
    },
  });
  return { calls, handler };
}

function request(options: {
  eventType?: string;
  bodyEventType?: string;
  authorization?: string;
  method?: string;
  eventId?: string;
  bodyEventId?: string;
  idempotencyKey?: string;
  body?: string;
} = {}): Request {
  const eventType = options.eventType ?? "exhibition.published";
  const eventId = options.eventId ?? "00000000-0000-4000-8000-000000000001";
  const body = options.body ?? JSON.stringify({
    id: options.bodyEventId ?? eventId,
    event_type: options.bodyEventType ?? eventType,
    aggregate_type: "exhibition",
    aggregate_id: "exhibition-one",
    deduplication_key: "exhibition.published:exhibition-one:1",
    payload: { exhibition_id: "exhibition-one" },
  });
  const method = options.method ?? "POST";
  return new Request(
    "https://project.supabase.co/functions/v1/outbox-delivery",
    {
      method,
      headers: {
        Authorization: options.authorization ?? `Bearer ${token}`,
        "Content-Type": "application/json",
        "Idempotency-Key": options.idempotencyKey ??
          "exhibition.published:exhibition-one:1",
        "X-Outbox-Event-Id": eventId,
        "X-Outbox-Event-Type": eventType,
      },
      body: method === "POST" ? body : undefined,
    },
  );
}

Deno.test("published exhibition triggers the exact Vercel deploy hook", async () => {
  const { calls, handler } = buildHandler();
  const response = await handler(request());

  assert(response.status === 204, "delivery was not acknowledged");
  assert((await response.text()) === "", "delivery leaked a response body");
  assert(calls.length === 1, "deploy hook was not called exactly once");
  assert(calls[0]?.url === hook, "wrong deploy hook called");
  assert(calls[0]?.init?.method === "POST", "deploy hook was not POSTed");
});

Deno.test("archive and restore also rebuild while internal events are acknowledged", async () => {
  for (const eventType of ["exhibition.archived", "exhibition.restored"]) {
    const { calls, handler } = buildHandler();
    const response = await handler(
      request({ eventType, bodyEventType: eventType }),
    );
    assert(response.status === 204, `${eventType} was not acknowledged`);
    assert(calls.length === 1, `${eventType} did not trigger a rebuild`);
  }

  const { calls, handler } = buildHandler();
  const response = await handler(request({
    eventType: "owner_exhibition.submitted",
    bodyEventType: "owner_exhibition.submitted",
  }));
  assert(response.status === 204, "known internal event was not acknowledged");
  assert(calls.length === 0, "internal event triggered a public rebuild");
});

Deno.test("rejects unauthenticated requests before any outbound call", async () => {
  const { calls, handler } = buildHandler();
  const response = await handler(request({ authorization: "Bearer wrong" }));
  assert(response.status === 401, "bad token was accepted");
  assert(calls.length === 0, "bad token reached the deploy hook");
});

Deno.test("rejects mismatched event headers and unknown event types", async () => {
  const mismatch = buildHandler();
  const mismatchResponse = await mismatch.handler(request({
    bodyEventType: "exhibition.archived",
  }));
  assert(mismatchResponse.status === 400, "mismatched event type was accepted");
  assert(mismatch.calls.length === 0, "mismatched event reached deploy hook");

  const mismatchedId = buildHandler();
  const mismatchedIdResponse = await mismatchedId.handler(request({
    bodyEventId: "00000000-0000-4000-8000-000000000002",
  }));
  assert(
    mismatchedIdResponse.status === 400,
    "mismatched event ID was accepted",
  );
  assert(mismatchedId.calls.length === 0, "mismatched ID reached deploy hook");

  const mismatchedKey = buildHandler();
  const mismatchedKeyResponse = await mismatchedKey.handler(request({
    idempotencyKey: "wrong-key",
  }));
  assert(
    mismatchedKeyResponse.status === 400,
    "mismatched idempotency key was accepted",
  );
  assert(
    mismatchedKey.calls.length === 0,
    "mismatched key reached deploy hook",
  );

  const unknown = buildHandler();
  const unknownResponse = await unknown.handler(request({
    eventType: "future.unknown",
    bodyEventType: "future.unknown",
  }));
  assert(
    unknownResponse.status === 422,
    "unknown event was silently discarded",
  );
  assert(unknown.calls.length === 0, "unknown event reached deploy hook");
});

Deno.test("rejects malformed, oversized, and non-POST requests", async () => {
  const malformed = buildHandler();
  assert(
    (await malformed.handler(request({ body: "{" }))).status === 400,
    "malformed JSON was accepted",
  );

  const oversized = buildHandler();
  assert(
    (await oversized.handler(request({ body: "x".repeat(70_000) }))).status ===
      413,
    "oversized body was accepted",
  );

  const get = buildHandler();
  assert(
    (await get.handler(request({ method: "GET" }))).status === 405,
    "GET was accepted",
  );
});

Deno.test("invalid configuration fails closed", async () => {
  const shortToken = buildHandler({ configuredToken: "short" });
  assert(
    (await shortToken.handler(request())).status === 500,
    "short configured token was accepted",
  );
  assert(shortToken.calls.length === 0, "invalid token config reached hook");

  const foreignHook = buildHandler({
    configuredHook: "https://attacker.invalid/v1/integrations/deploy/x/y",
  });
  assert(
    (await foreignHook.handler(request())).status === 500,
    "foreign deploy hook was accepted",
  );
  assert(foreignHook.calls.length === 0, "foreign hook was called");
});

Deno.test("deploy hook failures remain retryable", async () => {
  const { calls, handler } = buildHandler({ fetchStatus: 503 });
  const response = await handler(request());
  assert(response.status === 502, "hook failure was acknowledged as delivered");
  assert(calls.length === 1, "hook was not attempted");
});
