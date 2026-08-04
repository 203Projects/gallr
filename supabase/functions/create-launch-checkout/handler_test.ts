import {
  type CheckoutContext,
  checkoutDisposition,
  type LaunchCheckoutBackend,
  launchCheckoutReturnUrls,
} from "./backend.ts";
import { createLaunchCheckoutHandler } from "./handler.ts";

function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}

class Backend implements LaunchCheckoutBackend {
  prepared: string[] = [];
  context: CheckoutContext = {
    launchKitId: "kit-one",
    exhibitionId: "exhibition-one",
    galleryId: "gallery-one",
    status: "pending",
    ownerEmail: "owner@example.test",
    checkoutAttempt: 0,
  };
  prepare(
    _authorization: string,
    exhibitionId: string,
  ): Promise<CheckoutContext> {
    this.prepared.push(exhibitionId);
    return Promise.resolve(this.context);
  }
  createCheckout(): Promise<{ sessionId: string; url: string }> {
    return Promise.resolve({
      sessionId: "cs_test",
      url: "https://checkout.stripe.test/session",
    });
  }
}

function request(
  body: unknown,
  origin = "https://gallery.gallrmap.com",
  authorization = "Bearer token",
) {
  return new Request(
    "https://project.supabase.co/functions/v1/create-launch-checkout",
    {
      method: "POST",
      headers: {
        Origin: origin,
        Authorization: authorization,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    },
  );
}

function handler(backend: Backend) {
  return createLaunchCheckoutHandler({
    env: () => undefined,
    requestId: () => "request-one",
    createBackend: () => backend,
  });
}

Deno.test("returns hosted Checkout URL for an authorized pending kit", async () => {
  const backend = new Backend();
  const response = await handler(backend)(
    request({ exhibition_id: "exhibition-one" }),
  );
  const body = await response.json() as Record<string, unknown>;
  assert(response.status === 200, "checkout failed");
  assert(body.url === "https://checkout.stripe.test/session", "URL missing");
  assert(backend.prepared.join() === "exhibition-one", "wrong exhibition");
});

Deno.test("forwards hosted named key maps to the checkout backend", async () => {
  const backend = new Backend();
  const environments: Record<string, string>[] = [];
  const checkout = createLaunchCheckoutHandler({
    env: (name) =>
      name === "SUPABASE_PUBLISHABLE_KEYS"
        ? '{"default":"publishable"}'
        : name === "SUPABASE_SECRET_KEYS"
        ? '{"default":"secret"}'
        : undefined,
    requestId: () => "request-one",
    createBackend: (value) => {
      environments.push(value);
      return backend;
    },
  });

  const response = await checkout(request({ exhibition_id: "exhibition-one" }));
  assert(response.status === 200, "checkout failed");
  assert(
    environments[0]?.SUPABASE_PUBLISHABLE_KEYS ===
      '{"default":"publishable"}',
    "publishable map not forwarded",
  );
  assert(
    environments[0]?.SUPABASE_SECRET_KEYS === '{"default":"secret"}',
    "secret map not forwarded",
  );
});

Deno.test("returns active state without creating another payment", async () => {
  const backend = new Backend();
  backend.context = { ...backend.context, status: "active" };
  const response = await handler(backend)(
    request({ exhibition_id: "exhibition-one" }),
  );
  const body = await response.json() as Record<string, unknown>;
  assert(body.active === true, "active state missing");
});

Deno.test("rejects missing auth, foreign origins, and extra trusted fields", async () => {
  const backend = new Backend();
  assert(
    (await handler(backend)(request({ exhibition_id: "x" }, undefined, "")))
      .status === 401,
    "missing auth accepted",
  );
  assert(
    (await handler(backend)(
      request({ exhibition_id: "x" }, "https://attacker.invalid"),
    )).status === 403,
    "origin accepted",
  );
  assert(
    (await handler(backend)(
      request({ exhibition_id: "x", price_id: "attacker" }),
    )).status === 400,
    "price accepted",
  );
  assert(backend.prepared.length === 0, "invalid request touched backend");
});

Deno.test("reuses one live Checkout session and replaces only an expired one", () => {
  assert(
    checkoutDisposition({
      status: "open",
      url: "https://checkout.stripe.test/live",
    }) === "reuse",
    "live session was replaced",
  );
  assert(
    checkoutDisposition({ status: "expired", url: null }) === "replace",
    "expired session was not replaceable",
  );
  assert(
    checkoutDisposition({ status: "complete", url: null }) === "wait",
    "completed session allowed duplicate checkout",
  );
});

Deno.test("returns Checkout to the workspace without exposing the Stripe session ID", () => {
  const urls = launchCheckoutReturnUrls("https://gallery.gallrmap.com///");
  assert(
    urls.successUrl === "https://gallery.gallrmap.com/?launch=success",
    "success URL was not normalized",
  );
  assert(
    urls.cancelUrl === "https://gallery.gallrmap.com/?launch=cancelled",
    "cancel URL was not normalized",
  );
  assert(!urls.successUrl.includes("session_id"), "session ID was exposed");
  assert(
    !urls.successUrl.includes("CHECKOUT_SESSION_ID"),
    "Stripe session template was exposed",
  );
});
