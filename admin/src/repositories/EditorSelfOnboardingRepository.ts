import type { SupabaseClient } from "@supabase/supabase-js";

export interface EditorSelfOnboardingInput {
  editorId: string;
  nameKo: string;
  nameEn: string;
  titleKo: string;
  titleEn: string;
  bioKo: string;
  bioEn: string;
  curationDescriptionKo: string;
  curationDescriptionEn: string;
}

export interface EditorSelfOnboardingResult {
  editorId: string;
  nameKo: string;
  nameEn: string;
  active: false;
}

export interface EditorSelfOnboardingRepository {
  complete(
    input: EditorSelfOnboardingInput,
  ): Promise<EditorSelfOnboardingResult>;
}

function requiredString(
  row: Record<string, unknown>,
  key: string,
): string {
  const value = row[key];
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error("The completed editor profile returned an invalid response.");
  }
  return value;
}

function onboardingError(error: unknown): Error {
  if (error && typeof error === "object") {
    const message = (error as { message?: unknown }).message;
    if (message === "editor_slug_invalid") {
      return new Error("Editor slug must use lowercase letters, numbers, and single hyphens.");
    }
    if (message === "editor_profile_invalid") {
      return new Error("Complete all required Korean profile fields.");
    }
    if (message === "editor_invitation_required") {
      return new Error("This editor invitation is no longer valid.");
    }
    const code = (error as { code?: unknown }).code;
    if (code === "23505") {
      return new Error("That editor slug is already in use.");
    }
  }
  return new Error("The editor profile could not be created.");
}

export class SupabaseEditorSelfOnboardingRepository
implements EditorSelfOnboardingRepository {
  constructor(private readonly client: SupabaseClient) {}

  async complete(
    input: EditorSelfOnboardingInput,
  ): Promise<EditorSelfOnboardingResult> {
    const { data, error } = await this.client.rpc(
      "editor_complete_onboarding",
      {
        p_editor_id: input.editorId.trim(),
        p_name_ko: input.nameKo.trim(),
        p_name_en: input.nameEn.trim(),
        p_title_ko: input.titleKo.trim(),
        p_title_en: input.titleEn.trim(),
        p_bio_ko: input.bioKo.trim(),
        p_bio_en: input.bioEn.trim(),
        p_curation_description_ko: input.curationDescriptionKo.trim(),
        p_curation_description_en: input.curationDescriptionEn.trim(),
      },
    );
    if (error) throw onboardingError(error);
    if (data === null || typeof data !== "object" || Array.isArray(data)) {
      throw new Error("The completed editor profile returned an invalid response.");
    }
    const row = data as Record<string, unknown>;
    if (row.is_active !== false) {
      throw new Error("The completed editor profile returned an invalid response.");
    }
    return {
      editorId: requiredString(row, "editor_id"),
      nameKo: requiredString(row, "name_ko"),
      nameEn: typeof row.name_en === "string" ? row.name_en : "",
      active: false,
    };
  }
}
