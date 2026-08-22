import { createClient } from "@supabase/supabase-js";
import { resolveSupabaseSecretKey } from "../_shared/supabase_keys.ts";

export interface PublicLaunchKit {
  exhibition_id: string;
  name_ko: string;
  name_en: string;
  venue_name_ko: string;
  venue_name_en: string;
  address_ko: string;
  address_en: string;
  cover_image_url: string;
  description_ko: string;
  description_en: string;
  opening_date: string;
  closing_date: string;
  hours: string;
  contact: string;
  reception_date: string;
  reception_start_time: string;
}

export interface PublicRsvpInput {
  token: string;
  name: string;
  email: string;
  partySize: number;
  privacyAcknowledged: boolean;
  sourceDigest: string;
}

export interface LaunchRsvpBackend {
  get(token: string): Promise<PublicLaunchKit | null>;
  submit(input: PublicRsvpInput): Promise<boolean>;
}

function required(environment: Record<string, string>, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

class SupabaseRsvpBackend implements LaunchRsvpBackend {
  private readonly client;
  constructor(environment: Record<string, string>) {
    this.client = createClient(
      required(environment, "SUPABASE_URL"),
      resolveSupabaseSecretKey(environment, "launch-rsvp"),
      { auth: { autoRefreshToken: false, persistSession: false } },
    );
  }
  async get(token: string): Promise<PublicLaunchKit | null> {
    const { data, error } = await this.client.rpc("service_public_launch_kit", {
      p_public_token: token,
    });
    if (error) throw new Error("RSVP page could not be loaded.");
    return data as PublicLaunchKit | null;
  }
  async submit(input: PublicRsvpInput): Promise<boolean> {
    const { data, error } = await this.client.rpc(
      "service_submit_launch_rsvp",
      {
        p_public_token: input.token,
        p_name: input.name,
        p_email: input.email,
        p_party_size: input.partySize,
        p_privacy_acknowledged: input.privacyAcknowledged,
        p_source_digest: input.sourceDigest,
      },
    );
    if (error) throw new Error(error.message || "RSVP could not be saved.");
    return data === true;
  }
}

export function createLaunchRsvpBackend(
  environment: Record<string, string>,
): LaunchRsvpBackend {
  return new SupabaseRsvpBackend(environment);
}
