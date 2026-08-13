import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import {
  resolveSupabasePublishableKey,
  resolveSupabaseSecretKey,
} from "../_shared/supabase_keys.ts";

export interface EditorInvitePayload {
  email: string;
}

export interface EditorInviteResult {
  email: string;
  status: "invited";
}

export class EditorInviteAuthorizationError extends Error {
  constructor(
    readonly code: "authentication_required" | "admin_role_required",
  ) {
    super(code);
    this.name = "EditorInviteAuthorizationError";
  }
}

export type EditorInviteFailureCode =
  | "email_already_registered"
  | "email_rate_limited"
  | "service_unavailable";

export class EditorInviteFailure extends Error {
  constructor(readonly code: EditorInviteFailureCode) {
    super(code);
    this.name = "EditorInviteFailure";
  }
}

export interface EditorInviteBackend {
  authorizeAdmin(authorization: string): Promise<void>;
  invite(
    authorization: string,
    payload: EditorInvitePayload,
  ): Promise<EditorInviteResult>;
}

type Environment = Record<string, string>;

function required(environment: Environment, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

export function editorInvitationRedirect(environment: Environment): string {
  const portalUrl = new URL(required(environment, "EDITOR_PORTAL_URL"));
  portalUrl.pathname = "/";
  portalUrl.search = "?onboarding=editor";
  portalUrl.hash = "";
  return portalUrl.toString();
}

function callerClient(
  environment: Environment,
  authorization: string,
): SupabaseClient {
  return createClient(
    required(environment, "SUPABASE_URL"),
    resolveSupabasePublishableKey(environment, "invite-editor"),
    {
      global: { headers: { Authorization: authorization } },
      auth: { autoRefreshToken: false, persistSession: false },
    },
  );
}

class SupabaseEditorInviteBackend implements EditorInviteBackend {
  private readonly adminClient: SupabaseClient;
  private readonly redirectTo: string;

  constructor(private readonly environment: Environment) {
    this.adminClient = createClient(
      required(environment, "SUPABASE_URL"),
      resolveSupabaseSecretKey(environment, "invite-editor"),
      { auth: { autoRefreshToken: false, persistSession: false } },
    );
    this.redirectTo = editorInvitationRedirect(environment);
  }

  async authorizeAdmin(authorization: string): Promise<void> {
    const { data, error } = await callerClient(this.environment, authorization)
      .rpc("admin_current_staff");
    if (error) {
      const status = typeof error === "object" && error !== null &&
          "status" in error
        ? (error as { status?: unknown }).status
        : null;
      if (status === 401) {
        throw new EditorInviteAuthorizationError("authentication_required");
      }
      throw new EditorInviteAuthorizationError("admin_role_required");
    }
    if (
      data === null || typeof data !== "object" || Array.isArray(data) ||
      (data as Record<string, unknown>).role !== "admin" ||
      (data as Record<string, unknown>).active !== true
    ) {
      throw new EditorInviteAuthorizationError("admin_role_required");
    }
  }

  async invite(
    authorization: string,
    payload: EditorInvitePayload,
  ): Promise<EditorInviteResult> {
    const { data: invited, error: inviteError } = await this.adminClient.auth
      .admin.inviteUserByEmail(payload.email, { redirectTo: this.redirectTo });
    const invitedUserId = invited.user?.id;
    if (inviteError) {
      const code = inviteError.code;
      const status = inviteError.status;
      if (code === "over_email_send_rate_limit" || status === 429) {
        throw new EditorInviteFailure("email_rate_limited");
      }
      if (
        code === "email_exists" ||
        code === "user_already_exists" ||
        /already (?:been )?(?:registered|exists)/iu.test(inviteError.message)
      ) {
        throw new EditorInviteFailure("email_already_registered");
      }
      throw new EditorInviteFailure("service_unavailable");
    }
    if (!invitedUserId) {
      throw new EditorInviteFailure("service_unavailable");
    }

    const { data, error } = await callerClient(this.environment, authorization)
      .rpc("admin_register_editor_invitation", {
        p_user_id: invitedUserId,
      });
    if (
      error || data === null || typeof data !== "object" || Array.isArray(data)
    ) {
      // Compensate only the exact Auth user created in this request, so a
      // failed database command does not leave a retry-blocking orphan invite.
      await this.adminClient.auth.admin.deleteUser(invitedUserId).catch(() =>
        undefined
      );
      throw new EditorInviteFailure("service_unavailable");
    }
    return {
      email: payload.email,
      status: "invited",
    };
  }
}

export function createEditorInviteBackend(
  environment: Environment,
): EditorInviteBackend {
  return new SupabaseEditorInviteBackend(environment);
}
