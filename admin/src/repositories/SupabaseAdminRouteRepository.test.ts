import type { SupabaseClient } from "@supabase/supabase-js";
import {
  MalformedAdminRoutePayloadError,
  RouteModerationNotFoundError,
  RouteModerationNotStaffError,
  parseRouteReference,
} from "./AdminRouteRepository";
import { SupabaseAdminRouteRepository } from "./SupabaseAdminRouteRepository";

const ROUTE_ID = "6f1c2a7e-8d34-4b8e-9a51-2f0c7d1e9b10";

const wireRoute = {
  id: ROUTE_ID,
  revision: "2026-10-08T01:00:00Z",
  name: "토요일 한남 산책",
  is_published: true,
  published_at: "2026-10-07T12:00:00Z",
  revoked_at: null,
  author_display_name: "hanshin",
  listing_state: "unlisted",
  listing_requested_at: null,
  listing_decided_at: null,
  listing_last_approved_at: null,
  listing_decline_reason: null,
  listing_decline_note: null,
  copy_count: 0,
  open_report_count: 0,
  stops: [
    {
      position: 0,
      exhibition_id: "e-1",
      name_ko: "빛의 정원",
      name_en: "Garden of Light",
      venue_name_ko: "갤러리 빛",
      venue_name_en: "Gallery Light",
      latitude: 37.53,
      longitude: 127.0,
      region_ko: "용산구",
      region_en: "Yongsan-gu",
      city_ko: "서울",
    },
  ],
};

function createClient(result: { data: unknown; error: unknown }) {
  const rpc = vi.fn().mockResolvedValue(result);
  return { client: { rpc } as unknown as SupabaseClient, rpc };
}

describe("parseRouteReference", () => {
  it("accepts a share link or a bare id", () => {
    expect(parseRouteReference(`https://gallrmap.com/route/${ROUTE_ID}?s=share&v=1791432000`)).toBe(ROUTE_ID);
    expect(parseRouteReference(`gallrmap.com/route/${ROUTE_ID.toUpperCase()}`)).toBe(ROUTE_ID);
    expect(parseRouteReference(`  ${ROUTE_ID}  `)).toBe(ROUTE_ID);
  });

  it("rejects anything else", () => {
    expect(parseRouteReference("")).toBeNull();
    expect(parseRouteReference("https://gallrmap.com/exhibitions/quiet-lines/")).toBeNull();
    expect(parseRouteReference("not-a-route")).toBeNull();
  });
});

describe("SupabaseAdminRouteRepository", () => {
  it("looks a route up through the staff-only function", async () => {
    const { client, rpc } = createClient({ data: wireRoute, error: null });

    await expect(new SupabaseAdminRouteRepository(client).lookUp(ROUTE_ID)).resolves.toEqual({
      id: ROUTE_ID,
      name: "토요일 한남 산책",
      authorDisplayName: "hanshin",
      isPublished: true,
      publishedAt: "2026-10-07T12:00:00Z",
      revokedAt: null,
      stops: [{ position: 0, exhibitionId: "e-1", nameKo: "빛의 정원", nameEn: "Garden of Light", venueNameKo: "갤러리 빛", venueNameEn: "Gallery Light" }],
      revision: "2026-10-08T01:00:00Z",
      listingState: "unlisted",
      listingRequestedAt: null,
      listingLastApprovedAt: null,
      declineReason: null,
      copyCount: 0,
      openReportCount: 0,
    });
    expect(rpc).toHaveBeenCalledWith("get_route_for_moderation", { p_id: ROUTE_ID });
  });

  it("rejects a route payload without its listing fields or revision", async () => {
    for (const key of ["listing_state", "copy_count", "open_report_count", "revision"] as const) {
      const { [key]: _absent, ...withoutField } = wireRoute;
      const { client } = createClient({ data: withoutField, error: null });
      const lookUp = new SupabaseAdminRouteRepository(client).lookUp(ROUTE_ID);

      await expect(lookUp).rejects.toBeInstanceOf(MalformedAdminRoutePayloadError);
      await expect(lookUp).rejects.toThrow(`get_route_for_moderation returned malformed data at $.${key}`);
    }
  });

  it("returns null for an unknown route", async () => {
    const { client } = createClient({ data: null, error: null });

    await expect(new SupabaseAdminRouteRepository(client).lookUp(ROUTE_ID)).resolves.toBeNull();
  });

  it("revokes and returns the updated route", async () => {
    const revoked = { ...wireRoute, revoked_at: "2026-10-08T02:00:00Z" };
    const { client, rpc } = createClient({ data: revoked, error: null });

    const route = await new SupabaseAdminRouteRepository(client).revoke(ROUTE_ID);

    expect(route.revokedAt).toBe("2026-10-08T02:00:00Z");
    expect(rpc).toHaveBeenCalledWith("revoke_personal_route", { p_id: ROUTE_ID });
  });

  it("maps the database's refusals", async () => {
    const notStaff = createClient({ data: null, error: { code: "42501", message: "personal_route_not_staff" } });
    await expect(new SupabaseAdminRouteRepository(notStaff.client).lookUp(ROUTE_ID)).rejects.toBeInstanceOf(
      RouteModerationNotStaffError,
    );

    const missing = createClient({ data: null, error: { code: "P0002", message: "personal_route_not_found" } });
    await expect(new SupabaseAdminRouteRepository(missing.client).revoke(ROUTE_ID)).rejects.toBeInstanceOf(
      RouteModerationNotFoundError,
    );
  });

  it("rejects malformed responses at the boundary", async () => {
    const { client } = createClient({ data: { ...wireRoute, name: 3 }, error: null });

    await expect(new SupabaseAdminRouteRepository(client).lookUp(ROUTE_ID)).rejects.toBeInstanceOf(
      MalformedAdminRoutePayloadError,
    );
  });
});
