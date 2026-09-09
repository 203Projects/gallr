import { createClient } from "@supabase/supabase-js";
import { resolveSupabaseSecretKey } from "../_shared/supabase_keys.ts";

export type ValidatedMobileAnalyticsEvent = Record<string, unknown>;

export interface MobileAnalyticsBackend {
  record(
    events: ValidatedMobileAnalyticsEvent[],
    sourceDigest: string,
  ): Promise<number>;
}

function required(environment: Record<string, string>, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

class SupabaseMobileAnalyticsBackend implements MobileAnalyticsBackend {
  private readonly client;

  constructor(environment: Record<string, string>) {
    this.client = createClient(
      required(environment, "SUPABASE_URL"),
      resolveSupabaseSecretKey(environment, "mobile-analytics"),
      { auth: { autoRefreshToken: false, persistSession: false } },
    );
  }

  async record(
    events: ValidatedMobileAnalyticsEvent[],
    sourceDigest: string,
  ): Promise<number> {
    const { data, error } = await this.client.rpc(
      "service_record_mobile_analytics",
      { p_events: events, p_source_digest: sourceDigest },
    );
    if (
      error || !data || typeof data !== "object" || Array.isArray(data) ||
      typeof (data as Record<string, unknown>).accepted !== "number"
    ) {
      throw new Error("Mobile analytics could not be recorded.");
    }
    const accepted = (data as Record<string, unknown>).accepted as number;
    if (
      !Number.isInteger(accepted) || accepted < 0 || accepted > events.length
    ) {
      throw new Error("Mobile analytics response was invalid.");
    }
    return accepted;
  }
}

export function createMobileAnalyticsBackend(
  environment: Record<string, string>,
): MobileAnalyticsBackend {
  return new SupabaseMobileAnalyticsBackend(environment);
}
