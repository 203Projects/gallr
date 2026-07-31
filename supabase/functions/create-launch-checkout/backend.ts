import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import Stripe from "stripe";
import {
  resolveSupabasePublishableKey,
  resolveSupabaseSecretKey,
} from "../_shared/supabase_keys.ts";

export interface CheckoutContext {
  launchKitId: string;
  exhibitionId: string;
  galleryId: string;
  status: "pending" | "active";
  ownerEmail: string;
  checkoutAttempt: number;
  checkoutSessionId?: string;
}

export interface CheckoutResult {
  sessionId: string;
  url: string;
}

export interface LaunchCheckoutBackend {
  prepare(
    authorization: string,
    exhibitionId: string,
    requestId: string,
  ): Promise<CheckoutContext>;
  createCheckout(context: CheckoutContext): Promise<CheckoutResult>;
}

type Environment = Record<string, string>;

export function launchCheckoutReturnUrls(galleryUrl: string): {
  successUrl: string;
  cancelUrl: string;
} {
  const baseUrl = galleryUrl.replace(/\/+$/u, "");
  return {
    successUrl: `${baseUrl}/?launch=success`,
    cancelUrl: `${baseUrl}/?launch=cancelled`,
  };
}

function required(environment: Environment, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function text(value: unknown): string | null {
  return typeof value === "string" && value ? value : null;
}

function nonnegativeInteger(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
    ? value
    : null;
}

export function checkoutDisposition(
  session: { status: string | null; url: string | null },
): "reuse" | "replace" | "wait" {
  if (session.status === "open" && session.url) return "reuse";
  if (session.status === "expired") return "replace";
  return "wait";
}

class SupabaseStripeCheckoutBackend implements LaunchCheckoutBackend {
  private readonly serviceClient: SupabaseClient;
  private readonly stripe: Stripe;
  private readonly priceId: string;
  private readonly galleryUrl: string;

  constructor(private readonly environment: Environment) {
    const url = required(environment, "SUPABASE_URL");
    this.serviceClient = createClient(
      url,
      resolveSupabaseSecretKey(environment, "create-launch-checkout"),
      {
        auth: { autoRefreshToken: false, persistSession: false },
      },
    );
    this.stripe = new Stripe(required(environment, "STRIPE_SECRET_KEY"), {
      apiVersion: "2026-02-25.clover",
    });
    this.priceId = required(environment, "STRIPE_LAUNCH_KIT_PRICE_ID");
    this.galleryUrl = required(environment, "GALLERY_WORKSPACE_URL").replace(
      /\/+$/u,
      "",
    );
  }

  async prepare(
    authorization: string,
    exhibitionId: string,
    requestId: string,
  ): Promise<CheckoutContext> {
    const client = createClient(
      required(this.environment, "SUPABASE_URL"),
      resolveSupabasePublishableKey(
        this.environment,
        "create-launch-checkout",
      ),
      {
        global: { headers: { Authorization: authorization } },
        auth: { autoRefreshToken: false, persistSession: false },
      },
    );
    const { data: userData, error: userError } = await client.auth.getUser(
      authorization.replace(/^Bearer\s+/iu, ""),
    );
    if (userError || !userData.user?.email) {
      throw new Error("Owner authorization failed.");
    }
    const { data, error } = await client.rpc(
      "owner_prepare_launch_kit_checkout",
      {
        p_exhibition_id: exhibitionId,
        p_request_id: requestId,
      },
    );
    const payload = object(data);
    const launchKitId = text(payload?.launch_kit_id);
    const galleryId = text(payload?.gallery_id);
    const returnedExhibitionId = text(payload?.exhibition_id);
    const status = text(payload?.status);
    const checkoutAttempt = nonnegativeInteger(payload?.checkout_attempt);
    const checkoutSessionId = payload?.checkout_session_id === null
      ? undefined
      : text(payload?.checkout_session_id) ?? undefined;
    if (
      error || !launchKitId || !galleryId ||
      returnedExhibitionId !== exhibitionId ||
      checkoutAttempt === null ||
      (status !== "pending" && status !== "active")
    ) throw new Error("Launch Kit checkout could not be prepared.");
    return {
      launchKitId,
      exhibitionId,
      galleryId,
      status,
      ownerEmail: userData.user.email,
      checkoutAttempt,
      checkoutSessionId,
    };
  }

  async createCheckout(context: CheckoutContext): Promise<CheckoutResult> {
    if (context.checkoutSessionId) {
      const existing = await this.stripe.checkout.sessions.retrieve(
        context.checkoutSessionId,
      );
      const disposition = checkoutDisposition(existing);
      if (disposition === "reuse" && existing.url) {
        return { sessionId: existing.id, url: existing.url };
      }
      if (disposition === "wait") {
        throw new Error("Checkout payment confirmation is pending.");
      }
    }
    const attempt = context.checkoutAttempt + 1;
    const returnUrls = launchCheckoutReturnUrls(this.galleryUrl);
    const session = await this.stripe.checkout.sessions.create({
      mode: "payment",
      line_items: [{ price: this.priceId, quantity: 1 }],
      customer_email: context.ownerEmail,
      client_reference_id: context.launchKitId,
      metadata: {
        launch_kit_id: context.launchKitId,
        exhibition_id: context.exhibitionId,
        gallery_id: context.galleryId,
      },
      success_url: returnUrls.successUrl,
      cancel_url: returnUrls.cancelUrl,
    }, {
      idempotencyKey: `launch-kit-${context.launchKitId}-attempt-${attempt}`,
    });
    if (!session.url) throw new Error("Stripe Checkout URL is unavailable.");
    const { error } = await this.serviceClient.rpc(
      "service_attach_launch_kit_checkout",
      {
        p_launch_kit_id: context.launchKitId,
        p_stripe_price_id: this.priceId,
        p_checkout_session_id: session.id,
        p_checkout_attempt: attempt,
      },
    );
    if (error) throw new Error("Checkout Session could not be attached.");
    return { sessionId: session.id, url: session.url };
  }
}

export function createLaunchCheckoutBackend(
  environment: Environment,
): LaunchCheckoutBackend {
  return new SupabaseStripeCheckoutBackend(environment);
}
