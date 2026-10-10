/** A shared personal route as staff see it when reviewing a report (spec 089 US5, DR-D13). */
export interface ModeratedRouteStop {
  position: number;
  exhibitionId: string;
  nameKo: string;
  nameEn: string;
  venueNameKo: string;
  venueNameEn: string;
}

export type RouteListingState = "unlisted" | "requested" | "approved" | "declined" | "removed";
export type RouteDeclineReason = "name_or_description" | "promotional" | "composition" | "other";
export type RouteReportReason = "inappropriate" | "promotional" | "wrong_information" | "other";
export type RouteReportResolution = "dismissed" | "upheld";

export interface ModeratedRoute {
  id: string;
  name: string;
  authorDisplayName: string | null;
  isPublished: boolean;
  publishedAt: string | null;
  revokedAt: string | null;
  stops: ModeratedRouteStop[];
  /** Public-list fields (spec 089 US8); absent from servers older than the public routes migrations. */
  revision?: string;
  listingState?: RouteListingState;
  listingRequestedAt?: string | null;
  listingDecidedAt?: string | null;
  listingLastApprovedAt?: string | null;
  declineReason?: RouteDeclineReason | null;
  declineNote?: string | null;
  copyCount?: number;
  openReportCount?: number;
}

/** A route waiting for review, oldest first (DD11). */
export interface RouteListingQueueItem {
  id: string;
  name: string;
  authorDisplayName: string | null;
  requestedAt: string;
  stopCount: number;
  /** True when it was approved before and an edit sent it back ("수정됨"). */
  wasApprovedBefore: boolean;
  /** The version staff review; a decision on any other version is refused. */
  revision: string;
}

/** A listed route with open reports, most reported first. */
export interface ReportedRoute {
  id: string;
  name: string;
  listingState: RouteListingState;
  openCount: number;
  firstReportedAt: string;
  reasons: Partial<Record<RouteReportReason, number>>;
}

export type RouteListingDecision =
  | { kind: "approve" }
  | { kind: "decline"; reason: RouteDeclineReason; note: string | null };

/**
 * Staff review of public listing requests and reports (spec 089 US8, R11). Every call is checked against the staff
 * membership by the database.
 */
export interface AdminRouteReviewRepository {
  listQueue(): Promise<RouteListingQueueItem[]>;
  decide(routeId: string, decision: RouteListingDecision, expectedRevision: string): Promise<ModeratedRoute>;
  listReported(): Promise<ReportedRoute[]>;
  resolveReports(routeId: string, resolution: RouteReportResolution): Promise<ModeratedRoute>;
  unlist(routeId: string): Promise<ModeratedRoute>;
  restore(routeId: string): Promise<ModeratedRoute>;
}

/** The author changed the route after staff loaded it; reload and review again. */
export class RouteListingStaleError extends Error {
  constructor() {
    super("The route changed since it was loaded.");
    this.name = "RouteListingStaleError";
  }
}

/** The action does not apply to the route's current listing state. */
export class RouteListingInvalidTransitionError extends Error {
  constructor() {
    super("The route's listing state does not allow this action.");
    this.name = "RouteListingInvalidTransitionError";
  }
}

export type RouteModerationStatus = "public" | "private" | "revoked";

export function routeModerationStatus(route: ModeratedRoute): RouteModerationStatus {
  if (route.revokedAt !== null) return "revoked";
  return route.isPublished ? "public" : "private";
}

/** Staff-only look-up and revocation; revoking is irreversible and keeps the first revocation time. */
export interface AdminRouteRepository {
  lookUp(routeId: string): Promise<ModeratedRoute | null>;
  revoke(routeId: string): Promise<ModeratedRoute>;
}

export class RouteModerationNotStaffError extends Error {
  constructor() {
    super("Only active staff can moderate routes.");
    this.name = "RouteModerationNotStaffError";
  }
}

export class RouteModerationNotFoundError extends Error {
  constructor() {
    super("No route matches.");
    this.name = "RouteModerationNotFoundError";
  }
}

export class MalformedAdminRoutePayloadError extends Error {
  constructor(rpcName: string, path: string, expected: string) {
    super(`${rpcName} returned malformed data at ${path}: expected ${expected}.`);
    this.name = "MalformedAdminRoutePayloadError";
  }
}

const ROUTE_ID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

/** The route id in a gallrmap.com/route link or a bare id, lower-cased; null for anything else. */
export function parseRouteReference(input: string): string | null {
  const text = input.trim();
  if (text.length === 0) return null;
  const linked = /(?:^|\/\/|^www\.|\.)gallrmap\.com\/route\/([^/?#\s]+)/i.exec(text);
  const candidate = linked ? linked[1] : text;
  const match = ROUTE_ID.exec(candidate);
  return match && match[0].length === candidate.length ? match[0].toLowerCase() : null;
}
