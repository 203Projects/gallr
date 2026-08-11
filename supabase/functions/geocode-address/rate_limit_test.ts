import {
  consumeGeocodeRateLimit,
  RateLimitServiceError,
} from "./rate_limit.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function signal(): AbortSignal {
  return AbortSignal.timeout(5_000);
}

function bodyReadFailure(name: "AbortError" | "TimeoutError"): Response {
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        controller.error(new DOMException("secret transport detail", name));
      },
    }),
    { status: 200 },
  );
}

Deno.test("rate limiter forwards the caller JWT to the atomic database RPC", async () => {
  let captured: Request | undefined;
  const result = await consumeGeocodeRateLimit({
    authorization: "Bearer header.payload.signature",
    supabaseUrl: "https://project.supabase.co",
    publishableKey: "sb_publishable_test",
    fetcher: (request) => {
      captured = request as Request;
      return Promise.resolve(Response.json({
        allowed: true,
        retry_after_seconds: 0,
        limited_by: null,
      }));
    },
    signal: signal(),
  });

  assert(result.allowed, "expected the request to be allowed");
  assert(captured !== undefined, "expected a rate-limit request");
  assert(captured.method === "POST", "rate-limit RPC must use POST");
  assert(
    captured.url ===
      "https://project.supabase.co/rest/v1/rpc/geocode_consume_rate_limit",
    "unexpected rate-limit RPC URL",
  );
  assert(
    captured.headers.get("authorization") ===
      "Bearer header.payload.signature",
    "caller JWT was not forwarded",
  );
  assert(
    captured.headers.get("apikey") === "sb_publishable_test",
    "publishable key was not sent as apikey",
  );
});

Deno.test("rate limiter accepts only bounded database retry metadata", async () => {
  const result = await consumeGeocodeRateLimit({
    authorization: "Bearer header.payload.signature",
    supabaseUrl: "https://project.supabase.co",
    publishableKey: "sb_publishable_test",
    fetcher: () =>
      Promise.resolve(Response.json({
        allowed: false,
        retry_after_seconds: 17,
        limited_by: "project",
      })),
    signal: signal(),
  });

  assert(!result.allowed, "expected the request to be limited");
  if (result.allowed) throw new Error("expected a limited result");
  assert(result.retryAfterSeconds === 17, "unexpected retry interval");
  assert(result.limitedBy === "project", "unexpected limiting scope");

  let thrown: unknown;
  try {
    await consumeGeocodeRateLimit({
      authorization: "Bearer header.payload.signature",
      supabaseUrl: "https://project.supabase.co",
      publishableKey: "sb_publishable_test",
      fetcher: () =>
        Promise.resolve(Response.json({
          allowed: false,
          retry_after_seconds: 61,
          limited_by: "staff",
        })),
      signal: signal(),
    });
  } catch (error) {
    thrown = error;
  }
  assert(
    thrown instanceof RateLimitServiceError,
    "expected fail-closed result",
  );
  assert(
    thrown.code === "rate_limit_service_unavailable",
    "unexpected malformed-response code",
  );
});

Deno.test("rate limiter accepts the bounded owner caller scope", async () => {
  const result = await consumeGeocodeRateLimit({
    authorization: "Bearer header.payload.signature",
    supabaseUrl: "https://project.supabase.co",
    publishableKey: "sb_publishable_test",
    fetcher: () =>
      Promise.resolve(Response.json({
        allowed: false,
        retry_after_seconds: 9,
        limited_by: "owner",
      })),
    signal: signal(),
  });

  assert(!result.allowed, "expected owner quota rejection");
  if (result.allowed) throw new Error("expected a limited result");
  assert(result.limitedBy === "owner", "owner scope was not preserved");
});

Deno.test("rate limiter fails closed without exposing an upstream RPC body", async () => {
  let thrown: unknown;
  try {
    await consumeGeocodeRateLimit({
      authorization: "Bearer header.payload.signature",
      supabaseUrl: "https://project.supabase.co",
      publishableKey: "sb_publishable_test",
      fetcher: () =>
        Promise.resolve(new Response("database-secret", { status: 503 })),
      signal: signal(),
    });
  } catch (error) {
    thrown = error;
  }
  assert(thrown instanceof RateLimitServiceError, "expected RPC failure");
  assert(
    thrown.message === "Geocoding rate limiting is temporarily unavailable.",
    "raw RPC body leaked into the error",
  );
});

Deno.test("rate limiter sanitizes RPC body-read aborts", async () => {
  for (const name of ["AbortError", "TimeoutError"] as const) {
    let thrown: unknown;
    try {
      await consumeGeocodeRateLimit({
        authorization: "Bearer header.payload.signature",
        supabaseUrl: "https://project.supabase.co",
        publishableKey: "sb_publishable_test",
        fetcher: () => Promise.resolve(bodyReadFailure(name)),
        signal: signal(),
      });
    } catch (error) {
      thrown = error;
    }
    assert(thrown instanceof RateLimitServiceError, "expected RPC failure");
    assert(
      thrown.code === "rate_limit_service_unavailable",
      `unexpected body-read failure code for ${name}`,
    );
    assert(
      !thrown.message.includes("secret transport detail"),
      `body-read failure leaked details for ${name}`,
    );
  }
});
