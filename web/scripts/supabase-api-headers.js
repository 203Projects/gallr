"use strict";

function legacyJwtRole(key) {
  const segments = key.split(".");
  if (segments.length !== 3 || !segments[0].startsWith("eyJ")) return null;

  try {
    const payload = JSON.parse(
      Buffer.from(segments[1], "base64url").toString("utf8"),
    );
    return typeof payload.role === "string" ? payload.role : null;
  } catch {
    return null;
  }
}

function supabaseApiHeaders(apiKey) {
  const key = String(apiKey || "").trim();
  if (!key) throw new Error("Supabase API key is required");
  if (key.startsWith("sb_secret_")) {
    throw new Error("Supabase secret API keys are not allowed in public clients");
  }

  const role = legacyJwtRole(key);
  if (role === "service_role") {
    throw new Error(
      "Supabase service role API keys are not allowed in public clients",
    );
  }

  const headers = { apikey: key };
  if (role === "anon") headers.Authorization = `Bearer ${key}`;
  return headers;
}

module.exports = {
  legacyJwtRole,
  supabaseApiHeaders,
};
