import type { SupabaseClient } from "@supabase/supabase-js";
import {
  AppVersionPolicyNotStaffError,
  AppVersionPolicyRejectedError,
  MalformedAppVersionPolicyPayloadError,
  appVersionPolicyRejection,
  compareAppVersions,
  isAppVersion,
} from "./AppVersionPolicyRepository";
import { SupabaseAppVersionPolicyRepository } from "./SupabaseAppVersionPolicyRepository";

const androidRow = {
  platform: "android",
  minimum_version: "1.13.1",
  recommended_version: "1.14.0",
  latest_released_version: "1.14.0",
  updated_at: "2026-10-20T03:00:00Z",
  updated_by: "00000000-0000-4000-8000-00000000d801",
  updated_by_email: "staff@gallrmap.com",
};

function createClient(result: { data: unknown; error: unknown }) {
  const rpc = vi.fn().mockResolvedValue(result);
  return { repository: new SupabaseAppVersionPolicyRepository({ rpc } as unknown as SupabaseClient), rpc };
}

describe("app version rules", () => {
  it("accepts only three numbers", () => {
    expect(isAppVersion("1.13.1")).toBe(true);
    expect(isAppVersion("1.14")).toBe(false);
    expect(isAppVersion("1.14.0-debug")).toBe(false);
    expect(isAppVersion("")).toBe(false);
  });

  it("compares numerically", () => {
    expect(compareAppVersions("1.14.10", "1.14.9")).toBe(1);
    expect(compareAppVersions("1.13.1", "1.14.0")).toBe(-1);
    expect(compareAppVersions("2.0.0", "2.0.0")).toBe(0);
  });

  it("refuses what the server refuses", () => {
    const good = { minimumVersion: "1.14.0", recommendedVersion: "1.15.0", latestReleasedVersion: null };
    expect(appVersionPolicyRejection("android", good)).toBeNull();
    expect(appVersionPolicyRejection("web", good)).toBe("unknown_platform");
    expect(appVersionPolicyRejection("ios", { ...good, minimumVersion: "1.14" })).toBe("invalid_version");
    expect(appVersionPolicyRejection("ios", { ...good, latestReleasedVersion: "x" })).toBe("invalid_version");
    expect(appVersionPolicyRejection("ios", { ...good, recommendedVersion: "1.13.0" }))
      .toBe("recommended_below_minimum");
  });
});

describe("SupabaseAppVersionPolicyRepository", () => {
  it("lists both rows through the staff RPC", async () => {
    const { repository, rpc } = createClient({
      data: [androidRow, { ...androidRow, platform: "ios", latest_released_version: null, updated_by_email: null }],
      error: null,
    });

    const rows = await repository.list();

    expect(rpc).toHaveBeenCalledWith("admin_list_app_version_policy");
    expect(rows).toEqual([
      {
        platform: "android",
        minimumVersion: "1.13.1",
        recommendedVersion: "1.14.0",
        latestReleasedVersion: "1.14.0",
        updatedAt: "2026-10-20T03:00:00Z",
        updatedByEmail: "staff@gallrmap.com",
      },
      {
        platform: "ios",
        minimumVersion: "1.13.1",
        recommendedVersion: "1.14.0",
        latestReleasedVersion: null,
        updatedAt: "2026-10-20T03:00:00Z",
        updatedByEmail: null,
      },
    ]);
  });

  it("saves one row with the server's parameter names", async () => {
    const { repository, rpc } = createClient({ data: androidRow, error: null });

    const saved = await repository.save("android", {
      minimumVersion: "1.13.1",
      recommendedVersion: "1.14.0",
      latestReleasedVersion: "1.14.0",
    });

    expect(rpc).toHaveBeenCalledWith("admin_set_app_version_policy", {
      p_platform: "android",
      p_minimum: "1.13.1",
      p_recommended: "1.14.0",
      p_latest_released: "1.14.0",
    });
    expect(saved.updatedByEmail).toBe("staff@gallrmap.com");
  });

  it("maps authorization denial and the server's refusals", async () => {
    const denied = createClient({ data: null, error: { code: "42501", message: "active_staff_membership_required" } });
    await expect(denied.repository.list()).rejects.toBeInstanceOf(AppVersionPolicyNotStaffError);

    const refused = createClient({
      data: null,
      error: { code: "22023", message: "app_version_policy_recommended_below_minimum" },
    });
    await expect(
      refused.repository.save("android", { minimumVersion: "1.15.0", recommendedVersion: "1.14.0", latestReleasedVersion: null }),
    ).rejects.toMatchObject({ name: "AppVersionPolicyRejectedError", reason: "recommended_below_minimum" });
    expect(new AppVersionPolicyRejectedError("invalid_version").reason).toBe("invalid_version");
  });

  it("fails closed on a malformed payload", async () => {
    const notAList = createClient({ data: androidRow, error: null });
    await expect(notAList.repository.list()).rejects.toBeInstanceOf(MalformedAppVersionPolicyPayloadError);

    const badVersion = createClient({ data: [{ ...androidRow, minimum_version: "1.13" }], error: null });
    await expect(badVersion.repository.list()).rejects.toBeInstanceOf(MalformedAppVersionPolicyPayloadError);

    const badPlatform = createClient({ data: { ...androidRow, platform: "web" }, error: null });
    await expect(
      badPlatform.repository.save("android", { minimumVersion: "1.13.1", recommendedVersion: "1.13.1", latestReleasedVersion: null }),
    ).rejects.toBeInstanceOf(MalformedAppVersionPolicyPayloadError);
  });
});
