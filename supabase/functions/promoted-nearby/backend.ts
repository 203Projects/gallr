import { createClient } from "@supabase/supabase-js";
import { resolveSupabaseSecretKey } from "../_shared/supabase_keys.ts";

export interface PromotedPlacement {
  promotion_id: string;
  exhibition_id: string;
  name_ko: string;
  name_en: string;
  venue_name_ko: string;
  venue_name_en: string;
  city_ko: string;
  city_en: string;
  region_ko: string;
  region_en: string;
  opening_date: string;
  closing_date: string;
  cover_image_url: string | null;
  disclosure: "promoted_placement";
}

export interface PromotionBackend {
  select(
    viewerDigest: string,
    cityKo: string,
    regionKo: string,
  ): Promise<PromotedPlacement | null>;
}

function required(environment: Record<string, string>, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

class SupabasePromotionBackend implements PromotionBackend {
  private readonly client;
  constructor(environment: Record<string, string>) {
    this.client = createClient(
      required(environment, "SUPABASE_URL"),
      resolveSupabaseSecretKey(environment, "promoted-nearby"),
      { auth: { autoRefreshToken: false, persistSession: false } },
    );
  }

  async select(viewerDigest: string, cityKo: string, regionKo: string) {
    const { data, error } = await this.client.rpc(
      "service_select_local_promotion",
      {
        p_viewer_digest: viewerDigest,
        p_city_ko: cityKo,
        p_region_ko: regionKo,
      },
    );
    if (error) throw new Error("Promotion selection failed.");
    return data as PromotedPlacement | null;
  }
}

export function createPromotionBackend(
  environment: Record<string, string>,
): PromotionBackend {
  return new SupabasePromotionBackend(environment);
}
