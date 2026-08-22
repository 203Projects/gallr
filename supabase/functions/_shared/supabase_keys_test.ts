import {
  resolveSupabasePublishableKey,
  resolveSupabaseSecretKey,
} from "./supabase_keys.ts";

Deno.test("secret resolver prefers a component key from the hosted map", () => {
  const key = resolveSupabaseSecretKey({
    SUPABASE_SECRET_KEYS: JSON.stringify({
      default: "sb_secret_default",
      launch_rsvp: "sb_secret_rsvp",
    }),
    SUPABASE_SECRET_KEY: "sb_secret_local",
    SUPABASE_SERVICE_ROLE_KEY: "legacy-service-role",
  }, "launch-rsvp");

  if (key !== "sb_secret_rsvp") throw new Error("component key not selected");
});

Deno.test("secret resolver retains manually supplied slug compatibility", () => {
  const key = resolveSupabaseSecretKey({
    SUPABASE_SECRET_KEYS: '{"launch-rsvp":"slug-secret"}',
  }, "launch-rsvp");

  if (key !== "slug-secret") throw new Error("slug key not selected");
});

Deno.test("secret resolver accepts hosted default and legacy map names", () => {
  const hosted = resolveSupabaseSecretKey({
    SUPABASE_SECRET_KEYS: '{"default":" sb_secret_default "}',
  }, "promoted-nearby");
  if (hosted !== "sb_secret_default") {
    throw new Error("default key not selected");
  }

  const compatibility = resolveSupabaseSecretKey({
    SUPABASE_SECRET_KEYS: '{"service_role":"legacy-from-map"}',
  }, "promoted-nearby");
  if (compatibility !== "legacy-from-map") {
    throw new Error("compatibility map key not selected");
  }
});

Deno.test("secret resolver falls back to local and legacy single keys", () => {
  const local = resolveSupabaseSecretKey({
    SUPABASE_SECRET_KEYS: "not-json",
    SUPABASE_SECRET_KEY: " local-secret ",
    SUPABASE_SERVICE_ROLE_KEY: "legacy-service-role",
  }, "record-exhibition-view");
  if (local !== "local-secret") throw new Error("local key not selected");

  const legacy = resolveSupabaseSecretKey({
    SUPABASE_SERVICE_ROLE_KEY: " legacy-service-role ",
  }, "record-exhibition-view");
  if (legacy !== "legacy-service-role") {
    throw new Error("legacy key not selected");
  }
});

Deno.test("publishable resolver supports hosted, local, and legacy keys", () => {
  const hosted = resolveSupabasePublishableKey({
    SUPABASE_PUBLISHABLE_KEYS: JSON.stringify({
      default: "sb_publishable_default",
      geocode_address: "sb_publishable_geocode",
    }),
    SUPABASE_PUBLISHABLE_KEY: "sb_publishable_local",
  }, "geocode-address");
  if (hosted !== "sb_publishable_geocode") {
    throw new Error("component publishable key not selected");
  }

  const local = resolveSupabasePublishableKey({
    SUPABASE_PUBLISHABLE_KEY: " local-publishable ",
  }, "geocode-address");
  if (local !== "local-publishable") throw new Error("local key not selected");

  const legacy = resolveSupabasePublishableKey({
    SUPABASE_ANON_KEY: " legacy-anon ",
  }, "geocode-address");
  if (legacy !== "legacy-anon") throw new Error("legacy key not selected");
});

Deno.test("resolvers fail with generic errors when no usable key exists", () => {
  for (
    const resolve of [
      () =>
        resolveSupabaseSecretKey({ SUPABASE_SECRET_KEYS: "{}" }, "component"),
      () =>
        resolveSupabasePublishableKey({
          SUPABASE_PUBLISHABLE_KEYS: '{"default":" "}',
        }, "component"),
    ]
  ) {
    let message = "";
    try {
      resolve();
    } catch (error) {
      message = error instanceof Error ? error.message : String(error);
    }
    if (message !== "A Supabase API key is required.") {
      throw new Error(`unexpected resolver error: ${message}`);
    }
  }
});
