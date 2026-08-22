import { publicRsvpUrl } from "./publicRsvpUrl";

describe("public RSVP URL", () => {
  const token = "00000000-0000-4000-8000-000000000001";

  it("targets the production public RSVP route by default", () => {
    expect(publicRsvpUrl(token)).toBe(
      `https://gallrmap.com/rsvp/?token=${token}`,
    );
  });

  it("uses the environment-matched public origin and discards a base path", () => {
    expect(publicRsvpUrl(
      token,
      "https://public-preview.example.test/staging/base/",
    )).toBe(
      `https://public-preview.example.test/rsvp/?token=${token}`,
    );
  });
});
