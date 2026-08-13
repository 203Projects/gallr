import type { SupabaseClient } from "@supabase/supabase-js";
import type {
  AdminEditorRepository,
  AdminEditorRequest,
  AdminEditorRequestStatus,
  AdminEditorUpdateInput,
  AdminManagedEditor,
  EditorOnboardingInput,
  EditorOnboardingResult,
} from "./AdminEditorRepository";
import { EditorRevisionConflictError as RevisionConflict } from "./AdminEditorRepository";

function record(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("The editor request returned an invalid response.");
  }
  return value as Record<string, unknown>;
}

function mapRequest(value: unknown): AdminEditorRequest {
  const row = record(value);
  const kind = row.kind;
  const status = row.status;
  if ((kind !== "profile" && kind !== "curation") ||
      (status !== "submitted" && status !== "accepted" && status !== "rejected")) {
    throw new Error("The editor request returned an invalid response.");
  }
  return {
    id: stringField(row, "id"),
    editorId: stringField(row, "editor_id"),
    editorName: stringField(row, "editor_name"),
    kind,
    status,
    payload: record(row.payload),
    reviewNotes: typeof row.review_notes === "string" ? row.review_notes : "",
    createdAt: stringField(row, "created_at"),
  };
}

function stringField(
  value: Record<string, unknown>,
  key: string,
): string {
  const field = value[key];
  if (typeof field !== "string" || field.trim().length === 0) {
    throw new Error("The editor invitation returned an invalid response.");
  }
  return field;
}

function optionalStringField(
  value: Record<string, unknown>,
  key: string,
): string | null {
  const field = value[key];
  if (field === null) return null;
  if (typeof field !== "string" || field.trim().length === 0) {
    throw new Error("The managed editor returned an invalid response.");
  }
  return field;
}

function managedStringField(
  value: Record<string, unknown>,
  key: string,
  allowEmpty = false,
): string {
  const field = value[key];
  if (
    typeof field !== "string" ||
    (!allowEmpty && field.trim().length === 0)
  ) {
    throw new Error("The managed editor returned an invalid response.");
  }
  return field;
}

function managedBooleanField(
  value: Record<string, unknown>,
  key: string,
): boolean {
  const field = value[key];
  if (typeof field !== "boolean") {
    throw new Error("The managed editor returned an invalid response.");
  }
  return field;
}

function mapManagedEditor(value: unknown): AdminManagedEditor {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("The managed editor returned an invalid response.");
  }
  const row = value as Record<string, unknown>;
  const revision = row.revision;
  if (!Number.isInteger(revision) || (revision as number) < 1) {
    throw new Error("The managed editor returned an invalid response.");
  }
  return {
    editorId: managedStringField(row, "editor_id"),
    email: optionalStringField(row, "email"),
    nameKo: managedStringField(row, "name_ko"),
    nameEn: managedStringField(row, "name_en", true),
    titleKo: managedStringField(row, "title_ko"),
    titleEn: managedStringField(row, "title_en", true),
    bioKo: managedStringField(row, "bio_ko"),
    bioEn: managedStringField(row, "bio_en", true),
    curationDescriptionKo: managedStringField(
      row,
      "curation_description_ko",
    ),
    curationDescriptionEn: managedStringField(
      row,
      "curation_description_en",
      true,
    ),
    isActive: managedBooleanField(row, "is_active"),
    activeFrom: managedStringField(row, "active_from"),
    activeTo: optionalStringField(row, "active_to"),
    revision: revision as number,
    hasAccess: managedBooleanField(row, "has_access"),
    accessActive: managedBooleanField(row, "access_active"),
  };
}

function editorMutationError(
  error: unknown,
  fallback: string,
): Error {
  if (error && typeof error === "object") {
    const row = error as Record<string, unknown>;
    if (row.code === "40001" && row.message === "revision_conflict") {
      const serverRevision = Number.parseInt(String(row.details ?? ""), 10);
      if (Number.isInteger(serverRevision) && serverRevision > 0) {
        return new RevisionConflict(serverRevision);
      }
    }
  }
  return new Error(fallback);
}

function editorUpdateParameters(input: AdminEditorUpdateInput) {
  return {
    p_name_ko: input.nameKo.trim(),
    p_name_en: input.nameEn.trim(),
    p_title_ko: input.titleKo.trim(),
    p_title_en: input.titleEn.trim(),
    p_bio_ko: input.bioKo.trim(),
    p_bio_en: input.bioEn.trim(),
    p_curation_description_ko: input.curationDescriptionKo.trim(),
    p_curation_description_en: input.curationDescriptionEn.trim(),
    p_is_active: input.isActive,
    p_active_from: input.activeFrom,
    p_active_to: input.activeTo,
  };
}

async function editorInvitationError(error: unknown): Promise<Error> {
  let code: unknown;
  if (error && typeof error === "object" && "context" in error) {
    const context = (error as { context?: unknown }).context;
    if (context instanceof Response) {
      try {
        const body = await context.clone().json() as { error?: unknown };
        code = body.error;
      } catch {
        // Keep malformed or unavailable provider details behind a safe message.
      }
    }
  }
  switch (code) {
    case "email_already_registered":
      return new Error(
        "That email already has an account or pending invitation.",
      );
    case "email_rate_limited":
      return new Error(
        "Invitation email limit reached. Wait a few minutes and try again.",
      );
    case "admin_role_required":
      return new Error("Only an active administrator can invite editors.");
    case "service_unavailable":
      return new Error("Editor invitations are temporarily unavailable.");
    default:
      return new Error("The editor invitation could not be sent.");
  }
}

export class SupabaseAdminEditorRepository implements AdminEditorRepository {
  constructor(private readonly client: SupabaseClient) {}

  async invite(input: EditorOnboardingInput): Promise<EditorOnboardingResult> {
    const { data, error } = await this.client.functions.invoke("invite-editor", {
      body: { email: input.email.trim() },
    });
    if (error) {
      throw await editorInvitationError(error);
    }
    if (data === null || typeof data !== "object" || Array.isArray(data)) {
      throw new Error("The editor invitation returned an invalid response.");
    }
    const row = data as Record<string, unknown>;
    if (row.status !== "invited") {
      throw new Error("The editor invitation returned an invalid response.");
    }
    return {
      email: stringField(row, "email"),
      status: "invited",
    };
  }

  async listEditors(): Promise<AdminManagedEditor[]> {
    const { data, error } = await this.client.rpc("admin_list_editors");
    if (error) throw new Error("Editors could not be loaded.");
    if (!Array.isArray(data)) {
      throw new Error("The managed editor list returned an invalid response.");
    }
    return data.map(mapManagedEditor);
  }

  async updateEditor(
    editorId: string,
    expectedRevision: number,
    input: AdminEditorUpdateInput,
  ): Promise<AdminManagedEditor> {
    const { data, error } = await this.client.rpc("admin_update_editor", {
      p_editor_id: editorId,
      p_expected_revision: expectedRevision,
      ...editorUpdateParameters(input),
    });
    if (error) {
      throw editorMutationError(error, "The editor could not be updated.");
    }
    return mapManagedEditor(data);
  }

  async setAccess(
    editorId: string,
    expectedRevision: number,
    active: boolean,
  ): Promise<AdminManagedEditor> {
    const { data, error } = await this.client.rpc("admin_set_editor_access", {
      p_editor_id: editorId,
      p_expected_revision: expectedRevision,
      p_active: active,
    });
    if (error) {
      throw editorMutationError(
        error,
        active
          ? "Editor access could not be restored."
          : "Editor access could not be deactivated.",
      );
    }
    return mapManagedEditor(data);
  }

  async listRequests(
    status: AdminEditorRequestStatus = "submitted",
  ): Promise<AdminEditorRequest[]> {
    const { data, error } = await this.client.rpc("admin_list_editor_requests", {
      p_status: status,
    });
    if (error || !Array.isArray(data)) {
      throw new Error("Editor requests could not be loaded.");
    }
    return data.map(mapRequest);
  }

  async reviewRequest(
    requestId: string,
    approve: boolean,
    reviewNotes: string,
  ): Promise<AdminEditorRequest> {
    const { data, error } = await this.client.rpc("admin_review_editor_request", {
      p_request_id: requestId,
      p_approve: approve,
      p_review_notes: reviewNotes.trim(),
    });
    if (error) throw new Error("The editor request could not be reviewed.");
    return mapRequest(data);
  }
}
