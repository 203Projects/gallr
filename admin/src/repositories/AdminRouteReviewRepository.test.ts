import type { SupabaseClient } from "@supabase/supabase-js";
import {
  MalformedAdminRoutePayloadError,
  RouteListingInvalidTransitionError,
  RouteListingStaleError,
  RouteModerationNotStaffError,
  type ModeratedRoute,
} from "./AdminRouteRepository";
import { InMemoryAdminRouteRepository } from "./InMemoryAdminRouteRepository";
import { SupabaseAdminRouteRepository } from "./SupabaseAdminRouteRepository";

const ROUTE_ID = "6f1c2a7e-8d34-4b8e-9a51-2f0c7d1e9b10";
const REVISION = "2026-10-08T01:00:00.123456+00:00";

const wireRoute = {
  id: ROUTE_ID,
  revision: REVISION,
  name: "토요일 한남 산책",
  is_published: true,
  published_at: "2026-10-07T12:00:00Z",
  revoked_at: null,
  author_display_name: "hanshin",
  listing_state: "approved",
  listing_requested_at: "2026-10-08T00:00:00Z",
  listing_decided_at: "2026-10-08T02:00:00Z",
  listing_last_approved_at: "2026-10-08T02:00:00Z",
  listing_decline_reason: null,
  listing_decline_note: null,
  copy_count: 4,
  open_report_count: 2,
  stops: [],
};

function clientReturning(result: { data: unknown; error: unknown }) {
  const rpc = vi.fn().mockResolvedValue(result);
  return { client: { rpc } as unknown as SupabaseClient, rpc };
}

describe("SupabaseAdminRouteRepository review", () => {
  it("reads the waiting routes oldest first as the server sends them", async () => {
    const { client, rpc } = clientReturning({
      data: [
        {
          id: ROUTE_ID,
          name: "먼저 요청",
          author_display_name: "",
          requested_at: "2026-10-08T00:00:00Z",
          stop_count: 3,
          was_approved_before: true,
          revision: REVISION,
        },
      ],
      error: null,
    });

    await expect(new SupabaseAdminRouteRepository(client).listQueue()).resolves.toEqual([
      {
        id: ROUTE_ID,
        name: "먼저 요청",
        authorDisplayName: null,
        requestedAt: "2026-10-08T00:00:00Z",
        stopCount: 3,
        wasApprovedBefore: true,
        revision: REVISION,
      },
    ]);
    expect(rpc).toHaveBeenCalledWith("list_route_listing_queue");
  });

  it("sends a decision with the revision staff reviewed", async () => {
    const { client, rpc } = clientReturning({ data: { ...wireRoute, listing_state: "declined", listing_decline_reason: "composition", listing_decline_note: "줄여 주세요" }, error: null });

    const route = await new SupabaseAdminRouteRepository(client).decide(
      ROUTE_ID,
      { kind: "decline", reason: "composition", note: "줄여 주세요" },
      REVISION,
    );

    expect(rpc).toHaveBeenCalledWith("decide_route_listing", {
      p_id: ROUTE_ID,
      p_decision: "decline",
      p_reason: "composition",
      p_note: "줄여 주세요",
      p_expected_revision: REVISION,
    });
    expect(route.listingState).toBe("declined");
    expect(route.declineReason).toBe("composition");
    expect(route.declineNote).toBe("줄여 주세요");
  });

  it("approves without a reason", async () => {
    const { client, rpc } = clientReturning({ data: wireRoute, error: null });

    const route = await new SupabaseAdminRouteRepository(client).decide(ROUTE_ID, { kind: "approve" }, REVISION);

    expect(rpc).toHaveBeenCalledWith("decide_route_listing", {
      p_id: ROUTE_ID,
      p_decision: "approve",
      p_reason: null,
      p_note: null,
      p_expected_revision: REVISION,
    });
    expect(route.listingState).toBe("approved");
    expect(route.copyCount).toBe(4);
    expect(route.openReportCount).toBe(2);
    expect(route.revision).toBe(REVISION);
  });

  it("maps a stale decision and an invalid transition", async () => {
    const stale = clientReturning({ data: null, error: { code: "55000", message: "route_listing_stale" } });
    await expect(
      new SupabaseAdminRouteRepository(stale.client).decide(ROUTE_ID, { kind: "approve" }, REVISION),
    ).rejects.toBeInstanceOf(RouteListingStaleError);

    const invalid = clientReturning({ data: null, error: { code: "55000", message: "route_listing_invalid_transition" } });
    await expect(new SupabaseAdminRouteRepository(invalid.client).unlist(ROUTE_ID)).rejects.toBeInstanceOf(
      RouteListingInvalidTransitionError,
    );

    const notStaff = clientReturning({ data: null, error: { code: "42501", message: "personal_route_not_staff" } });
    await expect(new SupabaseAdminRouteRepository(notStaff.client).listReported()).rejects.toBeInstanceOf(
      RouteModerationNotStaffError,
    );
  });

  it("reads reported routes with their reason tallies", async () => {
    const { client, rpc } = clientReturning({
      data: [
        {
          id: ROUTE_ID,
          name: "신고된 동선",
          listing_state: "approved",
          open_count: 3,
          first_reported_at: "2026-10-08T00:00:00Z",
          reasons: { promotional: 2, other: 1 },
        },
      ],
      error: null,
    });

    await expect(new SupabaseAdminRouteRepository(client).listReported()).resolves.toEqual([
      {
        id: ROUTE_ID,
        name: "신고된 동선",
        listingState: "approved",
        openCount: 3,
        firstReportedAt: "2026-10-08T00:00:00Z",
        reasons: { promotional: 2, other: 1 },
      },
    ]);
    expect(rpc).toHaveBeenCalledWith("list_reported_routes");
  });

  it("resolves reports, unlists and restores through their functions", async () => {
    const { client, rpc } = clientReturning({ data: { ...wireRoute, listing_state: "removed" }, error: null });
    const repository = new SupabaseAdminRouteRepository(client);

    await repository.resolveReports(ROUTE_ID, "upheld");
    await repository.unlist(ROUTE_ID);
    await repository.restore(ROUTE_ID);

    expect(rpc).toHaveBeenNthCalledWith(1, "resolve_route_reports", { p_id: ROUTE_ID, p_resolution: "upheld" });
    expect(rpc).toHaveBeenNthCalledWith(2, "unlist_route", { p_id: ROUTE_ID });
    expect(rpc).toHaveBeenNthCalledWith(3, "restore_route_listing", { p_id: ROUTE_ID });
  });

  it("rejects malformed review payloads", async () => {
    const { client } = clientReturning({ data: [{ id: ROUTE_ID, name: 3 }], error: null });

    await expect(new SupabaseAdminRouteRepository(client).listQueue()).rejects.toBeInstanceOf(
      MalformedAdminRoutePayloadError,
    );
  });
});

describe("InMemoryAdminRouteRepository review", () => {
  const route: ModeratedRoute = {
    id: ROUTE_ID,
    name: "먼저 요청",
    authorDisplayName: "hanshin",
    isPublished: true,
    publishedAt: "2026-10-07T12:00:00Z",
    revokedAt: null,
    stops: [],
    listingState: "requested",
    listingRequestedAt: "2026-10-08T00:00:00Z",
    revision: REVISION,
  };

  it("approves only the reviewed revision and leaves the queue", async () => {
    const repository = new InMemoryAdminRouteRepository([route]);

    await expect(repository.decide(ROUTE_ID, { kind: "approve" }, "older")).rejects.toBeInstanceOf(RouteListingStaleError);
    expect(await repository.listQueue()).toHaveLength(1);

    const approved = await repository.decide(ROUTE_ID, { kind: "approve" }, REVISION);

    expect(approved.listingState).toBe("approved");
    expect(await repository.listQueue()).toEqual([]);
  });

  it("upholding reports removes the route from the list and the reports view", async () => {
    const repository = new InMemoryAdminRouteRepository([{ ...route, listingState: "approved" }], undefined, [
      { routeId: ROUTE_ID, reason: "promotional" },
      { routeId: ROUTE_ID, reason: "other" },
    ]);
    expect((await repository.listReported())[0]?.openCount).toBe(2);

    const removed = await repository.resolveReports(ROUTE_ID, "upheld");

    expect(removed.listingState).toBe("removed");
    expect(await repository.listReported()).toEqual([]);
    expect((await repository.restore(ROUTE_ID)).listingState).toBe("unlisted");
  });
});
