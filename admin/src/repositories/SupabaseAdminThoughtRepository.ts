import type { SupabaseClient } from "@supabase/supabase-js";
import {
  MalformedAdminThoughtPayloadError,
  THOUGHT_REPORT_REASONS,
  ThoughtInvalidTransitionError,
  ThoughtModerationNotStaffError,
  ThoughtNotFoundError,
  ThoughtReviewStaleError,
  type AdminThoughtRepository,
  type PendingThought,
  type ReportedThought,
  type ThoughtDecision,
  type ThoughtReportDecision,
  type ThoughtReportReason,
} from "./AdminThoughtRepository";

type JsonRecord = Record<string, unknown>;

const PENDING_KEYS = [
  "id",
  "content",
  "exhibition_id",
  "exhibition_name_ko",
  "exhibition_name_en",
  "author_display_name",
  "requested_at",
  "created_at",
  "was_approved_before",
] as const;

const REPORTED_KEYS = [
  "id",
  "content",
  "exhibition_id",
  "exhibition_name_ko",
  "exhibition_name_en",
  "author_display_name",
  "approved_at",
  "open_count",
  "first_reported_at",
  "reasons",
] as const;

/**
 * Calls the staff-guarded 감상 review functions (migration 20261112100100); the database checks the `publisher`
 * membership on every call. Items must carry exactly the contract's keys: anything else, a visit's feeling, tags,
 * date or private note above all, is refused rather than rendered (FR-026).
 */
export class SupabaseAdminThoughtRepository implements AdminThoughtRepository {
  constructor(private readonly client: SupabaseClient) {}

  async listPending(): Promise<PendingThought[]> {
    const rpcName = "list_thought_review_queue";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throw thoughtError(rpcName, error);
    return array(data, rpcName).map((item, index) => mapPending(item, rpcName, `$[${index}]`));
  }

  async decide(id: string, decision: ThoughtDecision, expectedRequestedAt: string): Promise<void> {
    const rpcName = "decide_thought";
    const { error } = await this.client.rpc(rpcName, {
      p_id: id,
      p_decision: decision.kind,
      p_reason: decision.kind === "reject" ? decision.reason : null,
      p_expected_requested_at: expectedRequestedAt,
    });
    if (error !== null) throw thoughtError(rpcName, error);
  }

  async listReported(): Promise<ReportedThought[]> {
    const rpcName = "list_reported_thoughts";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throw thoughtError(rpcName, error);
    return array(data, rpcName).map((item, index) => mapReported(item, rpcName, `$[${index}]`));
  }

  async resolveReports(id: string, resolution: ThoughtReportDecision, expectedApprovedAt: string): Promise<void> {
    const rpcName = "resolve_thought_reports";
    const { error } = await this.client.rpc(rpcName, {
      p_id: id,
      p_resolution: resolution.kind,
      p_reason: resolution.kind === "upheld" ? resolution.reason : null,
      p_expected_approved_at: expectedApprovedAt,
    });
    if (error !== null) throw thoughtError(rpcName, error);
  }
}

/** The database raises the contract's tokens as the error message, as the route functions do. */
function thoughtError(rpcName: string, error: { message?: string; code?: string }): Error {
  switch (error.message) {
    case "thought_not_staff":
      return new ThoughtModerationNotStaffError();
    case "thought_not_found":
      return new ThoughtNotFoundError();
    case "thought_review_stale":
    case "thought_reports_stale":
      return new ThoughtReviewStaleError();
    case "thought_review_invalid_transition":
    case "thought_no_open_reports":
      return new ThoughtInvalidTransitionError();
    default:
      return new Error(`${rpcName} failed${error.code ? ` (${error.code})` : ""}.`);
  }
}

function mapPending(value: unknown, rpcName: string, path: string): PendingThought {
  const row = exactRecord(value, PENDING_KEYS, rpcName, path);
  return {
    id: string(row, "id", rpcName, path),
    content: string(row, "content", rpcName, path),
    exhibitionId: string(row, "exhibition_id", rpcName, path),
    exhibitionNameKo: nullableString(row, "exhibition_name_ko", rpcName, path),
    exhibitionNameEn: nullableString(row, "exhibition_name_en", rpcName, path),
    authorDisplayName: displayName(row, rpcName, path),
    requestedAt: string(row, "requested_at", rpcName, path),
    createdAt: string(row, "created_at", rpcName, path),
    wasApprovedBefore: boolean(row, "was_approved_before", rpcName, path),
  };
}

function mapReported(value: unknown, rpcName: string, path: string): ReportedThought {
  const row = exactRecord(value, REPORTED_KEYS, rpcName, path);
  return {
    id: string(row, "id", rpcName, path),
    content: string(row, "content", rpcName, path),
    exhibitionId: string(row, "exhibition_id", rpcName, path),
    exhibitionNameKo: nullableString(row, "exhibition_name_ko", rpcName, path),
    exhibitionNameEn: nullableString(row, "exhibition_name_en", rpcName, path),
    authorDisplayName: displayName(row, rpcName, path),
    approvedAt: string(row, "approved_at", rpcName, path),
    openCount: number(row, "open_count", rpcName, path),
    firstReportedAt: string(row, "first_reported_at", rpcName, path),
    reasons: reasonCounts(row.reasons, rpcName, `${path}.reasons`),
  };
}

function reasonCounts(value: unknown, rpcName: string, path: string): Partial<Record<ThoughtReportReason, number>> {
  const tallies = record(value, rpcName, path);
  const reasons: Partial<Record<ThoughtReportReason, number>> = {};
  for (const [reason, total] of Object.entries(tallies)) {
    if (!THOUGHT_REPORT_REASONS.includes(reason as ThoughtReportReason) || typeof total !== "number") {
      throw new MalformedAdminThoughtPayloadError(rpcName, `${path}.${reason}`, "a known reason count");
    }
    reasons[reason as ThoughtReportReason] = total;
  }
  return reasons;
}

function array(value: unknown, rpcName: string): unknown[] {
  if (value === null) return [];
  if (!Array.isArray(value)) throw new MalformedAdminThoughtPayloadError(rpcName, "$", "an array");
  return value;
}

function record(value: unknown, rpcName: string, path: string): JsonRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new MalformedAdminThoughtPayloadError(rpcName, path, "an object");
  }
  return value as JsonRecord;
}

/** An object with every listed key and no other; a missing key is told apart from an unexpected one. */
function exactRecord(value: unknown, keys: readonly string[], rpcName: string, path: string): JsonRecord {
  const row = record(value, rpcName, path);
  for (const key of Object.keys(row)) {
    if (!keys.includes(key)) throw new MalformedAdminThoughtPayloadError(rpcName, `${path}.${key}`, "no such key");
  }
  for (const key of keys) {
    if (!(key in row)) throw new MalformedAdminThoughtPayloadError(rpcName, `${path}.${key}`, "a value");
  }
  return row;
}

function string(row: JsonRecord, key: string, rpcName: string, path: string): string {
  if (typeof row[key] !== "string") throw new MalformedAdminThoughtPayloadError(rpcName, `${path}.${key}`, "a string");
  return row[key] as string;
}

function nullableString(row: JsonRecord, key: string, rpcName: string, path: string): string | null {
  if (row[key] === null) return null;
  return string(row, key, rpcName, path);
}

function displayName(row: JsonRecord, rpcName: string, path: string): string | null {
  const name = nullableString(row, "author_display_name", rpcName, path);
  return name?.trim() ? name : null;
}

function number(row: JsonRecord, key: string, rpcName: string, path: string): number {
  if (typeof row[key] !== "number") throw new MalformedAdminThoughtPayloadError(rpcName, `${path}.${key}`, "a number");
  return row[key] as number;
}

function boolean(row: JsonRecord, key: string, rpcName: string, path: string): boolean {
  if (typeof row[key] !== "boolean") throw new MalformedAdminThoughtPayloadError(rpcName, `${path}.${key}`, "a boolean");
  return row[key] as boolean;
}
