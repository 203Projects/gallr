import { RouteModerationNotFoundError, routeModerationStatus } from "./AdminRouteRepository";
import { InMemoryAdminRouteRepository } from "./InMemoryAdminRouteRepository";

const ROUTE_ID = "6f1c2a7e-8d34-4b8e-9a51-2f0c7d1e9b10";

describe("InMemoryAdminRouteRepository", () => {
  it("looks up, revokes once and keeps the first revocation time", async () => {
    const repository = new InMemoryAdminRouteRepository(
      [{ id: ROUTE_ID, name: "동선", authorDisplayName: null, isPublished: true, publishedAt: "2026-10-07T12:00:00Z", revokedAt: null, stops: [] }],
      () => "2026-10-08T02:00:00Z",
    );

    const before = await repository.lookUp(ROUTE_ID);
    expect(before && routeModerationStatus(before)).toBe("public");

    const revoked = await repository.revoke(ROUTE_ID);
    expect(routeModerationStatus(revoked)).toBe("revoked");
    expect(revoked.revokedAt).toBe("2026-10-08T02:00:00Z");
    await expect(repository.lookUp(ROUTE_ID)).resolves.toEqual(revoked);
  });

  it("reports unknown routes", async () => {
    const repository = new InMemoryAdminRouteRepository([]);

    await expect(repository.lookUp(ROUTE_ID)).resolves.toBeNull();
    await expect(repository.revoke(ROUTE_ID)).rejects.toBeInstanceOf(RouteModerationNotFoundError);
  });

  it("names the private state", () => {
    expect(
      routeModerationStatus({ id: ROUTE_ID, name: "", authorDisplayName: null, isPublished: false, publishedAt: null, revokedAt: null, stops: [] }),
    ).toBe("private");
  });
});
