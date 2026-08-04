import { resolveSupabaseSecretKey } from "../_shared/supabase_keys.ts";

type Fetcher = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

interface MirrorPayload {
  p_snapshot: Record<string, unknown>;
  p_source_project_ref: string;
  p_reason: string;
}

export interface LegacyCatalogReceiverBackend {
  apply(payload: MirrorPayload): Promise<"applied" | "unchanged">;
}

const TARGET_URL = "https://yhuhjxswjbrtmbpbrciq.supabase.co";

class SupabaseLegacyCatalogReceiverBackend
  implements LegacyCatalogReceiverBackend {
  constructor(
    private readonly key: string,
    private readonly fetcher: Fetcher,
  ) {}

  async apply(payload: MirrorPayload): Promise<"applied" | "unchanged"> {
    const response = await this.fetcher(
      `${TARGET_URL}/rest/v1/rpc/service_replace_legacy_mobile_catalog`,
      {
        method: "POST",
        headers: {
          apikey: this.key,
          authorization: `Bearer ${this.key}`,
          "content-type": "application/json",
        },
        body: JSON.stringify(payload),
      },
    );
    if (!response.ok) {
      throw new Error(`Target RPC failed with HTTP ${response.status}.`);
    }
    let result: unknown;
    try {
      result = await response.json();
    } catch {
      throw new Error("Target RPC returned invalid JSON.");
    }
    const status =
      result && typeof result === "object" && !Array.isArray(result)
        ? (result as Record<string, unknown>).status
        : null;
    if (status !== "applied" && status !== "unchanged") {
      throw new Error("Target RPC returned an invalid receipt.");
    }
    return status;
  }
}

export function createLegacyCatalogReceiverBackend(
  environment: Record<string, string | undefined>,
  fetcher: Fetcher = fetch,
): LegacyCatalogReceiverBackend {
  const projectUrl = environment.SUPABASE_URL?.trim();
  if (projectUrl !== TARGET_URL) {
    throw new Error("Receiver project configuration is invalid.");
  }
  return new SupabaseLegacyCatalogReceiverBackend(
    resolveSupabaseSecretKey(environment, "legacy-catalog-mirror-receiver"),
    fetcher,
  );
}
