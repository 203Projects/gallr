import { createClient, type SupabaseClient } from "@supabase/supabase-js";

import {
  resolveSupabasePublishableKey,
  resolveSupabaseSecretKey,
} from "../_shared/supabase_keys.ts";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

type Environment = Record<string, string | undefined>;

export type PreparationDecision =
  | { allowed: true; requestId: string }
  | {
    allowed: false;
    code:
      | "account_deletion_rate_limited"
      | "account_deletion_reauthentication_required"
      | "account_deletion_requires_support";
    retryAfterSeconds?: number;
  };

export interface AccountDeletionBackend {
  authenticate(authorization: string): Promise<string>;
  prepare(authorization: string): Promise<PreparationDecision>;
  deleteIdentity(userId: string): Promise<void>;
  cancelCleanup(requestId: string): Promise<void>;
}

export class AccountDeletionBackendError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "AccountDeletionBackendError";
  }
}

export function validatedSupabaseUrl(environment: Environment): string {
  const raw = environment.SUPABASE_URL?.trim();
  if (!raw) {
    throw new AccountDeletionBackendError(
      "server_configuration_missing",
      "SUPABASE_URL is required.",
    );
  }
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new AccountDeletionBackendError(
      "server_configuration_invalid",
      "SUPABASE_URL is invalid.",
    );
  }
  const loopback = url.protocol === "http:" &&
    (url.hostname === "127.0.0.1" || url.hostname === "localhost");
  if (url.protocol !== "https:" && !loopback) {
    throw new AccountDeletionBackendError(
      "server_configuration_invalid",
      "SUPABASE_URL must use HTTPS outside local development.",
    );
  }
  return url.toString().replace(/\/$/u, "");
}

export function parsePreparationDecision(value: unknown): PreparationDecision {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new AccountDeletionBackendError(
      "preparation_response_invalid",
      "Account deletion preparation returned an invalid response.",
    );
  }
  const record = value as Record<string, unknown>;
  if (
    record.allowed === true && typeof record.request_id === "string" &&
    UUID_PATTERN.test(record.request_id)
  ) {
    return { allowed: true, requestId: record.request_id };
  }
  if (
    record.allowed === false &&
    (
      record.code === "account_deletion_rate_limited" ||
      record.code === "account_deletion_reauthentication_required" ||
      record.code === "account_deletion_requires_support"
    )
  ) {
    const retryAfter = record.retry_after_seconds;
    if (
      retryAfter !== undefined &&
      (!Number.isSafeInteger(retryAfter) || (retryAfter as number) < 1 ||
        (retryAfter as number) > 900)
    ) {
      throw new AccountDeletionBackendError(
        "preparation_response_invalid",
        "Account deletion preparation returned an invalid retry interval.",
      );
    }
    return {
      allowed: false,
      code: record.code,
      retryAfterSeconds: retryAfter as number | undefined,
    };
  }
  throw new AccountDeletionBackendError(
    "preparation_response_invalid",
    "Account deletion preparation returned an invalid response.",
  );
}

function message(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return "unknown error";
}

async function serviceIdentityExists(
  client: SupabaseClient,
  userId: string,
): Promise<boolean> {
  const { data, error } = await client.auth.admin.getUserById(userId);
  if (!error && data.user) return true;
  const status = error && "status" in error ? Number(error.status) : null;
  const code = error && "code" in error ? String(error.code) : "";
  if (status === 404 || code === "user_not_found") return false;
  throw new AccountDeletionBackendError(
    "identity_deletion_status_unknown",
    "The Auth deletion result could not be verified.",
  );
}

export function createAccountDeletionBackend(
  environment: Environment,
): AccountDeletionBackend {
  const supabaseUrl = validatedSupabaseUrl(environment);
  const publishableKey = resolveSupabasePublishableKey(
    environment,
    "delete-account",
  );
  const secretKey = resolveSupabaseSecretKey(environment, "delete-account");
  const serviceClient = createClient(supabaseUrl, secretKey, {
    auth: {
      autoRefreshToken: false,
      detectSessionInUrl: false,
      persistSession: false,
    },
    global: { headers: { "X-Client-Info": "gallr-delete-account" } },
  });

  function callerClient(authorization: string) {
    return createClient(supabaseUrl, publishableKey, {
      auth: {
        autoRefreshToken: false,
        detectSessionInUrl: false,
        persistSession: false,
      },
      global: { headers: { Authorization: authorization } },
    });
  }

  return {
    async authenticate(authorization) {
      const token = authorization.slice("Bearer ".length);
      const { data, error } = await callerClient(authorization).auth.getUser(
        token,
      );
      if (error || !data.user || !UUID_PATTERN.test(data.user.id)) {
        throw new AccountDeletionBackendError(
          "authentication_required",
          "A valid authenticated session is required.",
        );
      }
      return data.user.id;
    },

    async prepare(authorization) {
      const { data, error } = await callerClient(authorization).rpc(
        "account_deletion_prepare",
      );
      if (error) {
        throw new AccountDeletionBackendError(
          "preparation_failed",
          "Account deletion could not be prepared.",
        );
      }
      return parsePreparationDecision(data);
    },

    async deleteIdentity(userId) {
      const { error } = await serviceClient.auth.admin.deleteUser(userId);
      if (error) {
        const diagnostic = message(error);
        if (diagnostic.includes("account_deletion_requires_support")) {
          throw new AccountDeletionBackendError(
            "account_deletion_requires_support",
            "This account requires assisted deletion.",
          );
        }
        if (!(await serviceIdentityExists(serviceClient, userId))) return;
        throw new AccountDeletionBackendError(
          "identity_deletion_failed",
          "The Auth identity could not be deleted.",
        );
      }
    },

    async cancelCleanup(requestId) {
      const { error } = await serviceClient.rpc("account_deletion_cancel", {
        p_request_id: requestId,
      });
      if (error) {
        throw new AccountDeletionBackendError(
          "cleanup_cancellation_failed",
          "The cleanup request could not be cancelled.",
        );
      }
    },
  };
}
