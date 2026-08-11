import type { SupabaseClient } from "@supabase/supabase-js";
import type {
  AdminEditorRepository,
  AdminEditorRequest,
  AdminEditorRequestStatus,
  EditorOnboardingInput,
  EditorOnboardingResult,
} from "./AdminEditorRepository";

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

export class SupabaseAdminEditorRepository implements AdminEditorRepository {
  constructor(private readonly client: SupabaseClient) {}

  async invite(input: EditorOnboardingInput): Promise<EditorOnboardingResult> {
    const { data, error } = await this.client.functions.invoke("invite-editor", {
      body: {
        email: input.email.trim(),
        editor_id: input.editorId.trim(),
        name_ko: input.nameKo.trim(),
        name_en: input.nameEn.trim(),
        title_ko: input.titleKo.trim(),
        title_en: input.titleEn.trim(),
        bio_ko: input.bioKo.trim(),
        bio_en: input.bioEn.trim(),
        curation_description_ko: input.curationDescriptionKo.trim(),
        curation_description_en: input.curationDescriptionEn.trim(),
        is_active: input.isActive,
        active_from: input.activeFrom,
        active_to: input.activeTo,
      },
    });
    if (error) {
      throw new Error(
        "The editor could not be invited. Check for an existing email or slug and try again.",
      );
    }
    if (data === null || typeof data !== "object" || Array.isArray(data)) {
      throw new Error("The editor invitation returned an invalid response.");
    }
    const row = data as Record<string, unknown>;
    if (typeof row.is_active !== "boolean") {
      throw new Error("The editor invitation returned an invalid response.");
    }
    return {
      editorId: stringField(row, "editor_id"),
      email: stringField(row, "email"),
      nameKo: stringField(row, "name_ko"),
      nameEn: typeof row.name_en === "string" ? row.name_en : "",
      active: row.is_active,
    };
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
