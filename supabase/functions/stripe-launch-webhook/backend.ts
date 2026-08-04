import { createClient } from "@supabase/supabase-js";
import Stripe from "stripe";
import { resolveSupabaseSecretKey } from "../_shared/supabase_keys.ts";

export interface PaidCheckout {
  sessionId: string;
  eventId: string;
  paymentIntentId: string;
  amountTotal: number;
  currency: string;
}

export interface LaunchWebhookBackend {
  constructEvent(rawBody: string, signature: string): Promise<{
    id: string;
    type: string;
    data: { object: unknown };
  }>;
  activate(checkout: PaidCheckout): Promise<void>;
}

function required(environment: Record<string, string>, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

class StripeSupabaseWebhookBackend implements LaunchWebhookBackend {
  private readonly stripe: Stripe;
  private readonly webhookSecret: string;
  private readonly client;

  constructor(environment: Record<string, string>) {
    this.stripe = new Stripe(required(environment, "STRIPE_SECRET_KEY"), {
      apiVersion: "2026-02-25.clover",
    });
    this.webhookSecret = required(environment, "STRIPE_LAUNCH_WEBHOOK_SECRET");
    this.client = createClient(
      required(environment, "SUPABASE_URL"),
      resolveSupabaseSecretKey(environment, "stripe-launch-webhook"),
      { auth: { autoRefreshToken: false, persistSession: false } },
    );
  }

  async constructEvent(rawBody: string, signature: string) {
    return await this.stripe.webhooks.constructEventAsync(
      rawBody,
      signature,
      this.webhookSecret,
      undefined,
      Stripe.createSubtleCryptoProvider(),
    );
  }

  async activate(checkout: PaidCheckout): Promise<void> {
    const { error } = await this.client.rpc("service_activate_launch_kit", {
      p_checkout_session_id: checkout.sessionId,
      p_stripe_event_id: checkout.eventId,
      p_payment_intent_id: checkout.paymentIntentId,
      p_amount_total: checkout.amountTotal,
      p_currency: checkout.currency,
    });
    if (error) throw new Error("Launch Kit activation failed.");
  }
}

export function createLaunchWebhookBackend(
  environment: Record<string, string>,
): LaunchWebhookBackend {
  return new StripeSupabaseWebhookBackend(environment);
}
