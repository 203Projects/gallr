import type { SupabaseClient } from "@supabase/supabase-js";
import {
  MalformedAdminRoutePayloadError,
  RouteListingInvalidTransitionError,
  RouteListingStaleError,
  RouteModerationNotFoundError,
  RouteModerationNotStaffError,
  type AdminRouteRepository,
  type AdminRouteReviewRepository,
  type ModeratedRoute,
  type ModeratedRouteStop,
  type ReportedRoute,
  type RouteDeclineReason,
  type RouteListingDecision,
  type RouteListingQueueItem,
  type RouteListingState,
  type RouteReportReason,
  type RouteReportResolution,
} from "./AdminRouteRepository";

type JsonRecord = Record<string, unknown>;

/** Calls the staff-guarded route functions; the database checks staff membership on every call. */
export class SupabaseAdminRouteRepository implements AdminRouteRepository, AdminRouteReviewRepository {
  constructor(private readonly client: SupabaseClient) {}

  async listQueue(): Promise<RouteListingQueueItem[]> {
    const rpcName = "list_route_listing_queue";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throw routeError(rpcName, error);
    return array(data, rpcName).map((item, index) => mapQueueItem(item, rpcName, `$[${index}]`));
  }

  async decide(routeId: string, decision: RouteListingDecision, expectedRevision: string): Promise<ModeratedRoute> {
    const rpcName = "decide_route_listing";
    const declined = decision.kind === "decline" ? decision : null;
    const { data, error } = await this.client.rpc(rpcName, {
      p_id: routeId,
      p_decision: decision.kind,
      p_reason: declined?.reason ?? null,
      p_note: declined?.note ?? null,
      p_expected_revision: expectedRevision,
    });
    if (error !== null) throw routeError(rpcName, error);
    return mapRoute(data, rpcName);
  }

  async listReported(): Promise<ReportedRoute[]> {
    const rpcName = "list_reported_routes";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throw routeError(rpcName, error);
    return array(data, rpcName).map((item, index) => mapReported(item, rpcName, `$[${index}]`));
  }

  async resolveReports(routeId: string, resolution: RouteReportResolution): Promise<ModeratedRoute> {
    return this.callRoute("resolve_route_reports", { p_id: routeId, p_resolution: resolution });
  }

  async unlist(routeId: string): Promise<ModeratedRoute> {
    return this.callRoute("unlist_route", { p_id: routeId });
  }

  async restore(routeId: string): Promise<ModeratedRoute> {
    return this.callRoute("restore_route_listing", { p_id: routeId });
  }

  private async callRoute(rpcName: string, args: Record<string, unknown>): Promise<ModeratedRoute> {
    const { data, error } = await this.client.rpc(rpcName, args);
    if (error !== null) throw routeError(rpcName, error);
    return mapRoute(data, rpcName);
  }

  async lookUp(routeId: string): Promise<ModeratedRoute | null> {
    const rpcName = "get_route_for_moderation";
    const { data, error } = await this.client.rpc(rpcName, { p_id: routeId });
    if (error !== null) throw routeError(rpcName, error);
    if (data === null) return null;
    return mapRoute(data, rpcName);
  }

  async revoke(routeId: string): Promise<ModeratedRoute> {
    const rpcName = "revoke_personal_route";
    const { data, error } = await this.client.rpc(rpcName, { p_id: routeId });
    if (error !== null) throw routeError(rpcName, error);
    return mapRoute(data, rpcName);
  }
}

function routeError(rpcName: string, error: { message?: string; code?: string }): Error {
  if (error.message === "personal_route_not_staff") return new RouteModerationNotStaffError();
  if (error.message === "personal_route_not_found") return new RouteModerationNotFoundError();
  if (error.message === "route_listing_stale") return new RouteListingStaleError();
  if (error.message === "route_listing_invalid_transition") return new RouteListingInvalidTransitionError();
  return new Error(`${rpcName} failed${error.code ? ` (${error.code})` : ""}.`);
}

function mapRoute(value: unknown, rpcName: string): ModeratedRoute {
  const row = record(value, rpcName, "$");
  const stops = row.stops;
  if (!Array.isArray(stops)) throw new MalformedAdminRoutePayloadError(rpcName, "$.stops", "an array");
  return {
    id: string(row, "id", rpcName, "$"),
    name: string(row, "name", rpcName, "$"),
    authorDisplayName: nullableString(row, "author_display_name", rpcName, "$"),
    isPublished: boolean(row, "is_published", rpcName, "$"),
    publishedAt: nullableString(row, "published_at", rpcName, "$"),
    revokedAt: nullableString(row, "revoked_at", rpcName, "$"),
    stops: stops.map((stop, index) => mapStop(stop, rpcName, `$.stops[${index}]`)),
    ...listingFields(row, rpcName),
  };
}

const LISTING_STATES: readonly RouteListingState[] = ["unlisted", "requested", "approved", "declined", "removed"];
const DECLINE_REASONS: readonly RouteDeclineReason[] = ["name_or_description", "promotional", "composition", "other"];
const REPORT_REASONS: readonly RouteReportReason[] = ["inappropriate", "promotional", "wrong_information", "other"];

/** Listing fields are present only from the public routes migrations on; older payloads omit them. */
function listingFields(row: JsonRecord, rpcName: string): Partial<ModeratedRoute> {
  if (row.listing_state === undefined) return {};
  const fields: Partial<ModeratedRoute> = {
    listingState: oneOf(row, "listing_state", LISTING_STATES, rpcName, "$"),
    listingRequestedAt: nullableString(row, "listing_requested_at", rpcName, "$"),
    listingDecidedAt: nullableString(row, "listing_decided_at", rpcName, "$"),
    listingLastApprovedAt: nullableString(row, "listing_last_approved_at", rpcName, "$"),
    declineReason: row.listing_decline_reason == null ? null : oneOf(row, "listing_decline_reason", DECLINE_REASONS, rpcName, "$"),
    declineNote: nullableString(row, "listing_decline_note", rpcName, "$"),
  };
  if (typeof row.revision === "string") fields.revision = row.revision;
  if (row.copy_count !== undefined) fields.copyCount = number(row, "copy_count", rpcName, "$");
  if (row.open_report_count !== undefined) fields.openReportCount = number(row, "open_report_count", rpcName, "$");
  return fields;
}

function mapQueueItem(value: unknown, rpcName: string, path: string): RouteListingQueueItem {
  const row = record(value, rpcName, path);
  const author = nullableString(row, "author_display_name", rpcName, path);
  return {
    id: string(row, "id", rpcName, path),
    name: string(row, "name", rpcName, path),
    authorDisplayName: author?.trim() ? author : null,
    requestedAt: string(row, "requested_at", rpcName, path),
    stopCount: number(row, "stop_count", rpcName, path),
    wasApprovedBefore: boolean(row, "was_approved_before", rpcName, path),
    revision: string(row, "revision", rpcName, path),
  };
}

function mapReported(value: unknown, rpcName: string, path: string): ReportedRoute {
  const row = record(value, rpcName, path);
  const tallies = record(row.reasons ?? {}, rpcName, `${path}.reasons`);
  const reasons: Partial<Record<RouteReportReason, number>> = {};
  for (const [reason, total] of Object.entries(tallies)) {
    if (!REPORT_REASONS.includes(reason as RouteReportReason) || typeof total !== "number") {
      throw new MalformedAdminRoutePayloadError(rpcName, `${path}.reasons.${reason}`, "a known reason count");
    }
    reasons[reason as RouteReportReason] = total;
  }
  return {
    id: string(row, "id", rpcName, path),
    name: string(row, "name", rpcName, path),
    listingState: oneOf(row, "listing_state", LISTING_STATES, rpcName, path),
    openCount: number(row, "open_count", rpcName, path),
    firstReportedAt: string(row, "first_reported_at", rpcName, path),
    reasons,
  };
}

function array(value: unknown, rpcName: string): unknown[] {
  if (value === null) return [];
  if (!Array.isArray(value)) throw new MalformedAdminRoutePayloadError(rpcName, "$", "an array");
  return value;
}

function number(row: JsonRecord, key: string, rpcName: string, path: string): number {
  if (typeof row[key] !== "number") throw new MalformedAdminRoutePayloadError(rpcName, `${path}.${key}`, "a number");
  return row[key] as number;
}

function oneOf<T extends string>(row: JsonRecord, key: string, allowed: readonly T[], rpcName: string, path: string): T {
  const value = row[key];
  if (typeof value !== "string" || !allowed.includes(value as T)) {
    throw new MalformedAdminRoutePayloadError(rpcName, `${path}.${key}`, `one of ${allowed.join(", ")}`);
  }
  return value as T;
}

function mapStop(value: unknown, rpcName: string, path: string): ModeratedRouteStop {
  const row = record(value, rpcName, path);
  if (typeof row.position !== "number") {
    throw new MalformedAdminRoutePayloadError(rpcName, `${path}.position`, "a number");
  }
  return {
    position: row.position,
    exhibitionId: string(row, "exhibition_id", rpcName, path),
    nameKo: string(row, "name_ko", rpcName, path),
    nameEn: string(row, "name_en", rpcName, path),
    venueNameKo: string(row, "venue_name_ko", rpcName, path),
    venueNameEn: string(row, "venue_name_en", rpcName, path),
  };
}

function record(value: unknown, rpcName: string, path: string): JsonRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new MalformedAdminRoutePayloadError(rpcName, path, "an object");
  }
  return value as JsonRecord;
}

function string(row: JsonRecord, key: string, rpcName: string, path: string): string {
  if (typeof row[key] !== "string") throw new MalformedAdminRoutePayloadError(rpcName, `${path}.${key}`, "a string");
  return row[key] as string;
}

function nullableString(row: JsonRecord, key: string, rpcName: string, path: string): string | null {
  if (row[key] === null || row[key] === undefined) return null;
  return string(row, key, rpcName, path);
}

function boolean(row: JsonRecord, key: string, rpcName: string, path: string): boolean {
  if (typeof row[key] !== "boolean") throw new MalformedAdminRoutePayloadError(rpcName, `${path}.${key}`, "a boolean");
  return row[key] as boolean;
}
