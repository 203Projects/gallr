import {
  RouteListingInvalidTransitionError,
  RouteAuthorChangedError,
  RouteListingStaleError,
  RouteModerationNotFoundError,
  type AdminRouteRepository,
  type AdminRouteReviewRepository,
  type ModeratedRoute,
  type ReportedRoute,
  type RouteListingDecision,
  type RouteListingQueueItem,
  type RouteReportReason,
  type RouteReportResolution,
} from "./AdminRouteRepository";

/** An open report held by the in-memory repository. */
export interface InMemoryRouteReport {
  routeId: string;
  approvedAt?: string;
  reason: RouteReportReason;
  createdAt?: string;
}

/** Fixture-mode and test stand-in for the staff route functions. */
export class InMemoryAdminRouteRepository implements AdminRouteRepository, AdminRouteReviewRepository {
  private readonly routes: Map<string, ModeratedRoute>;
  private reports: InMemoryRouteReport[];

  constructor(
    routes: ModeratedRoute[],
    private readonly now: () => string = () => new Date().toISOString(),
    reports: InMemoryRouteReport[] = [],
  ) {
    this.routes = new Map(routes.map((route) => [route.id, structuredClone(route)]));
    this.reports = structuredClone(reports).map((report) => ({ ...report, approvedAt: report.approvedAt ?? this.find(report.routeId).listingLastApprovedAt ?? "" }));
  }

  async lookUp(routeId: string): Promise<ModeratedRoute | null> {
    const route = this.routes.get(routeId);
    return route ? this.snapshot(route) : null;
  }

  /**
   * Test and fixture seam: stages an author edit during review; not a moderation action.
   *
   * Stands in for the author saving the route: the revision moves on, and a change to a requested or approved route
   * sends it (back) to review, as the database's save does (spec 089 US8).
   */
  edit(routeId: string, changes: Partial<Pick<ModeratedRoute, "name" | "stops">>): ModeratedRoute {
    const route = this.find(routeId);
    const revision = this.now();
    Object.assign(route, structuredClone(changes), { revision });
    if (route.listingState === "approved") route.listingRequestedAt = revision;
    if (route.listingState === "requested" || route.listingState === "approved") route.listingState = "requested";
    return this.snapshot(route);
  }

  async revoke(routeId: string): Promise<ModeratedRoute> {
    const route = this.find(routeId);
    if (route.revokedAt === null) route.revokedAt = this.now();
    if (route.listingState !== "removed") route.listingState = "unlisted";
    return this.snapshot(route);
  }

  async listQueue(): Promise<RouteListingQueueItem[]> {
    return [...this.routes.values()]
      .filter((route) => route.listingState === "requested")
      .sort((a, b) => (a.listingRequestedAt ?? "").localeCompare(b.listingRequestedAt ?? ""))
      .map((route) => ({
        id: route.id,
        name: route.name,
        authorDisplayName: route.authorDisplayName,
        requestedAt: route.listingRequestedAt ?? "",
        stopCount: route.stops.length,
        wasApprovedBefore: route.listingLastApprovedAt !== null,
        revision: route.revision,
      }));
  }

  /** Test and fixture seam: the author changes their profile name; the route's own revision does not move. */
  renameAuthor(routeId: string, displayName: string | null): void {
    this.find(routeId).authorDisplayName = displayName;
  }

  async decide(
    routeId: string,
    decision: RouteListingDecision,
    expectedRevision: string,
    expectedAuthorName: string | null,
  ): Promise<ModeratedRoute> {
    const route = this.find(routeId);
    if (route.listingState !== "requested") throw new RouteListingInvalidTransitionError();
    if (route.revision !== expectedRevision) throw new RouteListingStaleError();
    if (decision.kind === "approve" && (expectedAuthorName ?? "") !== (route.authorDisplayName ?? "")) {
      throw new RouteAuthorChangedError();
    }
    if (decision.kind === "approve") {
      route.listingState = "approved";
      route.listingLastApprovedAt = this.now();
      route.declineReason = null;
    } else {
      route.listingState = "declined";
      route.declineReason = decision.reason;
    }
    return this.snapshot(route);
  }

  async listReported(): Promise<ReportedRoute[]> {
    const byRoute = new Map<string, InMemoryRouteReport[]>();
    for (const report of this.reports) {
      const key = `${report.routeId}:${report.approvedAt}`;
      byRoute.set(key, [...(byRoute.get(key) ?? []), report]);
    }
    return [...byRoute.entries()]
      .map(([, reports]) => {
        const routeId = reports[0].routeId;
        const route = this.find(routeId);
        const reasons: Partial<Record<RouteReportReason, number>> = {};
        for (const report of reports) reasons[report.reason] = (reasons[report.reason] ?? 0) + 1;
        return {
          id: routeId,
          name: route.name,
          listingState: route.listingState,
          openCount: reports.length,
          approvedAt: reports[0].approvedAt ?? "",
          revision: route.revision,
          firstReportedAt: reports.map((report) => report.createdAt ?? "").sort()[0] ?? "",
          reasons,
        };
      })
      .sort((a, b) => b.openCount - a.openCount);
  }

  async resolveReports(routeId: string, resolution: RouteReportResolution, expectedRevision: string, approvedAt: string): Promise<ModeratedRoute> {
    const route = this.find(routeId);
    if (route.revision !== expectedRevision || !approvedAt ||
      (resolution === "upheld" && (route.listingLastApprovedAt !== approvedAt || !["approved", "unlisted", "removed"].includes(route.listingState)))) throw new RouteListingStaleError();
    const remaining = this.reports.filter((report) => report.routeId !== routeId || report.approvedAt !== approvedAt);
    const resolved = this.reports.length - remaining.length;
    this.reports = remaining;
    // An upheld report removes the route from any state but removed, withdrawn (unlisted) included.
    if (resolved > 0 && resolution === "upheld" && route.listingState !== "removed") route.listingState = "removed";
    return this.snapshot(route);
  }

  async unlist(routeId: string): Promise<ModeratedRoute> {
    const route = this.find(routeId);
    // A route that was never listed has nothing to take off, so unlist_route refuses it. Any other state but removed
    // goes to removed, a route withdrawn after an approval included; already removed is a no-op.
    if (route.listingState === "unlisted" && route.listingLastApprovedAt === null) throw new RouteListingInvalidTransitionError();
    if (route.listingState !== "removed") route.listingState = "removed";
    return this.snapshot(route);
  }

  async restore(routeId: string): Promise<ModeratedRoute> {
    const route = this.find(routeId);
    if (route.listingState !== "removed") throw new RouteListingInvalidTransitionError();
    route.listingState = "unlisted";
    return this.snapshot(route);
  }

  private find(routeId: string): ModeratedRoute {
    const route = this.routes.get(routeId);
    if (!route) throw new RouteModerationNotFoundError();
    return route;
  }

  private snapshot(route: ModeratedRoute): ModeratedRoute {
    const openReportCount = this.reports.filter((report) => report.routeId === route.id).length;
    return { ...structuredClone(route), openReportCount };
  }
}
