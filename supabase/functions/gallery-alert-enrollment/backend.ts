import { createClient } from "@supabase/supabase-js";
import {
  resolveSupabasePublishableKey,
  resolveSupabaseSecretKey,
} from "../_shared/supabase_keys.ts";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

export interface RegisterInstallationInput {
  sourceDigest: string;
  installationId: string;
  installationSecret: string;
  platform: string;
  locale: string;
  expectedRevision: number;
  actorUserId: string | null;
}

export interface RegisterPushTokenInput {
  installationId: string;
  installationSecret: string;
  provider: string;
  providerToken: string;
  providerEnvironment: string;
  expectedRevision: number;
}

export interface SetSubscriptionInput {
  installationId: string;
  installationSecret: string;
  galleryId: string;
  enabled: boolean;
  expectedRevision: number;
}

export interface GetInstallationInput {
  installationId: string;
  installationSecret: string;
}

export interface GalleryAlertEnrollmentBackend {
  /**
   * Resolves the account behind a bearer token. Returns null for a missing,
   * unverifiable, or anonymous session, matching the database rule that an
   * anonymous JWT never claims an installation.
   */
  resolveActor(authorization: string | null): Promise<string | null>;
  registerInstallation(input: RegisterInstallationInput): Promise<unknown>;
  registerPushToken(input: RegisterPushTokenInput): Promise<unknown>;
  setSubscription(input: SetSubscriptionInput): Promise<unknown>;
  getInstallation(input: GetInstallationInput): Promise<unknown>;
}

/**
 * Carries the database's stable error code so the handler can map it to a
 * status without ever echoing an upstream message to the caller.
 */
export class GalleryAlertEnrollmentError extends Error {
  constructor(readonly code: string) {
    super(code);
    this.name = "GalleryAlertEnrollmentError";
  }
}

function required(environment: Record<string, string>, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

/**
 * Reduces a PostgREST failure to one of the stable codes the handler maps.
 * Message text is inspected only to classify; it is never returned.
 */
function classify(message: string): string {
  for (
    const code of [
      "gallery_alert_rate_limited",
      "gallery_alert_subscription_limit_reached",
      "gallery_alert_installation_unauthorized",
      "installation_account_conflict",
      "revision_conflict",
      "gallery_not_alertable",
    ]
  ) {
    if (message.includes(code)) return code;
  }
  if (
    message.includes("_invalid") || message.includes("_required") ||
    message.includes("gallery_alert_push_token")
  ) {
    return "enrollment_invalid";
  }
  return "enrollment_unavailable";
}

class SupabaseGalleryAlertEnrollmentBackend
  implements GalleryAlertEnrollmentBackend {
  private readonly serviceClient;
  private readonly supabaseUrl: string;
  private readonly publishableKey: string;

  constructor(environment: Record<string, string>) {
    this.supabaseUrl = required(environment, "SUPABASE_URL");
    this.publishableKey = resolveSupabasePublishableKey(
      environment,
      "gallery-alert-enrollment",
    );
    this.serviceClient = createClient(
      this.supabaseUrl,
      resolveSupabaseSecretKey(environment, "gallery-alert-enrollment"),
      { auth: { autoRefreshToken: false, persistSession: false } },
    );
  }

  async resolveActor(authorization: string | null): Promise<string | null> {
    if (!authorization?.startsWith("Bearer ")) return null;
    const token = authorization.slice("Bearer ".length).trim();
    if (!token) return null;
    const callerClient = createClient(this.supabaseUrl, this.publishableKey, {
      auth: { autoRefreshToken: false, persistSession: false },
      global: { headers: { Authorization: authorization } },
    });
    const { data, error } = await callerClient.auth.getUser(token);
    if (error || !data.user || !UUID_PATTERN.test(data.user.id)) return null;
    return data.user.is_anonymous ? null : data.user.id;
  }

  private async call(
    name: string,
    parameters: Record<string, unknown>,
  ): Promise<unknown> {
    const { data, error } = await this.serviceClient.rpc(name, parameters);
    if (error) throw new GalleryAlertEnrollmentError(classify(error.message));
    return data;
  }

  registerInstallation(input: RegisterInstallationInput): Promise<unknown> {
    return this.call("service_register_gallery_alert_installation", {
      p_source_digest: input.sourceDigest,
      p_installation_id: input.installationId,
      p_installation_secret: input.installationSecret,
      p_platform: input.platform,
      p_locale: input.locale,
      p_expected_revision: input.expectedRevision,
      p_actor_user_id: input.actorUserId,
    });
  }

  registerPushToken(input: RegisterPushTokenInput): Promise<unknown> {
    return this.call("register_gallery_alert_push_token", {
      p_installation_id: input.installationId,
      p_installation_secret: input.installationSecret,
      p_provider: input.provider,
      p_provider_token: input.providerToken,
      p_provider_environment: input.providerEnvironment,
      p_expected_revision: input.expectedRevision,
    });
  }

  setSubscription(input: SetSubscriptionInput): Promise<unknown> {
    return this.call("set_gallery_alert_subscription", {
      p_installation_id: input.installationId,
      p_installation_secret: input.installationSecret,
      p_gallery_id: input.galleryId,
      p_enabled: input.enabled,
      p_expected_revision: input.expectedRevision,
    });
  }

  getInstallation(input: GetInstallationInput): Promise<unknown> {
    return this.call("get_gallery_alert_installation", {
      p_installation_id: input.installationId,
      p_installation_secret: input.installationSecret,
    });
  }
}

export function createGalleryAlertEnrollmentBackend(
  environment: Record<string, string>,
): GalleryAlertEnrollmentBackend {
  return new SupabaseGalleryAlertEnrollmentBackend(environment);
}
