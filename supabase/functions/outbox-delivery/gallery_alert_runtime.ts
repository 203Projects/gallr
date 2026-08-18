import {
  deliverGalleryPublicationAlerts,
  type GalleryAlertBackend,
  type GalleryAlertClaim,
  type GalleryAlertFailure,
  type GalleryAlertJob,
  type GalleryAlertProvider,
  type GalleryAlertProviderResult,
  type GalleryPublicationEvent,
} from "./gallery_alert.ts";
import { resolveSupabaseSecretKey } from "../_shared/supabase_keys.ts";

type EnvironmentReader = (name: string) => string | undefined;
type Fetcher = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

export interface GalleryAlertAuthorizer {
  apns(): Promise<{ token: string; topic: string }>;
  fcm(): Promise<{ token: string; projectId: string }>;
}

interface ServiceAccount {
  client_email: string;
  private_key: string;
  project_id: string;
  token_uri?: string;
}

interface RuntimeDependencies {
  env: EnvironmentReader;
  fetch: Fetcher;
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SAFE_ERROR_CODE = /^[a-z][a-z0-9_]{2,79}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(
    /=+$/,
    "",
  );
}

function jsonBase64Url(value: unknown): string {
  return bytesToBase64Url(new TextEncoder().encode(JSON.stringify(value)));
}

function pemBytes(pem: string, label: string): Uint8Array {
  const normalized = pem.replaceAll("\\n", "\n");
  const encoded = normalized
    .replace(`-----BEGIN ${label}-----`, "")
    .replace(`-----END ${label}-----`, "")
    .replace(/\s/g, "");
  if (!encoded) throw new Error("provider_private_key_invalid");
  const binary = atob(encoded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

async function signedJwt(
  algorithm: "ES256" | "RS256",
  keyId: string | null,
  claims: Record<string, unknown>,
  privateKey: string,
): Promise<string> {
  const header = keyId ? { alg: algorithm, kid: keyId } : { alg: algorithm };
  const unsigned = `${jsonBase64Url(header)}.${jsonBase64Url(claims)}`;
  const keyAlgorithm = algorithm === "ES256"
    ? { name: "ECDSA", namedCurve: "P-256" }
    : { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" };
  const keyBytes = pemBytes(privateKey, "PRIVATE KEY");
  const keyData = keyBytes.buffer.slice(
    keyBytes.byteOffset,
    keyBytes.byteOffset + keyBytes.byteLength,
  ) as ArrayBuffer;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    keyData,
    keyAlgorithm,
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    algorithm === "ES256"
      ? { name: "ECDSA", hash: "SHA-256" }
      : { name: "RSASSA-PKCS1-v1_5" },
    key,
    new TextEncoder().encode(unsigned),
  );
  return `${unsigned}.${bytesToBase64Url(new Uint8Array(signature))}`;
}

function required(env: EnvironmentReader, name: string): string {
  const value = env(name)?.trim();
  if (!value) throw new Error("gallery_alert_configuration_missing");
  return value;
}

class RuntimeGalleryAlertAuthorizer implements GalleryAlertAuthorizer {
  private fcmToken:
    | { value: string; expiresAt: number; projectId: string }
    | null = null;
  private apnsToken:
    | { value: string; expiresAt: number; topic: string }
    | null = null;

  constructor(
    private readonly dependencies: RuntimeDependencies,
  ) {}

  async apns(): Promise<{ token: string; topic: string }> {
    const now = Math.floor(Date.now() / 1000);
    if (this.apnsToken && this.apnsToken.expiresAt > now + 60) {
      return { token: this.apnsToken.value, topic: this.apnsToken.topic };
    }
    const keyId = required(this.dependencies.env, "GALLERY_ALERT_APNS_KEY_ID");
    const teamId = required(
      this.dependencies.env,
      "GALLERY_ALERT_APNS_TEAM_ID",
    );
    const topic = required(this.dependencies.env, "GALLERY_ALERT_APNS_TOPIC");
    const privateKey = required(
      this.dependencies.env,
      "GALLERY_ALERT_APNS_PRIVATE_KEY",
    );
    if (
      !/^[A-Z0-9]{10}$/.test(keyId) ||
      !/^[A-Z0-9]{10}$/.test(teamId) ||
      !/^[A-Za-z0-9.-]{3,255}$/.test(topic)
    ) throw new Error("gallery_alert_configuration_invalid");
    const token = await signedJwt(
      "ES256",
      keyId,
      { iss: teamId, iat: now },
      privateKey,
    );
    this.apnsToken = { value: token, expiresAt: now + 50 * 60, topic };
    return { token, topic };
  }

  async fcm(): Promise<{ token: string; projectId: string }> {
    const now = Math.floor(Date.now() / 1000);
    if (this.fcmToken && this.fcmToken.expiresAt > now + 60) {
      return { token: this.fcmToken.value, projectId: this.fcmToken.projectId };
    }
    let account: ServiceAccount;
    try {
      const decoded: unknown = JSON.parse(
        required(
          this.dependencies.env,
          "GALLERY_ALERT_FCM_SERVICE_ACCOUNT_JSON",
        ),
      );
      if (!isRecord(decoded)) throw new Error();
      account = decoded as unknown as ServiceAccount;
    } catch {
      throw new Error("gallery_alert_configuration_invalid");
    }
    if (
      typeof account.client_email !== "string" ||
      !account.client_email.includes("@") ||
      typeof account.private_key !== "string" ||
      typeof account.project_id !== "string" ||
      !/^[a-z][a-z0-9-]{3,62}$/.test(account.project_id)
    ) throw new Error("gallery_alert_configuration_invalid");
    const tokenUri = account.token_uri ?? "https://oauth2.googleapis.com/token";
    if (tokenUri !== "https://oauth2.googleapis.com/token") {
      throw new Error("gallery_alert_configuration_invalid");
    }
    const assertion = await signedJwt(
      "RS256",
      null,
      {
        iss: account.client_email,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
        aud: tokenUri,
        iat: now,
        exp: now + 3600,
      },
      account.private_key,
    );
    const response = await this.dependencies.fetch(tokenUri, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
    });
    if (!response.ok) throw new Error("gallery_alert_provider_auth_failed");
    const decoded: unknown = await response.json();
    if (
      !isRecord(decoded) ||
      typeof decoded.access_token !== "string" ||
      decoded.access_token.length < 20
    ) throw new Error("gallery_alert_provider_auth_failed");
    const expiresIn = typeof decoded.expires_in === "number"
      ? Math.min(3600, Math.max(300, decoded.expires_in))
      : 3600;
    this.fcmToken = {
      value: decoded.access_token,
      expiresAt: now + expiresIn,
      projectId: account.project_id,
    };
    return { token: decoded.access_token, projectId: account.project_id };
  }
}

function localized(job: GalleryAlertJob): { title: string; body: string } {
  const korean = job.locale.toLowerCase().startsWith("ko");
  const gallery = korean
    ? job.gallery_name_ko || job.gallery_name_en
    : job.gallery_name_en || job.gallery_name_ko;
  const exhibition = korean
    ? job.exhibition_name_ko || job.exhibition_name_en
    : job.exhibition_name_en || job.exhibition_name_ko;
  return korean
    ? {
      title: `${gallery} 새 전시`,
      body: `${exhibition} 전시가 공개되었어요.`,
    }
    : { title: `New at ${gallery}`, body: `${exhibition} is now published.` };
}

async function collapseId(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return bytesToBase64Url(new Uint8Array(digest));
}

function safeProviderCode(
  prefix: string,
  status: number,
  reason?: string,
): string {
  const normalized = reason?.replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_|_$/g, "")
    .slice(0, 40);
  const value = `${prefix}_http_${status}${normalized ? `_${normalized}` : ""}`;
  return SAFE_ERROR_CODE.test(value) ? value : `${prefix}_provider_error`;
}

async function providerReason(response: Response): Promise<string | undefined> {
  try {
    const decoded: unknown = await response.json();
    if (!isRecord(decoded)) return undefined;
    if (typeof decoded.reason === "string") return decoded.reason;
    if (isRecord(decoded.error) && typeof decoded.error.status === "string") {
      return decoded.error.status;
    }
  } catch {
    // Bodies are optional and never returned verbatim.
  }
  return undefined;
}

function classify(
  provider: "apns" | "fcm",
  status: number,
  reason?: string,
): GalleryAlertProviderResult {
  if (status >= 200 && status < 300) return { outcome: "delivered" };
  const code = safeProviderCode(provider, status, reason);
  const normalized = reason?.toLowerCase() ?? "";
  const invalid = provider === "apns"
    ? status === 410 ||
      ["baddevicetoken", "devicetokennotfortopic", "unregistered"]
        .includes(normalized.replaceAll("_", ""))
    : normalized.includes("unregistered") || normalized.includes("not_found");
  if (invalid) return { outcome: "invalid_token", code };
  if (
    status === 408 || status === 409 || status === 425 || status === 429 ||
    status >= 500
  ) {
    return { outcome: "retryable", code };
  }
  return { outcome: "permanent", code };
}

export class HttpGalleryAlertProvider implements GalleryAlertProvider {
  constructor(
    private readonly fetcher: Fetcher,
    private readonly authorizer: GalleryAlertAuthorizer,
  ) {}

  async send(job: GalleryAlertJob): Promise<GalleryAlertProviderResult> {
    const message = localized(job);
    if (job.provider === "apns") {
      const authorization = await this.authorizer.apns();
      const host = job.provider_environment === "sandbox"
        ? "api.sandbox.push.apple.com"
        : "api.push.apple.com";
      const response = await this.fetcher(
        `https://${host}/3/device/${job.provider_token}`,
        {
          method: "POST",
          headers: {
            "Authorization": `bearer ${authorization.token}`,
            "Content-Type": "application/json",
            "apns-collapse-id": await collapseId(job.deduplication_key),
            "apns-expiration": "0",
            "apns-priority": "10",
            "apns-push-type": "alert",
            "apns-topic": authorization.topic,
          },
          body: JSON.stringify({
            aps: {
              alert: message,
              sound: "default",
            },
            deepLinkType: "exhibition",
            exhibitionId: job.exhibition_id,
          }),
        },
      );
      return classify("apns", response.status, await providerReason(response));
    }

    const authorization = await this.authorizer.fcm();
    const response = await this.fetcher(
      `https://fcm.googleapis.com/v1/projects/${authorization.projectId}/messages:send`,
      {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${authorization.token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            fid: job.provider_token,
            notification: message,
            data: {
              "gallr.notification.deeplink.type": "exhibition",
              "gallr.notification.deeplink.exhibitionId": job.exhibition_id,
            },
            android: {
              collapse_key: await collapseId(job.deduplication_key),
              priority: "high",
              notification: { channel_id: "gallr_reminders" },
            },
          },
        }),
      },
    );
    return classify("fcm", response.status, await providerReason(response));
  }
}

class RestGalleryAlertBackend implements GalleryAlertBackend {
  private readonly endpoint: string;
  private readonly credential: string;
  private readonly leaseOwner = `outbox-delivery:${crypto.randomUUID()}`;

  constructor(
    env: EnvironmentReader,
    private readonly fetcher: Fetcher,
  ) {
    const supabaseUrl = required(env, "SUPABASE_URL");
    const url = new URL(supabaseUrl);
    if (
      url.protocol !== "https:" || url.username || url.password || url.search ||
      url.hash
    ) {
      throw new Error("gallery_alert_configuration_invalid");
    }
    this.endpoint = `${url.origin}/rest/v1/rpc`;
    this.credential = resolveSupabaseSecretKey(
      {
        SUPABASE_SECRET_KEYS: env("SUPABASE_SECRET_KEYS"),
        SUPABASE_SECRET_KEY: env("SUPABASE_SECRET_KEY"),
        SUPABASE_SERVICE_ROLE_KEY: env("SUPABASE_SERVICE_ROLE_KEY"),
      },
      "outbox-delivery",
    );
  }

  private async rpc(
    name: string,
    body: Record<string, unknown>,
  ): Promise<unknown> {
    const response = await this.fetcher(`${this.endpoint}/${name}`, {
      method: "POST",
      headers: {
        "apikey": this.credential,
        "Authorization": `Bearer ${this.credential}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
    if (!response.ok) throw new Error("gallery_alert_database_unavailable");
    if (response.status === 204) return null;
    return await response.json();
  }

  async claim(eventId: string): Promise<GalleryAlertClaim> {
    const decoded = await this.rpc("claim_gallery_alert_delivery_jobs", {
      p_outbox_event_id: eventId,
      p_lease_owner: this.leaseOwner,
      p_lease_seconds: 120,
      p_batch_size: 50,
    });
    if (!isRecord(decoded) || !Array.isArray(decoded.jobs)) {
      throw new Error("gallery_alert_database_response_invalid");
    }
    const jobs = decoded.jobs as GalleryAlertJob[];
    if (!jobs.every(validJob)) {
      throw new Error("gallery_alert_database_response_invalid");
    }
    return { jobs, hasMore: decoded.has_more === true };
  }

  async markDelivered(jobId: number, leaseToken: string): Promise<void> {
    await this.rpc("complete_gallery_alert_delivery_job", {
      p_job_id: jobId,
      p_lease_token: leaseToken,
    });
  }

  async markFailed(
    jobId: number,
    leaseToken: string,
    failure: GalleryAlertFailure,
  ): Promise<void> {
    await this.rpc("fail_gallery_alert_delivery_job", {
      p_job_id: jobId,
      p_lease_token: leaseToken,
      p_error_code: failure.code,
      p_retryable: failure.retryable,
      p_invalid_token: failure.invalidToken,
    });
  }
}

function validJob(job: GalleryAlertJob): boolean {
  return Number.isSafeInteger(job.job_id) && job.job_id > 0 &&
    UUID_PATTERN.test(job.lease_token) &&
    (job.provider === "apns" || job.provider === "fcm") &&
    typeof job.provider_token === "string" && job.provider_token.length >= 20 &&
    (job.provider_environment === "sandbox" ||
      job.provider_environment === "production") &&
    typeof job.locale === "string" && job.locale.length <= 35 &&
    typeof job.exhibition_id === "string" && job.exhibition_id.length <= 200 &&
    typeof job.deduplication_key === "string" &&
    job.deduplication_key.length <= 512;
}

export function createGalleryAlertDispatcher(
  dependencies: RuntimeDependencies,
): (
  event: GalleryPublicationEvent,
) => Promise<{ ok: true } | { ok: false; code: string }> {
  const backend = new RestGalleryAlertBackend(
    dependencies.env,
    dependencies.fetch,
  );
  const provider = new HttpGalleryAlertProvider(
    dependencies.fetch,
    new RuntimeGalleryAlertAuthorizer(dependencies),
  );
  return (event) => deliverGalleryPublicationAlerts(backend, provider, event);
}
