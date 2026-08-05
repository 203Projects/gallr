import { authorizeGeocodeCaller, GeocodeAuthorizationError } from "./auth.ts";

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

Deno.test("geocode authorization forwards the caller JWT to the generic RPC", async () => {
  let captured: Request | undefined;
  const identity = await authorizeGeocodeCaller({
    authorization: "Bearer header.payload.signature",
    supabaseUrl: "https://project.supabase.co",
    publishableKey: "sb_publishable_test",
    fetcher: (request) => {
      captured = request as Request;
      return Promise.resolve(Response.json({
        caller_type: "staff",
        user_id: "00000000-0000-4000-8000-000000000001",
        role: "publisher",
      }));
    },
    signal: signal(),
  });

  assert(identity.callerType === "staff", "expected staff identity");
  if (identity.callerType !== "staff") throw new Error("expected staff");
  assert(identity.role === "publisher", "expected publisher identity");
  assert(captured !== undefined, "expected an authorization request");
  assert(captured.method === "POST", "authorization RPC must use POST");
  assert(
    captured.url ===
      "https://project.supabase.co/rest/v1/rpc/geocode_current_caller",
    "unexpected authorization RPC URL",
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

Deno.test("geocode authorization accepts an eligible gallery owner", async () => {
  const identity = await authorizeGeocodeCaller({
    authorization: "Bearer header.payload.signature",
    supabaseUrl: "https://project.supabase.co",
    publishableKey: "sb_publishable_test",
    fetcher: () =>
      Promise.resolve(Response.json({
        caller_type: "owner",
        user_id: "00000000-0000-4000-8000-000000000002",
        gallery_id: "10000000-0000-4000-8000-000000000002",
      })),
    signal: signal(),
  });

  assert(identity.callerType === "owner", "expected owner identity");
  if (identity.callerType !== "owner") throw new Error("expected owner");
  assert(
    identity.galleryId === "10000000-0000-4000-8000-000000000002",
    "owner gallery was not preserved",
  );
});

Deno.test("geocode authorization maps denied pending claimants", async () => {
  let thrown: unknown;
  try {
    await authorizeGeocodeCaller({
      authorization: "Bearer header.payload.signature",
      supabaseUrl: "https://project.supabase.co",
      publishableKey: "sb_publishable_test",
      fetcher: () => Promise.resolve(new Response(null, { status: 403 })),
      signal: signal(),
    });
  } catch (error) {
    thrown = error;
  }
  assert(thrown instanceof GeocodeAuthorizationError, "expected denial");
  assert(thrown.code === "geocode_access_required", "unexpected denial code");
});

Deno.test("geocode authorization rejects malformed successful RPC payloads", async () => {
  for (
    const payload of [
      { caller_type: "staff", user_id: "not-a-uuid", role: "publisher" },
      {
        caller_type: "owner",
        user_id: "00000000-0000-4000-8000-000000000002",
        gallery_id: "not-a-uuid",
      },
      {
        caller_type: "owner",
        user_id: "00000000-0000-4000-8000-000000000002",
        gallery_id: "10000000-0000-4000-8000-000000000002",
        role: "publisher",
      },
    ]
  ) {
    let thrown: unknown;
    try {
      await authorizeGeocodeCaller({
        authorization: "Bearer header.payload.signature",
        supabaseUrl: "https://project.supabase.co",
        publishableKey: "sb_publishable_test",
        fetcher: () => Promise.resolve(Response.json(payload)),
        signal: signal(),
      });
    } catch (error) {
      thrown = error;
    }
    assert(thrown instanceof GeocodeAuthorizationError, "expected failure");
    assert(
      thrown.code === "authorization_service_unavailable",
      "unexpected malformed response code",
    );
  }
});

Deno.test("geocode authorization fails closed when the RPC is unavailable", async () => {
  let thrown: unknown;
  try {
    await authorizeGeocodeCaller({
      authorization: "Bearer header.payload.signature",
      supabaseUrl: "https://project.supabase.co",
      publishableKey: "sb_publishable_test",
      fetcher: () => Promise.resolve(new Response(null, { status: 503 })),
      signal: signal(),
    });
  } catch (error) {
    thrown = error;
  }
  assert(thrown instanceof GeocodeAuthorizationError, "expected failure");
  assert(
    thrown.code === "authorization_service_unavailable",
    "unexpected failure code",
  );
});

Deno.test("geocode authorization sanitizes RPC body-read aborts", async () => {
  for (const name of ["AbortError", "TimeoutError"] as const) {
    let thrown: unknown;
    try {
      await authorizeGeocodeCaller({
        authorization: "Bearer header.payload.signature",
        supabaseUrl: "https://project.supabase.co",
        publishableKey: "sb_publishable_test",
        fetcher: () => Promise.resolve(bodyReadFailure(name)),
        signal: signal(),
      });
    } catch (error) {
      thrown = error;
    }
    assert(thrown instanceof GeocodeAuthorizationError, "expected failure");
    assert(
      thrown.code === "authorization_service_unavailable",
      `unexpected body-read failure code for ${name}`,
    );
    assert(
      !thrown.message.includes("secret transport detail"),
      `body-read failure leaked details for ${name}`,
    );
  }
});
