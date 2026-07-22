import { authorizeActiveStaff, StaffAuthorizationError } from "./auth.ts";

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

Deno.test("staff authorization forwards the caller JWT to admin_current_staff", async () => {
  let captured: Request | undefined;
  const identity = await authorizeActiveStaff({
    authorization: "Bearer header.payload.signature",
    supabaseUrl: "https://project.supabase.co",
    publishableKey: "sb_publishable_test",
    fetcher: (request) => {
      captured = request as Request;
      return Promise.resolve(Response.json({
        user_id: "00000000-0000-4000-8000-000000000001",
        role: "publisher",
        active: true,
      }));
    },
    signal: signal(),
  });

  assert(identity.role === "publisher", "expected publisher identity");
  assert(captured !== undefined, "expected an authorization request");
  assert(captured.method === "POST", "authorization RPC must use POST");
  assert(
    captured.url ===
      "https://project.supabase.co/rest/v1/rpc/admin_current_staff",
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

Deno.test("staff authorization rejects inactive or malformed membership", async () => {
  let thrown: unknown;
  try {
    await authorizeActiveStaff({
      authorization: "Bearer header.payload.signature",
      supabaseUrl: "https://project.supabase.co",
      publishableKey: "sb_publishable_test",
      fetcher: () =>
        Promise.resolve(Response.json({
          user_id: "00000000-0000-4000-8000-000000000001",
          role: "publisher",
          active: false,
        })),
      signal: signal(),
    });
  } catch (error) {
    thrown = error;
  }
  assert(thrown instanceof StaffAuthorizationError, "expected denial");
  assert(
    thrown.code === "active_staff_membership_required",
    "unexpected denial code",
  );
});

Deno.test("staff authorization rejects malformed successful RPC payloads", async () => {
  let thrown: unknown;
  try {
    await authorizeActiveStaff({
      authorization: "Bearer header.payload.signature",
      supabaseUrl: "https://project.supabase.co",
      publishableKey: "sb_publishable_test",
      fetcher: () =>
        Promise.resolve(Response.json({
          user_id: "not-a-uuid",
          role: "publisher",
          active: true,
        })),
      signal: signal(),
    });
  } catch (error) {
    thrown = error;
  }
  assert(thrown instanceof StaffAuthorizationError, "expected failure");
  assert(
    thrown.code === "authorization_service_unavailable",
    "unexpected malformed response code",
  );
});

Deno.test("staff authorization fails closed when the RPC is unavailable", async () => {
  let thrown: unknown;
  try {
    await authorizeActiveStaff({
      authorization: "Bearer header.payload.signature",
      supabaseUrl: "https://project.supabase.co",
      publishableKey: "sb_publishable_test",
      fetcher: () => Promise.resolve(new Response(null, { status: 503 })),
      signal: signal(),
    });
  } catch (error) {
    thrown = error;
  }
  assert(thrown instanceof StaffAuthorizationError, "expected failure");
  assert(
    thrown.code === "authorization_service_unavailable",
    "unexpected failure code",
  );
});

Deno.test("staff authorization sanitizes RPC body-read aborts", async () => {
  for (const name of ["AbortError", "TimeoutError"] as const) {
    let thrown: unknown;
    try {
      await authorizeActiveStaff({
        authorization: "Bearer header.payload.signature",
        supabaseUrl: "https://project.supabase.co",
        publishableKey: "sb_publishable_test",
        fetcher: () => Promise.resolve(bodyReadFailure(name)),
        signal: signal(),
      });
    } catch (error) {
      thrown = error;
    }
    assert(thrown instanceof StaffAuthorizationError, "expected failure");
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
