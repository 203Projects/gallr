import type { LaunchWebhookBackend, PaidCheckout } from "./backend.ts";
import { createLaunchWebhookHandler } from "./handler.ts";

function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}

class Backend implements LaunchWebhookBackend {
  verified = false;
  activated: PaidCheckout[] = [];
  event = {
    id: "evt_paid",
    type: "checkout.session.completed",
    data: {
      object: {
        id: "cs_paid",
        mode: "payment",
        payment_status: "paid",
        payment_intent: "pi_paid",
        amount_total: 9900,
        currency: "krw",
      },
    },
  };
  constructEvent(): Promise<typeof this.event> {
    this.verified = true;
    return Promise.resolve(this.event);
  }
  activate(checkout: PaidCheckout): Promise<void> {
    this.activated.push(checkout);
    return Promise.resolve();
  }
}

function handler(backend: Backend) {
  return createLaunchWebhookHandler({
    env: () => "test",
    createBackend: () => backend,
  });
}

function request(signature = "t=1,v1=signed") {
  return new Request(
    "https://project.supabase.co/functions/v1/stripe-launch-webhook",
    {
      method: "POST",
      headers: { "Stripe-Signature": signature },
      body: "raw-stripe-body",
    },
  );
}

Deno.test("activates only a verified paid one-time Checkout Session", async () => {
  const backend = new Backend();
  const response = await handler(backend)(request());
  assert(response.status === 200, "webhook failed");
  assert(backend.verified, "signature was not verified first");
  assert(
    backend.activated[0]?.sessionId === "cs_paid",
    "wrong session activated",
  );
  assert(backend.activated[0]?.amountTotal === 9900, "amount missing");
});

Deno.test("forwards the hosted secret key map to the webhook backend", async () => {
  const backend = new Backend();
  const environments: Record<string, string>[] = [];
  const webhook = createLaunchWebhookHandler({
    env: (name) =>
      name === "SUPABASE_SECRET_KEYS" ? '{"default":"secret"}' : "test",
    createBackend: (value) => {
      environments.push(value);
      return backend;
    },
  });

  assert((await webhook(request())).status === 200, "webhook failed");
  assert(
    environments[0]?.SUPABASE_SECRET_KEYS === '{"default":"secret"}',
    "secret map not forwarded",
  );
});

Deno.test("ignores unrelated signed events", async () => {
  const backend = new Backend();
  backend.event = { ...backend.event, type: "customer.created" };
  assert((await handler(backend)(request())).status === 200, "event rejected");
  assert(backend.activated.length === 0, "unrelated event activated kit");
});

Deno.test("rejects missing signatures and unpaid sessions", async () => {
  const backend = new Backend();
  assert(
    (await handler(backend)(request(""))).status === 400,
    "unsigned accepted",
  );
  backend.event.data.object.payment_status = "unpaid";
  assert((await handler(backend)(request())).status === 400, "unpaid accepted");
  assert(backend.activated.length === 0, "unpaid session activated kit");
});
