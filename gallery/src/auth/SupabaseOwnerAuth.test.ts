import type { SupabaseClient } from "@supabase/supabase-js";
import { SupabaseOwnerAuth } from "./SupabaseOwnerAuth";

function createClient(error: unknown = null) {
  const signInWithOAuth = vi.fn().mockResolvedValue({
    data: { provider: "google", url: "https://accounts.google.com/" },
    error,
  });
  return {
    client: { auth: { signInWithOAuth } } as unknown as SupabaseClient,
    signInWithOAuth,
  };
}

describe("SupabaseOwnerAuth", () => {
  it("starts Google OAuth on the current gallery portal origin", async () => {
    const { client, signInWithOAuth } = createClient();
    const auth = new SupabaseOwnerAuth(client);

    await auth.signInWithGoogle();

    expect(signInWithOAuth).toHaveBeenCalledWith({
      provider: "google",
      options: { redirectTo: window.location.origin },
    });
  });
});
