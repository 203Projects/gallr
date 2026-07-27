import vercelConfig from "../vercel.json";
import deploymentGuide from "../README.md?raw";

describe("admin deployment contract", () => {
  it("builds the standalone Vite app with baseline browser protections", () => {
    expect(vercelConfig).toMatchObject({
      $schema: "https://openapi.vercel.sh/vercel.json",
      framework: "vite",
      installCommand: "npm ci",
      buildCommand: "npm run build",
      outputDirectory: "dist",
    });

    const headers = vercelConfig.headers.find(
      (entry) => entry.source === "/(.*)",
    )?.headers;

    expect(headers).toEqual(
      expect.arrayContaining([
        { key: "X-Content-Type-Options", value: "nosniff" },
        { key: "X-Frame-Options", value: "DENY" },
        { key: "Referrer-Policy", value: "no-referrer" },
        {
          key: "Permissions-Policy",
          value: "camera=(), microphone=(), geolocation=()",
        },
      ]),
    );
  });

  it("documents the separate project, domain, environment, and auth redirect", () => {
    expect(deploymentGuide).toContain("Root Directory: admin");
    expect(deploymentGuide).toContain("https://admin.gallrmap.com");
    expect(deploymentGuide).toContain("VITE_SUPABASE_URL");
    expect(deploymentGuide).toContain("VITE_SUPABASE_PUBLISHABLE_KEY");
    expect(deploymentGuide).toContain("Supabase Auth");
    expect(deploymentGuide).toContain("Redirect URLs");
  });
});
