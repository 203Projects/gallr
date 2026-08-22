import type { SupabaseClient } from "@supabase/supabase-js";
import { SupabaseOwnerAuth } from "./SupabaseOwnerAuth";

function createClient(error: unknown = null) {
  const signInWithOtp = vi.fn().mockResolvedValue({ data: {}, error: null });
  const signInWithOAuth = vi.fn().mockResolvedValue({
    data: { provider: "google", url: "https://accounts.google.com/" },
    error,
  });
  return {
    client: { auth: { signInWithOtp, signInWithOAuth } } as unknown as SupabaseClient,
    signInWithOtp,
    signInWithOAuth,
  };
}

describe("SupabaseOwnerAuth", () => {
  afterEach(() => {
    window.history.replaceState({}, "", "/");
  });

  it("starts Google OAuth on the current gallery portal origin", async () => {
    const { client, signInWithOAuth } = createClient();
    const auth = new SupabaseOwnerAuth(client);

    await auth.signInWithGoogle();

    expect(signInWithOAuth).toHaveBeenCalledWith({
      provider: "google",
      options: { redirectTo: window.location.origin },
    });
  });

  it("makes Gallery email OTP an explicit self-service identity flow", async () => {
    const { client, signInWithOtp } = createClient();
    const auth = new SupabaseOwnerAuth(client);

    await auth.sendOtp("owner@example.test");

    expect(signInWithOtp).toHaveBeenCalledWith({
      email: "owner@example.test",
      options: {
        emailRedirectTo: window.location.origin,
        shouldCreateUser: true,
      },
    });
  });

  it("maps a signup-disabled OAuth callback and removes only Auth parameters", () => {
    window.history.replaceState(
      {},
      "",
      "/?next=claim&error=server_error&error_code=signup_disabled&error_description=Signups+not+allowed+for+this+instance",
    );
    const { client } = createClient();
    const auth = new SupabaseOwnerAuth(client);

    expect(auth.getOAuthCallbackError()).toBe("signup-disabled");
    expect(window.location.pathname).toBe("/");
    expect(window.location.search).toBe("?next=claim");
  });

  it("maps an OAuth fragment failure without retaining provider details", () => {
    window.history.replaceState(
      {},
      "",
      "/#error=access_denied&error_code=unexpected_provider_error&error_description=private+provider+detail",
    );
    const { client } = createClient();
    const auth = new SupabaseOwnerAuth(client);

    expect(auth.getOAuthCallbackError()).toBe("oauth-failed");
    expect(window.location.hash).toBe("");
  });

  it("returns no callback failure for an ordinary Gallery route", () => {
    window.history.replaceState({}, "", "/?next=claim");
    const { client } = createClient();
    const auth = new SupabaseOwnerAuth(client);

    expect(auth.getOAuthCallbackError()).toBeNull();
    expect(window.location.search).toBe("?next=claim");
  });
});
