import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import type { Session, SupabaseClient } from "@supabase/supabase-js";
import { SignOutIcon } from "./Icons";

export type StaffRole = "contributor" | "publisher" | "admin";

export interface StaffAccess {
  userId: string;
  role: StaffRole;
  active: boolean;
}

interface AuthGateProps {
  client: SupabaseClient;
  children: (access: StaffAccess, signOut: () => Promise<void>) => ReactNode;
}

type AccessState =
  | { kind: "checking" }
  | { kind: "signed-out" }
  | { kind: "authorized"; access: StaffAccess }
  | { kind: "unauthorized"; message: string };

function parseStaffAccess(value: unknown): StaffAccess | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const row = value as Record<string, unknown>;
  const role = row.role;
  const userId = row.user_id;
  const active = row.active;
  if (
    typeof userId !== "string" ||
    (role !== "contributor" && role !== "publisher" && role !== "admin") ||
    typeof active !== "boolean"
  ) {
    return null;
  }
  return { userId, role, active };
}

async function resolveAccess(
  client: SupabaseClient,
  session: Session | null,
): Promise<AccessState> {
  if (!session) return { kind: "signed-out" };

  const { data, error } = await client.rpc("admin_current_staff");
  if (error) {
    return {
      kind: "unauthorized",
      message: "Staff access could not be verified. Sign out and try again.",
    };
  }

  const access = parseStaffAccess(data);
  if (!access) {
    return {
      kind: "unauthorized",
      message: "This account does not have gallr admin access.",
    };
  }
  if (!access.active) {
    return {
      kind: "unauthorized",
      message: "This staff account is inactive.",
    };
  }
  return { kind: "authorized", access };
}

function LoginRail() {
  return (
    <aside className="login-rail" aria-label="gallr admin">
      <strong>gallr admin</strong>
      <span className="login-rail-mark" aria-hidden="true">
        <SignOutIcon />
      </span>
    </aside>
  );
}

export function AuthGate({ client, children }: AuthGateProps) {
  const [accessState, setAccessState] = useState<AccessState>({ kind: "checking" });
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formMessage, setFormMessage] = useState<string | null>(null);

  useEffect(() => {
    let current = true;

    const synchronize = async (session: Session | null) => {
      setAccessState({ kind: "checking" });
      const next = await resolveAccess(client, session);
      if (current) setAccessState(next);
    };

    void client.auth.getSession().then(({ data }) => synchronize(data.session));
    const {
      data: { subscription },
    } = client.auth.onAuthStateChange((_event, session) => {
      void synchronize(session);
    });

    return () => {
      current = false;
      subscription.unsubscribe();
    };
  }, [client]);

  const signOut = async () => {
    setFormMessage(null);
    await client.auth.signOut();
  };

  const handleSignIn = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSubmitting(true);
    setFormMessage(null);
    const { error } = await client.auth.signInWithPassword({
      email: email.trim(),
      password,
    });
    if (error) setFormMessage("Email or password is incorrect.");
    setSubmitting(false);
  };

  const handlePasswordReset = async () => {
    if (!email.trim()) {
      setFormMessage("Enter your email before requesting a reset link.");
      return;
    }
    setSubmitting(true);
    setFormMessage(null);
    const { error } = await client.auth.resetPasswordForEmail(email.trim(), {
      redirectTo: window.location.origin,
    });
    setFormMessage(
      error ? "The reset link could not be sent." : "Check your email for a reset link.",
    );
    setSubmitting(false);
  };

  if (accessState.kind === "authorized") {
    return <>{children(accessState.access, signOut)}</>;
  }

  if (accessState.kind === "checking") {
    return (
      <div className="login-shell" aria-busy="true">
        <LoginRail />
        <main className="login-stage">
          <p className="login-checking" role="status">
            Checking session…
          </p>
        </main>
      </div>
    );
  }

  if (accessState.kind === "unauthorized") {
    return (
      <div className="login-shell">
        <LoginRail />
        <main className="login-stage">
          <section className="access-denied" aria-labelledby="access-denied-title">
            <h1 id="access-denied-title">Access unavailable</h1>
            <p>{accessState.message}</p>
            <button className="black-button" type="button" onClick={signOut}>
              Sign out
            </button>
          </section>
        </main>
      </div>
    );
  }

  return (
    <div className="login-shell">
      <LoginRail />
      <main className="login-stage">
        <form className="login-form" onSubmit={handleSignIn}>
          <h1>gallr</h1>
          <p>Content admin</p>
          <label>
            <span>Email</span>
            <input
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <label>
            <span>Password</span>
            <input
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <button className="black-button" type="submit" disabled={submitting}>
            {submitting ? "Signing in…" : "Sign in"}
          </button>
          <button
            className="forgot-password-button"
            type="button"
            disabled={submitting}
            onClick={handlePasswordReset}
          >
            Forgot password?
          </button>
          <div className="login-message" role="status" aria-live="polite">
            {formMessage}
          </div>
        </form>
      </main>
    </div>
  );
}
