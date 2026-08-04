import type { LaunchWebhookBackend, PaidCheckout } from "./backend.ts";

const MAX_BYTES = 256 * 1024;
type EnvironmentReader = (name: string) => string | undefined;

export interface WebhookHandlerDependencies {
  env?: EnvironmentReader;
  createBackend: (environment: Record<string, string>) => LaunchWebhookBackend;
}

function environment(env: EnvironmentReader): Record<string, string> {
  return Object.fromEntries([
    "SUPABASE_URL",
    "SUPABASE_SECRET_KEY",
    "SUPABASE_SECRET_KEYS",
    "SUPABASE_SERVICE_ROLE_KEY",
    "STRIPE_SECRET_KEY",
    "STRIPE_LAUNCH_WEBHOOK_SECRET",
  ].map((name) => [name, env(name) ?? ""]));
}

function paidCheckout(event: {
  id: string;
  data: { object: unknown };
}): PaidCheckout | null {
  const value = event.data.object;
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const session = value as Record<string, unknown>;
  const paymentIntent = session.payment_intent;
  if (
    typeof session.id !== "string" || session.mode !== "payment" ||
    session.payment_status !== "paid" ||
    typeof paymentIntent !== "string" ||
    typeof session.amount_total !== "number" ||
    !Number.isSafeInteger(session.amount_total) ||
    session.amount_total < 0 || typeof session.currency !== "string" ||
    !/^[a-z]{3}$/u.test(session.currency)
  ) return null;
  return {
    sessionId: session.id,
    eventId: event.id,
    paymentIntentId: paymentIntent,
    amountTotal: session.amount_total,
    currency: session.currency,
  };
}

export function createLaunchWebhookHandler(
  dependencies: WebhookHandlerDependencies,
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") {
      return new Response(null, { status: 405, headers: { Allow: "POST" } });
    }
    const signature = request.headers.get("stripe-signature") ?? "";
    if (!signature) return new Response(null, { status: 400 });
    const rawBody = await request.text();
    if (new TextEncoder().encode(rawBody).byteLength > MAX_BYTES) {
      return new Response(null, { status: 413 });
    }
    try {
      const backend = dependencies.createBackend(environment(env));
      const event = await backend.constructEvent(rawBody, signature);
      if (event.type !== "checkout.session.completed") {
        return new Response(null, { status: 200 });
      }
      const checkout = paidCheckout(event);
      if (!checkout) return new Response(null, { status: 400 });
      await backend.activate(checkout);
      return new Response(null, { status: 200 });
    } catch {
      return new Response(null, { status: 400 });
    }
  };
}
