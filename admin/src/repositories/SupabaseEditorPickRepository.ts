import type { SupabaseClient } from "@supabase/supabase-js";
import type {
  EditorCurationChange,
  EditorCurationSubmission,
  EditorExhibitionSuggestion,
  EditorPickCandidate,
  EditorProfile,
} from "../domain";
import type { EditorPickRepository } from "./EditorPickRepository";

type JsonRecord = Record<string, unknown>;

export class MalformedEditorPickPayloadError extends Error {
  constructor(rpcName: string, path: string, expected: string) {
    super(`${rpcName} returned malformed data at ${path}: expected ${expected}.`);
    this.name = "MalformedEditorPickPayloadError";
  }
}

function record(value: unknown, rpcName: string, path: string): JsonRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new MalformedEditorPickPayloadError(rpcName, path, "an object");
  }
  return value as JsonRecord;
}

function string(
  value: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string {
  if (typeof value[key] !== "string") {
    throw new MalformedEditorPickPayloadError(
      rpcName,
      `${path}.${key}`,
      "a string",
    );
  }
  return value[key];
}

function nonEmptyString(
  value: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string {
  const result = string(value, key, rpcName, path);
  if (result.trim().length === 0) {
    throw new MalformedEditorPickPayloadError(
      rpcName,
      `${path}.${key}`,
      "a non-empty string",
    );
  }
  return result;
}

function boolean(
  value: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): boolean {
  if (typeof value[key] !== "boolean") {
    throw new MalformedEditorPickPayloadError(
      rpcName,
      `${path}.${key}`,
      "a boolean",
    );
  }
  return value[key];
}

function positiveInteger(
  value: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): number {
  const result = value[key];
  if (!Number.isSafeInteger(result) || (result as number) < 1) {
    throw new MalformedEditorPickPayloadError(
      rpcName,
      `${path}.${key}`,
      "a positive integer",
    );
  }
  return result as number;
}

function mapCandidate(
  value: unknown,
  rpcName: string,
  path: string,
): EditorPickCandidate {
  const row = record(value, rpcName, path);
  return {
    id: nonEmptyString(row, "id", rpcName, path),
    workingVersionId: nonEmptyString(
      row,
      "working_version_id",
      rpcName,
      path,
    ),
    publishedVersionId: nonEmptyString(
      row,
      "published_version_id",
      rpcName,
      path,
    ),
    revision: positiveInteger(row, "revision", rpcName, path),
    nameKo: string(row, "name_ko", rpcName, path),
    nameEn: string(row, "name_en", rpcName, path),
    venueNameKo: string(row, "venue_name_ko", rpcName, path),
    venueNameEn: string(row, "venue_name_en", rpcName, path),
    openingDate: string(row, "opening_date", rpcName, path),
    closingDate: string(row, "closing_date", rpcName, path),
    selected: boolean(row, "selected", rpcName, path),
    live: boolean(row, "live", rpcName, path),
  };
}

function requestResult(
  value: unknown,
  rpcName: string,
  idKey: "request_id" | "submission_id",
): { id: string; status: "submitted" } {
  const row = record(value, rpcName, "$");
  const status = string(row, "status", rpcName, "$");
  if (status !== "submitted") {
    throw new MalformedEditorPickPayloadError(rpcName, "$.status", '"submitted"');
  }
  return { id: nonEmptyString(row, idKey, rpcName, "$"), status };
}

function rpcError(rpcName: string, error: unknown): Error {
  if (typeof error === "object" && error !== null) {
    const message = (error as { message?: unknown }).message;
    if (typeof message === "string" && message.length > 0) {
      return new Error(`${rpcName} failed: ${message}`);
    }
  }
  return new Error(`${rpcName} failed.`);
}

export class SupabaseEditorPickRepository implements EditorPickRepository {
  constructor(private readonly client: SupabaseClient) {}

  async list(search: string): Promise<EditorPickCandidate[]> {
    const rpcName = "editor_list_pick_candidates";
    const { data, error } = await this.client.rpc(rpcName, {
      p_search: search.trim(),
    });
    if (error !== null) throw rpcError(rpcName, error);
    if (!Array.isArray(data)) {
      throw new MalformedEditorPickPayloadError(rpcName, "$", "an array");
    }
    return data.map((item, index) =>
      mapCandidate(item, rpcName, `$[${index}]`),
    );
  }

  async getProfile(): Promise<EditorProfile> {
    const rpcName = "editor_get_profile";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throw rpcError(rpcName, error);
    const row = record(data, rpcName, "$");
    return {
      editorId: nonEmptyString(row, "editor_id", rpcName, "$"),
      nameKo: nonEmptyString(row, "name_ko", rpcName, "$"),
      nameEn: string(row, "name_en", rpcName, "$"),
      bioKo: string(row, "bio_ko", rpcName, "$"),
      bioEn: string(row, "bio_en", rpcName, "$"),
      curationDescriptionKo: string(row, "curation_description_ko", rpcName, "$"),
      curationDescriptionEn: string(row, "curation_description_en", rpcName, "$"),
      pendingProfile: boolean(row, "pending_profile", rpcName, "$"),
      pendingCuration: boolean(row, "pending_curation", rpcName, "$"),
    };
  }

  async submitCuration(
    changes: EditorCurationChange[],
    curationDescriptionKo: string,
    curationDescriptionEn: string,
  ): Promise<EditorCurationSubmission> {
    const rpcName = "editor_submit_curation";
    const { data, error } = await this.client.rpc(rpcName, {
      p_changes: changes.map((change) => ({
        exhibition_id: change.exhibitionId,
        expected_version_id: change.expectedVersionId,
        expected_revision: change.expectedRevision,
        selected: change.selected,
      })),
      p_curation_description_ko: curationDescriptionKo.trim(),
      p_curation_description_en: curationDescriptionEn.trim(),
    });
    if (error !== null) throw rpcError(rpcName, error);
    const row = record(data, rpcName, "$");
    const request = requestResult(data, rpcName, "request_id");
    if (!Array.isArray(row.candidates)) {
      throw new MalformedEditorPickPayloadError(rpcName, "$.candidates", "an array");
    }
    return {
      requestId: request.id,
      status: request.status,
      candidates: row.candidates.map((item, index) =>
        mapCandidate(item, rpcName, `$.candidates[${index}]`)
      ),
    };
  }

  async submitProfile(
    bioKo: string,
    bioEn: string,
  ): Promise<{ requestId: string; status: "submitted" }> {
    const rpcName = "editor_submit_profile";
    const { data, error } = await this.client.rpc(rpcName, {
      p_bio_ko: bioKo.trim(),
      p_bio_en: bioEn.trim(),
    });
    if (error !== null) throw rpcError(rpcName, error);
    const result = requestResult(data, rpcName, "request_id");
    return { requestId: result.id, status: result.status };
  }

  async submitExhibition(
    suggestion: EditorExhibitionSuggestion,
  ): Promise<{ submissionId: string; status: "submitted" }> {
    const rpcName = "editor_submit_exhibition";
    const { data, error } = await this.client.rpc(rpcName, {
      p_payload: {
        name_ko: suggestion.nameKo.trim(),
        name_en: suggestion.nameEn.trim(),
        venue_name_ko: suggestion.venueNameKo.trim(),
        venue_name_en: suggestion.venueNameEn.trim(),
        opening_date: suggestion.openingDate,
        closing_date: suggestion.closingDate,
        address_ko: suggestion.addressKo.trim(),
        address_en: suggestion.addressEn.trim(),
        hours: suggestion.hours.trim(),
        description_ko: suggestion.descriptionKo.trim(),
        description_en: suggestion.descriptionEn.trim(),
      },
    });
    if (error !== null) throw rpcError(rpcName, error);
    const result = requestResult(data, rpcName, "submission_id");
    return { submissionId: result.id, status: result.status };
  }

  async setSelected(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    selected: boolean,
  ): Promise<EditorPickCandidate> {
    const rpcName = "editor_set_pick";
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_selected: selected,
    });
    if (error !== null) throw rpcError(rpcName, error);
    return mapCandidate(data, rpcName, "$");
  }
}
