"use strict";
/** Artist intake uses the same environment-specific public project as the catalogue. Off by default. */
function registrationConfig(environment = process.env) {
  if (
    !["true", "1"].includes(
      environment.GALLR_ENABLE_ARTIST_SUBMISSIONS?.trim().toLowerCase(),
    )
  )
    return { enabled: false };
  const key = environment.SUPABASE_PUBLISHABLE_KEY?.trim() || "";
  if (!/^sb_publishable_[A-Za-z0-9_-]+$/.test(key))
    throw new Error("Artist registration requires a publishable client key");
  let url;
  try {
    url = new URL(environment.SUPABASE_URL);
  } catch {
    throw new Error("Artist registration requires the matching Supabase URL");
  }
  if (
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    (url.pathname !== "/" && url.pathname !== "") ||
    (url.protocol !== "https:" &&
      !(
        url.protocol === "http:" &&
        ["localhost", "127.0.0.1"].includes(url.hostname)
      ))
  )
    throw new Error("Artist registration project URL is invalid");
  return { enabled: true, url: url.origin, key };
}
module.exports = { registrationConfig };
