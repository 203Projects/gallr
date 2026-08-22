export function publicRsvpUrl(
  publicToken: string,
  publicSiteUrl = "https://gallrmap.com",
): string {
  const url = new URL("/rsvp/", publicSiteUrl);
  url.searchParams.set("token", publicToken);
  return url.toString();
}
