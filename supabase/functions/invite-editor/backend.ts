import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import {
  resolveSupabasePublishableKey,
  resolveSupabaseSecretKey,
} from "../_shared/supabase_keys.ts";

export interface EditorInvitePayload {
  email: string;
  editor_id: string;
  name_ko: string;
  name_en: string;
  title_ko: string;
  title_en: string;
  bio_ko: string;
  bio_en: string;
  curation_description_ko: string;
  curation_description_en: string;
  is_active: boolean;
  active_from: string;
  active_to: string | null;
}

export interface EditorInviteResult {
  editor_id: string;
  email: string;
  name_ko: string;
  name_en: string;
  is_active: boolean;
}

export class EditorInviteAuthorizationError extends Error {
  constructor(
    readonly code: "authentication_required" | "admin_role_required",
  ) {
    super(code);
    this.name = "EditorInviteAuthorizationError";
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
    if (inviteError || !invitedUserId) {
      throw new Error("Editor invitation could not be created.");
    }

    const { data, error } = await callerClient(this.environment, authorization)
      .rpc("admin_create_editor_onboarding", {
        p_user_id: invitedUserId,
        p_editor_id: payload.editor_id,
        p_name_ko: payload.name_ko,
        p_name_en: payload.name_en,
        p_title_ko: payload.title_ko,
        p_title_en: payload.title_en,
        p_bio_ko: payload.bio_ko,
        p_bio_en: payload.bio_en,
        p_curation_description_ko: payload.curation_description_ko,
        p_curation_description_en: payload.curation_description_en,
        p_is_active: payload.is_active,
        p_active_from: payload.active_from,
        p_active_to: payload.active_to,
      });
    if (
      error || data === null || typeof data !== "object" || Array.isArray(data)
    ) {
      // Compensate only the exact Auth user created in this request, so a
      // failed database command does not leave a retry-blocking orphan invite.
      await this.adminClient.auth.admin.deleteUser(invitedUserId).catch(() =>
        undefined
      );
      throw new Error("Editor profile could not be created.");
    }
    return {
      editor_id: payload.editor_id,
      email: payload.email,
      name_ko: payload.name_ko,
      name_en: payload.name_en,
      is_active: payload.is_active,
    };
  }
}

export function createEditorInviteBackend(
  environment: Environment,
): EditorInviteBackend {
  return new SupabaseEditorInviteBackend(environment);
}
