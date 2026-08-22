import type { Session, SupabaseClient } from "@supabase/supabase-js";
import type { OwnerAuth, OwnerSession } from "../domain";

function toOwnerSession(session: Session | null): OwnerSession | null {
  if (!session) return null;
  return {
    userId: session.user.id,
    email: session.user.email ?? "",
  };
}

export class SupabaseOwnerAuth implements OwnerAuth {
  constructor(private readonly client: SupabaseClient) {}

  async getSession(): Promise<OwnerSession | null> {
    const { data, error } = await this.client.auth.getSession();
    if (error) throw new Error("Session could not be verified.");
    return toOwnerSession(data.session);
  }

  subscribe(listener: (session: OwnerSession | null) => void): () => void {
    const { data } = this.client.auth.onAuthStateChange((_event, session) => {
      listener(toOwnerSession(session));
    });
    return () => data.subscription.unsubscribe();
  }

  async sendOtp(email: string): Promise<void> {
    const { error } = await this.client.auth.signInWithOtp({
      email,
      options: { emailRedirectTo: window.location.origin },
    });
    if (error) throw new Error("Sign-in email could not be sent.");
  }

  async signInWithGoogle(): Promise<void> {
    const { error } = await this.client.auth.signInWithOAuth({
      provider: "google",
      options: { redirectTo: window.location.origin },
    });
    if (error) throw new Error("Google sign-in could not be started.");
  }

  async signOut(): Promise<void> {
    const { error } = await this.client.auth.signOut();
    if (error) throw new Error("Sign out failed.");
  }
}
