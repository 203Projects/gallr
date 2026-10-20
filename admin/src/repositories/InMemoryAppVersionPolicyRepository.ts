import { appVersionPolicyFixtures } from "../data/fixtures";
import {
  AppVersionPolicyRejectedError,
  appVersionPolicyRejection,
  type AppPlatform,
  type AppVersionPolicy,
  type AppVersionPolicyInput,
  type AppVersionPolicyRepository,
} from "./AppVersionPolicyRepository";

/** Fixture-mode and test double; refuses the same inputs as the server. */
export class InMemoryAppVersionPolicyRepository implements AppVersionPolicyRepository {
  private readonly rows: Map<AppPlatform, AppVersionPolicy>;

  constructor(
    rows: AppVersionPolicy[] = appVersionPolicyFixtures,
    private readonly staffEmail = "staff@example.com",
    private readonly now: () => string = () => new Date().toISOString(),
  ) {
    this.rows = new Map(rows.map((row) => [row.platform, { ...row }]));
  }

  async list(): Promise<AppVersionPolicy[]> {
    return [...this.rows.values()].sort((a, b) => a.platform.localeCompare(b.platform)).map((row) => ({ ...row }));
  }

  async save(platform: AppPlatform, input: AppVersionPolicyInput): Promise<AppVersionPolicy> {
    const rejection = appVersionPolicyRejection(platform, input);
    if (rejection) throw new AppVersionPolicyRejectedError(rejection);
    const saved: AppVersionPolicy = {
      platform,
      minimumVersion: input.minimumVersion,
      recommendedVersion: input.recommendedVersion,
      latestReleasedVersion: input.latestReleasedVersion,
      updatedAt: this.now(),
      updatedByEmail: this.staffEmail,
    };
    this.rows.set(platform, saved);
    return { ...saved };
  }
}
