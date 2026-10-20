import type { SupabaseClient } from "@supabase/supabase-js";
import {
  AppVersionPolicyNotStaffError,
  AppVersionPolicyRejectedError,
  MalformedAppVersionPolicyPayloadError,
  mapAppVersionPolicy,
  type AppPlatform,
  type AppVersionPolicy,
  type AppVersionPolicyInput,
  type AppVersionPolicyRejection,
  type AppVersionPolicyRepository,
} from "./AppVersionPolicyRepository";

const LIST_RPC = "admin_list_app_version_policy";
const SAVE_RPC = "admin_set_app_version_policy";

const rejections: Record<string, AppVersionPolicyRejection> = {
  app_version_policy_unknown_platform: "unknown_platform",
  app_version_policy_invalid_version: "invalid_version",
  app_version_policy_recommended_below_minimum: "recommended_below_minimum",
};

function policyError(rpcName: string, error: { code?: string; message?: string }): Error {
  if (error.code === "42501") return new AppVersionPolicyNotStaffError();
  if (error.code === "22023") {
    return new AppVersionPolicyRejectedError(rejections[error.message ?? ""] ?? "invalid_version");
  }
  return new Error(`${rpcName} failed.`);
}

export class SupabaseAppVersionPolicyRepository implements AppVersionPolicyRepository {
  constructor(private readonly client: SupabaseClient) {}

  async list(): Promise<AppVersionPolicy[]> {
    const { data, error } = await this.client.rpc(LIST_RPC);
    if (error !== null) throw policyError(LIST_RPC, error);
    if (!Array.isArray(data)) throw new MalformedAppVersionPolicyPayloadError(LIST_RPC);
    return data.map((row) => mapAppVersionPolicy(row, LIST_RPC));
  }

  async save(platform: AppPlatform, input: AppVersionPolicyInput): Promise<AppVersionPolicy> {
    const { data, error } = await this.client.rpc(SAVE_RPC, {
      p_platform: platform,
      p_minimum: input.minimumVersion,
      p_recommended: input.recommendedVersion,
      p_latest_released: input.latestReleasedVersion,
    });
    if (error !== null) throw policyError(SAVE_RPC, error);
    return mapAppVersionPolicy(data, SAVE_RPC);
  }
}
