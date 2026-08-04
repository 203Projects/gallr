import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { resolveSupabaseSecretKey } from "../_shared/supabase_keys.ts";

export interface ImpactBackend {
  record(exhibitionId: string): Promise<boolean>;
}

function required(environment: Record<string, string>, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

class SupabaseImpactBackend implements ImpactBackend {
  constructor(private readonly client: SupabaseClient) {}

  async record(exhibitionId: string): Promise<boolean> {
    const { data, error } = await this.client.rpc(
      "record_exhibition_page_load",
      { p_exhibition_id: exhibitionId },
    );
    if (error || typeof data !== "boolean") {
      throw new Error("Impact could not be recorded.");
    }
    return data;
  }
}

export function createImpactBackend(
  environment: Record<string, string>,
): ImpactBackend {
  return new SupabaseImpactBackend(
    createClient(
      required(environment, "SUPABASE_URL"),
      resolveSupabaseSecretKey(environment, "record-exhibition-view"),
      { auth: { autoRefreshToken: false, persistSession: false } },
    ),
  );
}
