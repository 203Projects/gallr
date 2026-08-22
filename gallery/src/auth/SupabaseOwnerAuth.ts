import type { Session, SupabaseClient } from "@supabase/supabase-js";
import type {
  OwnerAuth,
  OwnerOAuthCallbackError,
  OwnerSession,
} from "../domain";

const AUTH_ERROR_PARAMETERS = ["error", "error_code", "error_description"] as const;

function consumeOAuthCallbackErrorFromUrl(): OwnerOAuthCallbackError | null {
  const url = new URL(window.location.href);
  const fragment = new URLSearchParams(url.hash.startsWith("#") ? url.hash.slice(1) : url.hash);
  const value = (name: (typeof AUTH_ERROR_PARAMETERS)[number]) =>
    url.searchParams.get(name) ?? fragment.get(name);
  const error = value("error");
  const code = value("error_code");
  const description = value("error_description");

  if (!error && !code && !description) return null;

  for (const parameter of AUTH_ERROR_PARAMETERS) {
    url.searchParams.delete(parameter);
    fragment.delete(parameter);
  }
  url.hash = fragment.size > 0 ? `#${fragment.toString()}` : "";
  window.history.replaceState(
    window.history.state,
    "",
    `${url.pathname}${url.search}${url.hash}`,
  );

  const normalized = `${code ?? ""} ${description ?? ""}`.toLowerCase();
  return normalized.includes("signup_disabled") || normalized.includes("signups not allowed")
    ? "signup-disabled"
    : "oauth-failed";
}

function toOwnerSession(session: Session | null): OwnerSession | null {
  if (!session) return null;
  return {
    userId: session.user.id,
    email: session.user.email ?? "",
  };
}

export class SupabaseOwnerAuth implements OwnerAuth {
  private readonly callbackError = consumeOAuthCallbackErrorFromUrl();

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

  getOAuthCallbackError(): OwnerOAuthCallbackError | null {
    return this.callbackError;
  }

  async sendOtp(email: string): Promise<void> {
    const { error } = await this.client.auth.signInWithOtp({
      email,
      options: {
        emailRedirectTo: window.location.origin,
        shouldCreateUser: true,
      },
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
