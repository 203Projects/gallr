import type { SupabaseClient } from "@supabase/supabase-js";
import {
  CURATOR_NOTE_DECLINE_REASONS,
  CuratorNoteDraftingUnavailableError,
  CuratorNoteInvalidTransitionError,
  CuratorNoteNotFoundError,
  CuratorNoteNotQualifyingError,
  CuratorNoteNotStaffError,
  CuratorNoteStaleError,
  CuratorNoteUnsupportedError,
  MalformedCuratorNotePayloadError,
  type AdminCuratorNoteRepository,
  type CuratorNoteDeclineReason,
  type CuratorNoteDraftRequestResult,
  type CuratorNoteEdit,
  type CuratorNoteFacts,
  type CuratorNoteInsight,
  type CuratorNoteLanguage,
  type CuratorNoteQueueItem,
  type CuratorNoteRequestKind,
  type CuratorNoteStatus,
  type CuratorNoteVersion,
} from "./AdminCuratorNoteRepository";

type JsonRecord = Record<string, unknown>;

const STATUSES: readonly CuratorNoteStatus[] = ["draft", "unusable", "replaced", "declined", "approved", "superseded"];
const REQUEST_KINDS: readonly CuratorNoteRequestKind[] = ["scheduled", "single", "regenerate"];
const FUNCTION_NAME = "draft-curator-notes";

/** Calls the staff-guarded curator note functions; the database checks the publisher tier on every call. */
export class SupabaseAdminCuratorNoteRepository implements AdminCuratorNoteRepository {
  constructor(private readonly client: SupabaseClient) {}

  async listQueue(): Promise<CuratorNoteQueueItem[]> {
    const rpcName = "list_curator_note_queue";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throw noteError(rpcName, error);
    return array(data, rpcName, "$").map((item, index) => mapQueueItem(item, rpcName, `$[${index}]`));
  }

  async get(exhibitionId: string): Promise<CuratorNoteFacts> {
    const rpcName = "get_curator_note";
    const { data, error } = await this.client.rpc(rpcName, { p_exhibition_id: exhibitionId });
    if (error !== null) throw noteError(rpcName, error);
    const row = record(data, rpcName, "$");
    const packet = row.packet;
    return {
      exhibitionId: string(row, "exhibition_id", rpcName, "$"),
      autoDraftBlocked: boolean(row, "auto_draft_blocked", rpcName, "$"),
      approvedVersionId: nullableString(row, "approved_version_id", rpcName, "$"),
      qualifies: boolean(row, "qualifies", rpcName, "$"),
      packet: packet === null || packet === undefined ? null : record(packet, rpcName, "$.packet"),
      packetHash: nullableString(row, "packet_hash", rpcName, "$"),
      versions: array(row.versions, rpcName, "$.versions").map((version, index) =>
        mapVersion(version, rpcName, `$.versions[${index}]`)
      ),
    };
  }

  saveEdit(versionId: string, edit: CuratorNoteEdit, expectedRevision: string): Promise<CuratorNoteVersion> {
    const fields: JsonRecord = {};
    if (edit.hookKo !== undefined) fields.hook_ko = edit.hookKo;
    if (edit.hookEn !== undefined) fields.hook_en = edit.hookEn;
    if (edit.tipKo !== undefined) fields.tip_ko = edit.tipKo;
    if (edit.tipEn !== undefined) fields.tip_en = edit.tipEn;
    if (edit.insightsKo !== undefined) fields.insights_ko = edit.insightsKo;
    if (edit.insightsEn !== undefined) fields.insights_en = edit.insightsEn;
    return this.callVersion("save_curator_note_edit", {
      p_version_id: versionId,
      p_fields: fields,
      p_expected_revision: expectedRevision,
    });
  }

  markSentence(versionId: string, ref: string, unsupported: boolean, expectedRevision: string): Promise<CuratorNoteVersion> {
    return this.callVersion("mark_curator_note_sentence", {
      p_version_id: versionId,
      p_ref: ref,
      p_unsupported: unsupported,
      p_expected_revision: expectedRevision,
    });
  }

  approve(versionId: string, expectedRevision: string): Promise<CuratorNoteVersion> {
    return this.callVersion("approve_curator_note", { p_version_id: versionId, p_expected_revision: expectedRevision });
  }

  decline(
    versionId: string,
    reason: CuratorNoteDeclineReason,
    note: string | null,
    expectedRevision: string,
  ): Promise<CuratorNoteVersion> {
    return this.callVersion("decline_curator_note", {
      p_version_id: versionId,
      p_reason: reason,
      p_note: note,
      p_expected_revision: expectedRevision,
    });
  }

  async requestDraft(exhibitionId: string, kind: "single" | "regenerate"): Promise<CuratorNoteDraftRequestResult> {
    const { data, error } = await this.client.functions.invoke(FUNCTION_NAME, {
      body: { mode: "single", exhibition_id: exhibitionId, request_kind: kind },
    });
    if (error) throw functionError(error);
    const row = record(data, FUNCTION_NAME, "$");
    const results = array(row.results, FUNCTION_NAME, "$.results");
    const first = results.length > 0 ? record(results[0], FUNCTION_NAME, "$.results[0]") : null;
    const outcome = first?.outcome;
    if (outcome !== "drafted" && outcome !== "unusable") {
      throw new MalformedCuratorNotePayloadError(FUNCTION_NAME, "$.results[0].outcome", "drafted or unusable");
    }
    return { outcome, code: typeof first?.code === "string" ? first.code : null };
  }

  recordExport(versionId: string, language: CuratorNoteLanguage, slideCount: number): Promise<CuratorNoteVersion> {
    return this.callVersion("record_curator_note_export", {
      p_version_id: versionId,
      p_language: language,
      p_slide_count: slideCount,
    });
  }

  private async callVersion(rpcName: string, args: JsonRecord): Promise<CuratorNoteVersion> {
    const { data, error } = await this.client.rpc(rpcName, args);
    if (error !== null) throw noteError(rpcName, error);
    return mapVersion(data, rpcName, "$");
  }
}

function noteError(rpcName: string, error: { message?: string; code?: string }): Error {
  switch (error.message) {
    case "curator_note_not_staff":
      return new CuratorNoteNotStaffError();
    case "curator_note_stale":
      return new CuratorNoteStaleError();
    case "curator_note_unsupported":
      return new CuratorNoteUnsupportedError();
    case "curator_note_invalid_transition":
      return new CuratorNoteInvalidTransitionError();
    case "curator_note_not_found":
      return new CuratorNoteNotFoundError();
    default:
      return new Error(`${rpcName} failed${error.code ? ` (${error.code})` : ""}.`);
  }
}

function functionError(error: unknown): Error {
  const status = (error as { context?: { status?: number } } | null)?.context?.status;
  if (status === 401) return new CuratorNoteNotStaffError();
  if (status === 404) return new CuratorNoteNotQualifyingError();
  return new CuratorNoteDraftingUnavailableError();
}

function mapQueueItem(value: unknown, rpcName: string, path: string): CuratorNoteQueueItem {
  const row = record(value, rpcName, path);
  return {
    versionId: string(row, "version_id", rpcName, path),
    exhibitionId: string(row, "exhibition_id", rpcName, path),
    nameKo: string(row, "name_ko", rpcName, path),
    nameEn: string(row, "name_en", rpcName, path),
    isFeatured: boolean(row, "is_featured", rpcName, path),
    closingDate: nullableString(row, "closing_date", rpcName, path),
    versionNumber: number(row, "version_number", rpcName, path),
    promptVersion: string(row, "prompt_version", rpcName, path),
    generatedAt: string(row, "generated_at", rpcName, path),
    requestKind: oneOf(row, "request_kind", REQUEST_KINDS, rpcName, path),
    unsupportedCount: number(row, "unsupported_count", rpcName, path),
    edited: boolean(row, "edited", rpcName, path),
    revision: string(row, "revision", rpcName, path),
  };
}

function mapVersion(value: unknown, rpcName: string, path: string): CuratorNoteVersion {
  const row = record(value, rpcName, path);
  return {
    id: string(row, "id", rpcName, path),
    exhibitionId: string(row, "exhibition_id", rpcName, path),
    versionNumber: number(row, "version_number", rpcName, path),
    status: oneOf(row, "status", STATUSES, rpcName, path),
    hookKo: string(row, "hook_ko", rpcName, path),
    hookEn: string(row, "hook_en", rpcName, path),
    insightsKo: insights(row, "insights_ko", rpcName, path),
    insightsEn: insights(row, "insights_en", rpcName, path),
    tipKo: string(row, "tip_ko", rpcName, path),
    tipEn: string(row, "tip_en", rpcName, path),
    unsupportedRefs: strings(row, "unsupported_refs", rpcName, path),
    edited: boolean(row, "edited", rpcName, path),
    producer: string(row, "producer", rpcName, path),
    model: string(row, "model", rpcName, path),
    promptVersion: string(row, "prompt_version", rpcName, path),
    sourceFields: strings(row, "source_fields", rpcName, path),
    sourceHash: string(row, "source_hash", rpcName, path),
    requestKind: oneOf(row, "request_kind", REQUEST_KINDS, rpcName, path),
    generatedAt: string(row, "generated_at", rpcName, path),
    rejectionCode: nullableString(row, "rejection_code", rpcName, path),
    reviewerNameKo: nullableString(row, "reviewer_name_ko", rpcName, path),
    reviewerNameEn: nullableString(row, "reviewer_name_en", rpcName, path),
    decidedAt: nullableString(row, "decided_at", rpcName, path),
    declineReason: row.decline_reason == null ? null : oneOf(row, "decline_reason", CURATOR_NOTE_DECLINE_REASONS, rpcName, path),
    declineNote: nullableString(row, "decline_note", rpcName, path),
    approvedAt: nullableString(row, "approved_at", rpcName, path),
    revision: string(row, "revision", rpcName, path),
  };
}

function insights(row: JsonRecord, key: string, rpcName: string, path: string): CuratorNoteInsight[] {
  return array(row[key], rpcName, `${path}.${key}`).map((item, index) => {
    const insight = record(item, rpcName, `${path}.${key}[${index}]`);
    return {
      text: string(insight, "text", rpcName, `${path}.${key}[${index}]`),
      grounding: strings(insight, "grounding", rpcName, `${path}.${key}[${index}]`),
    };
  });
}

function record(value: unknown, rpcName: string, path: string): JsonRecord {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new MalformedCuratorNotePayloadError(rpcName, path, "an object");
  }
  return value as JsonRecord;
}

function array(value: unknown, rpcName: string, path: string): unknown[] {
  if (!Array.isArray(value)) throw new MalformedCuratorNotePayloadError(rpcName, path, "an array");
  return value;
}

function string(row: JsonRecord, key: string, rpcName: string, path: string): string {
  const value = row[key];
  if (typeof value !== "string") throw new MalformedCuratorNotePayloadError(rpcName, `${path}.${key}`, "a string");
  return value;
}

function nullableString(row: JsonRecord, key: string, rpcName: string, path: string): string | null {
  const value = row[key];
  if (value === null || value === undefined) return null;
  if (typeof value !== "string") throw new MalformedCuratorNotePayloadError(rpcName, `${path}.${key}`, "a string or null");
  return value;
}

function strings(row: JsonRecord, key: string, rpcName: string, path: string): string[] {
  return array(row[key], rpcName, `${path}.${key}`).map((item, index) => {
    if (typeof item !== "string") throw new MalformedCuratorNotePayloadError(rpcName, `${path}.${key}[${index}]`, "a string");
    return item;
  });
}

function number(row: JsonRecord, key: string, rpcName: string, path: string): number {
  const value = row[key];
  if (typeof value !== "number") throw new MalformedCuratorNotePayloadError(rpcName, `${path}.${key}`, "a number");
  return value;
}

function boolean(row: JsonRecord, key: string, rpcName: string, path: string): boolean {
  const value = row[key];
  if (typeof value !== "boolean") throw new MalformedCuratorNotePayloadError(rpcName, `${path}.${key}`, "a boolean");
  return value;
}

function oneOf<T extends string>(row: JsonRecord, key: string, allowed: readonly T[], rpcName: string, path: string): T {
  const value = row[key];
  if (typeof value !== "string" || !allowed.includes(value as T)) {
    throw new MalformedCuratorNotePayloadError(rpcName, `${path}.${key}`, `one of ${allowed.join(", ")}`);
  }
  return value as T;
}
