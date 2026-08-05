import {
  StaffAuthorizationError,
  type StaffAuthorizationOptions,
} from "./auth.ts";
import { createGeocodeHandler, PROVIDER_TIMEOUT_MS } from "./handler.ts";
import { RateLimitServiceError } from "./rate_limit.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const ENVIRONMENT: Record<string, string> = {
  SUPABASE_URL: "https://project.supabase.co",
  SUPABASE_PUBLISHABLE_KEY: "sb_publishable_test",
  NAVER_MAPS_API_KEY_ID: "naver-id",
  NAVER_MAPS_API_KEY: "naver-secret",
};

function request(body: unknown): Request {
  return new Request(
    "https://project.supabase.co/functions/v1/geocode-address",
    {
      method: "POST",
      headers: {
        Authorization: "Bearer header.payload.signature",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    },
  );
}

function providerResponse(): Response {
  return Response.json({
    status: "OK",
    meta: { totalCount: 1, page: 1, count: 1 },
    addresses: [{
      roadAddress: "서울 용산구 한남대로 28",
      jibunAddress: "서울 용산구 한남동 1-1",
      englishAddress: "28 Hannam-daero, Yongsan-gu, Seoul",
      addressElements: [
        {
          types: ["SIDO"],
          longName: "서울특별시",
          shortName: "서울특별시",
          code: "",
        },
        {
          types: ["SIGUGUN"],
          longName: "용산구",
          shortName: "용산구",
          code: "",
        },
      ],
      x: "127.0005",
      y: "37.5344",
      distance: 0,
    }],
  });
}

function providerBodyReadFailure(
  name: "AbortError" | "TimeoutError",
): Response {
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        controller.error(new DOMException("secret transport detail", name));
      },
    }),
    { status: 200 },
  );
}

const allowRateLimit = () =>
  Promise.resolve({
    allowed: true as const,
    retryAfterSeconds: 0 as const,
    limitedBy: null,
  });

Deno.test("handler supports browser preflight and rejects non-POST methods", async () => {
  const handler = createGeocodeHandler({ requestId: () => "request-1" });
  const preflight = await handler(
    new Request("https://example.test/geocode-address", { method: "OPTIONS" }),
  );
  assert(preflight.status === 204, "expected preflight success");
  assert(
    preflight.headers.get("access-control-allow-origin") === "*",
    "missing CORS origin",
  );

  const get = await handler(
    new Request("https://example.test/geocode-address", { method: "GET" }),
  );
  assert(get.status === 405, "expected GET rejection");
  assert(get.headers.get("allow") === "POST, OPTIONS", "missing Allow header");
  assert(
    get.headers.get("access-control-allow-origin") === "*",
    "missing CORS header on method error",
  );
});

Deno.test("handler authorizes POST requests before inspecting their body", async () => {
  let authorizationCalls = 0;
  const handler = createGeocodeHandler({
    env: (name) => ENVIRONMENT[name],
    requestId: () => "request-auth-first",
    log: () => undefined,
    authorizeStaff: () => {
      authorizationCalls += 1;
      return Promise.resolve({
        userId: "00000000-0000-4000-8000-000000000001",
        role: "publisher",
      });
    },
  });
  const response = await handler(
    new Request(
      "https://project.supabase.co/functions/v1/geocode-address",
      {
        method: "POST",
        headers: { Authorization: "Bearer header.payload.signature" },
        body: "not-json",
      },
    ),
  );

  assert(
    authorizationCalls === 1,
    "POST body validation bypassed authorization",
  );
  assert(response.status === 415, "expected content-type rejection");
});

Deno.test("handler authorizes staff then returns bounded NAVER candidates", async () => {
  let authorizationOptions: StaffAuthorizationOptions | undefined;
  let providerRequest: Request | undefined;
  let providerSignal: AbortSignal | null | undefined;
  const handler = createGeocodeHandler({
    env: (name) => ENVIRONMENT[name],
    requestId: () => "request-2",
    log: () => undefined,
    authorizeStaff: (options) => {
      authorizationOptions = options;
      return Promise.resolve({
        userId: "00000000-0000-4000-8000-000000000001",
        role: "publisher",
      });
    },
    consumeRateLimit: allowRateLimit,
    fetcher: (input, init) => {
      providerRequest = input as Request;
      providerSignal = init?.signal;
      return Promise.resolve(providerResponse());
    },
  });

  const response = await handler(
    request({ address: " 서울 용산구 한남대로 28 " }),
  );
  const payload = await response.json() as Record<string, unknown>;
  const candidates = payload.candidates as Array<Record<string, unknown>>;

  assert(response.status === 200, "expected geocoding success");
  assert(authorizationOptions !== undefined, "staff authorization was skipped");
  assert(
    authorizationOptions.authorization === "Bearer header.payload.signature",
    "caller authorization was not used",
  );
  assert(providerRequest !== undefined, "provider request was not made");
  assert(
    new URL(providerRequest.url).searchParams.get("query") ===
      "서울 용산구 한남대로 28",
    "address was not normalized",
  );
  assert(providerSignal instanceof AbortSignal, "provider timeout is missing");
  assert(
    PROVIDER_TIMEOUT_MS === 5_000,
    "provider timeout must be five seconds",
  );
  assert(candidates.length === 1, "expected one candidate");
  assert(candidates[0].longitude === "127.0005", "longitude was not mapped");
  assert(candidates[0].latitude === "37.5344", "latitude was not mapped");
  assert(candidates[0].city_ko === "서울", "city was not normalized");
  assert(candidates[0].region_ko === "용산구", "region was not normalized");
  assert(
    response.headers.get("cache-control") === "no-store",
    "response cached",
  );
  assert(
    response.headers.get("access-control-allow-origin") === "*",
    "missing CORS header on success",
  );
});

Deno.test("handler rejects malformed provider payload without returning it", async () => {
  const handler = createGeocodeHandler({
    env: (name) => ENVIRONMENT[name],
    requestId: () => "request-3",
    log: () => undefined,
    authorizeStaff: () =>
      Promise.resolve({
        userId: "00000000-0000-4000-8000-000000000001",
        role: "publisher",
      }),
    consumeRateLimit: allowRateLimit,
    fetcher: () =>
      Promise.resolve(
        Response.json({ status: "OK", raw_secret: "do-not-return" }),
      ),
  });

  const response = await handler(
    request({ address: "서울 용산구 한남대로 28" }),
  );
  const text = await response.text();
  assert(response.status === 502, "expected provider response rejection");
  assert(!text.includes("do-not-return"), "raw provider payload was returned");
  assert(
    text.includes("geocoding_provider_response_invalid"),
    "missing structured error code",
  );
});

Deno.test("handler rejects oversized provider responses before parsing", async () => {
  const handler = createGeocodeHandler({
    env: (name) => ENVIRONMENT[name],
    requestId: () => "request-4",
    log: () => undefined,
    authorizeStaff: () =>
      Promise.resolve({
        userId: "00000000-0000-4000-8000-000000000001",
        role: "publisher",
      }),
    consumeRateLimit: allowRateLimit,
    fetcher: () =>
      Promise.resolve(
        new Response("{}", { headers: { "Content-Length": "70000" } }),
      ),
  });

  const response = await handler(
    request({ address: "서울 용산구 한남대로 28" }),
  );
  assert(response.status === 502, "expected oversized response rejection");
  const payload = await response.json() as { error: { code: string } };
  assert(
    payload.error.code === "geocoding_provider_response_too_large",
    "unexpected oversized response code",
  );
});

Deno.test("handler rejects a distributed quota result before calling NAVER", async () => {
  let providerCalls = 0;
  const handler = createGeocodeHandler({
    env: (name) => ENVIRONMENT[name],
    requestId: () => "request-rate-limited",
    log: () => undefined,
    authorizeStaff: () =>
      Promise.resolve({
        userId: "00000000-0000-4000-8000-000000000001",
        role: "publisher",
      }),
    consumeRateLimit: () =>
      Promise.resolve({
        allowed: false,
        retryAfterSeconds: 17,
        limitedBy: "staff",
      }),
    fetcher: () => {
      providerCalls += 1;
      return Promise.resolve(providerResponse());
    },
  });

  const response = await handler(
    request({ address: "서울 용산구 한남대로 28" }),
  );
  const payload = await response.json() as { error: { code: string } };
  assert(response.status === 429, "expected local rate-limit response");
  assert(
    response.headers.get("retry-after") === "17",
    "missing bounded Retry-After",
  );
  assert(
    payload.error.code === "geocoding_rate_limited",
    "unexpected local rate-limit code",
  );
  assert(providerCalls === 0, "NAVER was called after quota rejection");
});

Deno.test("handler maps sanitized NAVER auth, quota, and outage statuses", async () => {
  const cases = [
    {
      providerStatus: 401,
      expectedStatus: 502,
      expectedCode: "geocoding_provider_configuration_error",
      retryAfter: null,
    },
    {
      providerStatus: 403,
      expectedStatus: 502,
      expectedCode: "geocoding_provider_configuration_error",
      retryAfter: null,
    },
    {
      providerStatus: 429,
      expectedStatus: 429,
      expectedCode: "geocoding_provider_rate_limited",
      retryAfter: "23",
    },
    {
      providerStatus: 503,
      expectedStatus: 503,
      expectedCode: "geocoding_provider_unavailable",
      retryAfter: null,
    },
    {
      providerStatus: 504,
      expectedStatus: 504,
      expectedCode: "geocoding_provider_timeout",
      retryAfter: null,
    },
  ] as const;

  for (const testCase of cases) {
    const logs: unknown[] = [];
    const headers = new Headers({ "Content-Type": "text/plain" });
    if (testCase.retryAfter !== null) {
      headers.set("Retry-After", testCase.retryAfter);
    }
    const handler = createGeocodeHandler({
      env: (name) => ENVIRONMENT[name],
      requestId: () => `provider-${testCase.providerStatus}`,
      log: (_level, event) => logs.push(event),
      authorizeStaff: () =>
        Promise.resolve({
          userId: "00000000-0000-4000-8000-000000000001",
          role: "publisher",
        }),
      consumeRateLimit: allowRateLimit,
      fetcher: () =>
        Promise.resolve(
          new Response("raw-provider-secret", {
            status: testCase.providerStatus,
            headers,
          }),
        ),
    });

    const response = await handler(
      request({ address: "서울 용산구 한남대로 28" }),
    );
    const text = await response.text();
    assert(
      response.status === testCase.expectedStatus,
      `unexpected mapping for NAVER ${testCase.providerStatus}`,
    );
    assert(
      text.includes(testCase.expectedCode),
      `missing code for NAVER ${testCase.providerStatus}`,
    );
    assert(!text.includes("raw-provider-secret"), "provider body leaked");
    assert(
      !JSON.stringify(logs).includes("raw-provider-secret"),
      "provider body leaked to logs",
    );
    assert(
      response.headers.get("access-control-allow-origin") === "*",
      "CORS headers missing from provider error",
    );
    if (testCase.retryAfter !== null) {
      assert(
        response.headers.get("retry-after") === testCase.retryAfter,
        "valid provider Retry-After was not preserved",
      );
    }
  }
});

Deno.test("handler replaces unsafe NAVER Retry-After values with a bounded default", async () => {
  const handler = createGeocodeHandler({
    env: (name) => ENVIRONMENT[name],
    requestId: () => "provider-retry-invalid",
    log: () => undefined,
    authorizeStaff: () =>
      Promise.resolve({
        userId: "00000000-0000-4000-8000-000000000001",
        role: "publisher",
      }),
    consumeRateLimit: allowRateLimit,
    fetcher: () =>
      Promise.resolve(
        new Response("hidden", {
          status: 429,
          headers: { "Retry-After": "999999" },
        }),
      ),
  });

  const response = await handler(
    request({ address: "서울 용산구 한남대로 28" }),
  );
  assert(response.status === 429, "expected provider rate-limit response");
  assert(
    response.headers.get("retry-after") === "60",
    "unsafe provider Retry-After was forwarded",
  );
});

Deno.test("handler maps provider fetch timeouts without leaking exception details", async () => {
  const handler = createGeocodeHandler({
    env: (name) => ENVIRONMENT[name],
    requestId: () => "provider-fetch-timeout",
    log: () => undefined,
    authorizeStaff: () =>
      Promise.resolve({
        userId: "00000000-0000-4000-8000-000000000001",
        role: "publisher",
      }),
    consumeRateLimit: allowRateLimit,
    fetcher: () => {
      throw new DOMException("secret transport detail", "TimeoutError");
    },
  });

  const response = await handler(
    request({ address: "서울 용산구 한남대로 28" }),
  );
  const text = await response.text();
  assert(response.status === 504, "expected provider timeout");
  assert(text.includes("geocoding_provider_timeout"), "missing timeout code");
  assert(!text.includes("secret transport detail"), "exception detail leaked");
});

Deno.test("handler maps provider body-read aborts to a sanitized timeout", async () => {
  for (const name of ["AbortError", "TimeoutError"] as const) {
    const handler = createGeocodeHandler({
      env: (key) => ENVIRONMENT[key],
      requestId: () => `provider-body-${name}`,
      log: () => undefined,
      authorizeStaff: () =>
        Promise.resolve({
          userId: "00000000-0000-4000-8000-000000000001",
          role: "publisher",
        }),
      consumeRateLimit: allowRateLimit,
      fetcher: () => Promise.resolve(providerBodyReadFailure(name)),
    });

    const response = await handler(
      request({ address: "서울 용산구 한남대로 28" }),
    );
    const text = await response.text();
    assert(response.status === 504, `expected provider timeout for ${name}`);
    assert(
      text.includes("geocoding_provider_timeout"),
      `missing timeout code for ${name}`,
    );
    assert(
      !text.includes("secret transport detail"),
      `body-read failure leaked details for ${name}`,
    );
  }
});

Deno.test("handler fails closed when the database limiter is unavailable", async () => {
  const handler = createGeocodeHandler({
    env: (name) => ENVIRONMENT[name],
    requestId: () => "limiter-unavailable",
    log: () => undefined,
    authorizeStaff: () =>
      Promise.resolve({
        userId: "00000000-0000-4000-8000-000000000001",
        role: "publisher",
      }),
    consumeRateLimit: () => {
      throw new RateLimitServiceError(
        "rate_limit_service_unavailable",
        "Geocoding rate limiting is temporarily unavailable.",
      );
    },
    fetcher: () => Promise.resolve(providerResponse()),
  });

  const response = await handler(
    request({ address: "서울 용산구 한남대로 28" }),
  );
  const text = await response.text();
  assert(response.status === 503, "limiter failures must fail closed");
  assert(
    text.includes("rate_limit_service_unavailable"),
    "missing limiter code",
  );
});

Deno.test("handler maps staff authentication failures with CORS headers", async () => {
  const cases = [
    ["authentication_required", 401],
    ["active_staff_membership_required", 403],
    ["authorization_service_unavailable", 503],
  ] as const;

  for (const [code, status] of cases) {
    const handler = createGeocodeHandler({
      env: (name) => ENVIRONMENT[name],
      requestId: () => `auth-${status}`,
      log: () => undefined,
      authorizeStaff: () => {
        throw new StaffAuthorizationError(
          code,
          "Sanitized authorization error.",
        );
      },
    });
    const response = await handler(
      request({ address: "서울 용산구 한남대로 28" }),
    );
    assert(response.status === status, `unexpected auth mapping for ${code}`);
    assert(
      response.headers.get("access-control-allow-origin") === "*",
      `CORS missing for ${code}`,
    );
  }
});

Deno.test("handler rejects oversized, malformed, and invalid UTF-8 bodies", async () => {
  const handler = createGeocodeHandler({
    env: (name) => ENVIRONMENT[name],
    requestId: () => "invalid-body",
    log: () => undefined,
    authorizeStaff: () =>
      Promise.resolve({
        userId: "00000000-0000-4000-8000-000000000001",
        role: "publisher",
      }),
  });

  const oversized = await handler(request({ address: "가".repeat(2_100) }));
  assert(oversized.status === 413, "expected oversized request rejection");

  const malformed = await handler(
    new Request("https://example.test/geocode-address", {
      method: "POST",
      headers: {
        Authorization: "Bearer header.payload.signature",
        "Content-Type": "application/json",
      },
      body: "{",
    }),
  );
  assert(malformed.status === 400, "expected malformed JSON rejection");

  const invalidUtf8 = await handler(
    new Request("https://example.test/geocode-address", {
      method: "POST",
      headers: {
        Authorization: "Bearer header.payload.signature",
        "Content-Type": "application/json",
      },
      body: new Uint8Array([0xff]),
    }),
  );
  assert(invalidUtf8.status === 400, "expected invalid UTF-8 rejection");
});
