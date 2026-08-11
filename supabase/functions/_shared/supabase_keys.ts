export type SupabaseKeyEnvironment = Record<string, string | undefined>;

function trimmed(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function namedKey(
  encoded: string | undefined,
  component: string,
  compatibilityName: string,
): string | null {
  if (!encoded?.trim()) return null;
  try {
    const parsed: unknown = JSON.parse(encoded);
    const direct = trimmed(parsed);
    if (direct) return direct;
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return null;
    }
    const keys = parsed as Record<string, unknown>;
    // Supabase API-key names cannot contain hyphens, while Edge Function slugs
    // conventionally do. Prefer the valid snake_case form and retain the raw
    // component name for compatibility with manually supplied local maps.
    const componentKeyName = component.replaceAll("-", "_");
    for (
      const name of [
        componentKeyName,
        component,
        "default",
        compatibilityName,
      ]
    ) {
      const value = trimmed(keys[name]);
      if (value) return value;
    }
  } catch {
    // Fall through to local/legacy single-key variables without exposing input.
  }
  return null;
}

function required(value: string | null): string {
  if (value) return value;
  throw new Error("A Supabase API key is required.");
}

export function resolveSupabaseSecretKey(
  environment: SupabaseKeyEnvironment,
  component: string,
): string {
  return required(
    namedKey(environment.SUPABASE_SECRET_KEYS, component, "service_role") ||
      trimmed(environment.SUPABASE_SECRET_KEY) ||
      trimmed(environment.SUPABASE_SERVICE_ROLE_KEY),
  );
}

export function resolveSupabasePublishableKey(
  environment: SupabaseKeyEnvironment,
  component: string,
): string {
  return required(
    namedKey(environment.SUPABASE_PUBLISHABLE_KEYS, component, "anon") ||
      trimmed(environment.SUPABASE_PUBLISHABLE_KEY) ||
      trimmed(environment.SUPABASE_ANON_KEY),
  );
}
