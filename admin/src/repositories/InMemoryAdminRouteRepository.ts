import {
  RouteListingInvalidTransitionError,
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
    this.reports = structuredClone(reports);
  }

  async lookUp(routeId: string): Promise<ModeratedRoute | null> {
    const route = this.routes.get(routeId);
    return route ? this.snapshot(route) : null;
  }

  /**
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

  async decide(routeId: string, decision: RouteListingDecision, expectedRevision: string): Promise<ModeratedRoute> {
    const route = this.find(routeId);
    if (route.listingState !== "requested") throw new RouteListingInvalidTransitionError();
    if (route.revision !== expectedRevision) throw new RouteListingStaleError();
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
    for (const report of this.reports) byRoute.set(report.routeId, [...(byRoute.get(report.routeId) ?? []), report]);
    return [...byRoute.entries()]
      .map(([routeId, reports]) => {
        const route = this.find(routeId);
        const reasons: Partial<Record<RouteReportReason, number>> = {};
        for (const report of reports) reasons[report.reason] = (reasons[report.reason] ?? 0) + 1;
        return {
          id: routeId,
          name: route.name,
          listingState: route.listingState,
          openCount: reports.length,
          firstReportedAt: reports.map((report) => report.createdAt ?? "").sort()[0] ?? "",
          reasons,
        };
      })
      .sort((a, b) => b.openCount - a.openCount);
  }

  async resolveReports(routeId: string, resolution: RouteReportResolution): Promise<ModeratedRoute> {
    const route = this.find(routeId);
    this.reports = this.reports.filter((report) => report.routeId !== routeId);
    if (resolution === "upheld" && ["requested", "approved", "declined"].includes(route.listingState)) {
      route.listingState = "removed";
    }
    return this.snapshot(route);
  }

  async unlist(routeId: string): Promise<ModeratedRoute> {
    const route = this.find(routeId);
    if (route.listingState === "unlisted") throw new RouteListingInvalidTransitionError();
    route.listingState = "removed";
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
