import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { Session, SupabaseClient } from "@supabase/supabase-js";
import { SignOutIcon } from "./Icons";

export type AdminStaffRole = "contributor" | "publisher" | "admin";
export type StaffRole = AdminStaffRole | "editor";

interface BaseAccess {
  userId: string;
  active: boolean;
}

export type StaffAccess =
  | (BaseAccess & {
      role: AdminStaffRole;
      editorId: null;
      editorName: null;
    })
  | (BaseAccess & {
      role: "editor";
      editorId: string;
      editorName: string;
    });

interface AuthGateProps {
  client: SupabaseClient;
  children: (access: StaffAccess, signOut: () => Promise<void>) => ReactNode;
}

type AccessState =
  | { kind: "checking" }
  | { kind: "signed-out" }
  | { kind: "password-recovery"; session: Session }
  | { kind: "authorized"; access: StaffAccess }
  | { kind: "unauthorized"; message: string };

const STAFF_VERIFICATION_FAILURE: AccessState = {
  kind: "unauthorized",
  message: "Staff access could not be verified. Sign out and try again.",
};

function passwordResetMessage(error: { code?: string; status?: number } | null) {
  if (!error) return "Check your email for a reset link.";
  if (error.code === "over_email_send_rate_limit" || error.status === 429) {
    return "Too many reset emails were requested. Wait a few minutes and try again.";
  }
  return "The reset link could not be sent.";
}

interface PasswordUpdateError {
  code?: string;
  reasons?: readonly string[];
}

function passwordUpdateMessage(error: PasswordUpdateError) {
  if (error.code === "weak_password") {
    if (error.reasons?.includes("pwned")) {
      return "Choose a unique password that has not appeared in a known data breach.";
    }
    if (error.reasons?.includes("length")) {
      return "This password is too short. Use at least 8 characters.";
    }
    if (error.reasons?.includes("characters")) {
      return "This password does not meet the configured character requirements.";
    }
    return "This password was rejected as weak. Use a longer, unique password.";
  }
  if (error.code === "same_password") {
    return "Choose a password different from your current password.";
  }
  if (
    error.code === "session_expired" ||
    error.code === "session_not_found" ||
    error.code === "refresh_token_not_found" ||
    error.code === "bad_jwt" ||
    error.code === "otp_expired"
  ) {
    return "This reset session has expired. Return to sign-in and request a new link.";
  }
  return "Password could not be updated. Try again.";
}

function parseStaffAccess(value: unknown): StaffAccess | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const row = value as Record<string, unknown>;
  const role = row.role;
  const userId = row.user_id;
  const active = row.active;
  if (
    typeof userId !== "string" ||
    (role !== "contributor" &&
      role !== "publisher" &&
      role !== "admin" &&
      role !== "editor") ||
    typeof active !== "boolean"
  ) {
    return null;
  }
  if (role === "editor") {
    if (
      typeof row.editor_id !== "string" ||
      row.editor_id.trim().length === 0 ||
      typeof row.editor_name !== "string" ||
      row.editor_name.trim().length === 0
    ) {
      return null;
    }
    return {
      userId,
      role,
      active,
      editorId: row.editor_id,
      editorName: row.editor_name,
    };
  }
  return { userId, role, active, editorId: null, editorName: null };
}

async function resolveAccess(
  client: SupabaseClient,
  session: Session | null,
): Promise<AccessState> {
  if (!session) return { kind: "signed-out" };

  let result: Awaited<ReturnType<SupabaseClient["rpc"]>>;
  try {
    result = await client.rpc("admin_current_staff");
  } catch {
    return STAFF_VERIFICATION_FAILURE;
  }
  const { data, error } = result;
  if (error) return STAFF_VERIFICATION_FAILURE;

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
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [recoveryError, setRecoveryError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [formMessage, setFormMessage] = useState<string | null>(null);
  const recoveryActive = useRef(false);
  const editorInvitationActive = useRef(
    new URLSearchParams(window.location.search).get("onboarding") === "editor",
  );
  const synchronizationGeneration = useRef(0);
  const verifiedUserId = useRef<string | null>(null);

  useEffect(() => {
    let current = true;
    recoveryActive.current = false;

    const beginPasswordSetup = (session: Session | null) => {
      synchronizationGeneration.current += 1;
      verifiedUserId.current = null;
      if (!session) {
        recoveryActive.current = false;
        setAccessState({ kind: "signed-out" });
        setFormMessage("The invitation or reset link is invalid or has expired.");
        return;
      }
      recoveryActive.current = true;
      setNewPassword("");
      setConfirmPassword("");
      setRecoveryError(null);
      setAccessState({ kind: "password-recovery", session });
    };

    const synchronize = async (
      session: Session | null,
      keepAuthorizedWorkspace = false,
    ) => {
      const generation = ++synchronizationGeneration.current;
      if (!keepAuthorizedWorkspace) {
        verifiedUserId.current = null;
        setAccessState({ kind: "checking" });
      }
      let next = STAFF_VERIFICATION_FAILURE;
      try {
        next = await resolveAccess(client, session);
      } catch {
        // Keep this boundary fail-closed if access resolution changes later.
      }
      if (
        current &&
        generation === synchronizationGeneration.current &&
        !recoveryActive.current
      ) {
        verifiedUserId.current =
          next.kind === "authorized" ? next.access.userId : null;
        setAccessState(next);
      }
    };

    const initialGeneration = ++synchronizationGeneration.current;
    void client.auth
      .getSession()
      .then(({ data, error }) => {
        if (
          !current ||
          initialGeneration !== synchronizationGeneration.current ||
          recoveryActive.current
        ) {
          return;
        }
        if (error) {
          setAccessState(STAFF_VERIFICATION_FAILURE);
          return;
        }
        if (editorInvitationActive.current && data.session) {
          beginPasswordSetup(data.session);
          return;
        }
        void synchronize(data.session);
      })
      .catch(() => {
        if (
          current &&
          initialGeneration === synchronizationGeneration.current &&
          !recoveryActive.current
        ) {
          setAccessState(STAFF_VERIFICATION_FAILURE);
        }
      });
    const {
      data: { subscription },
    } = client.auth.onAuthStateChange((event, session) => {
      if (
        event === "PASSWORD_RECOVERY" ||
        (editorInvitationActive.current && session !== null &&
          (event === "SIGNED_IN" || event === "INITIAL_SESSION"))
      ) {
        beginPasswordSetup(session);
        return;
      }

      if (recoveryActive.current) {
        if (event === "SIGNED_OUT" || !session) {
          recoveryActive.current = false;
          void synchronize(null);
        } else {
          setAccessState({ kind: "password-recovery", session });
        }
        return;
      }

      void synchronize(
        session,
        session !== null && verifiedUserId.current === session.user.id,
      );
    });

    return () => {
      current = false;
      synchronizationGeneration.current += 1;
      subscription.unsubscribe();
    };
  }, [client]);

  const signOut = async () => {
    recoveryActive.current = false;
    if (editorInvitationActive.current) {
      window.history.replaceState(null, "", window.location.pathname);
    }
    editorInvitationActive.current = false;
    setPassword("");
    setFormMessage(null);
    setRecoveryError(null);
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
    setFormMessage(passwordResetMessage(error));
    setSubmitting(false);
  };

  const handleGoogleSignIn = async () => {
    setSubmitting(true);
    setFormMessage(null);
    try {
      const { error } = await client.auth.signInWithOAuth({
        provider: "google",
        options: { redirectTo: window.location.origin },
      });
      if (error) setFormMessage("Google sign-in could not be started.");
    } catch {
      setFormMessage("Google sign-in could not be started.");
    } finally {
      setSubmitting(false);
    }
  };

  const handlePasswordUpdate = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setRecoveryError(null);

    if (newPassword.length < 8) {
      setRecoveryError("Password must be at least 8 characters.");
      return;
    }
    if (newPassword !== confirmPassword) {
      setRecoveryError("Passwords do not match.");
      return;
    }
    if (accessState.kind !== "password-recovery") return;

    const recoveryGeneration = synchronizationGeneration.current;
    setSubmitting(true);
    let updateError: PasswordUpdateError | null = null;
    try {
      const { error } = await client.auth.updateUser({ password: newPassword });
      updateError = error;
    } catch {
      updateError = {};
    }
    if (
      !recoveryActive.current ||
      recoveryGeneration !== synchronizationGeneration.current
    ) {
      setSubmitting(false);
      return;
    }
    if (updateError) {
      setRecoveryError(passwordUpdateMessage(updateError));
      setSubmitting(false);
      return;
    }

    recoveryActive.current = false;
    if (editorInvitationActive.current) {
      editorInvitationActive.current = false;
      window.history.replaceState(null, "", window.location.pathname);
    }
    setNewPassword("");
    setConfirmPassword("");
    const generation = ++synchronizationGeneration.current;
    setAccessState({ kind: "checking" });
    try {
      const next = await resolveAccess(client, accessState.session);
      if (generation === synchronizationGeneration.current) {
        setAccessState(next);
      }
    } catch {
      if (generation === synchronizationGeneration.current) {
        setAccessState(STAFF_VERIFICATION_FAILURE);
      }
    } finally {
      setSubmitting(false);
    }
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

  if (accessState.kind === "password-recovery") {
    return (
      <div className="login-shell">
        <LoginRail />
        <main className="login-stage">
          <form className="login-form access-denied" onSubmit={handlePasswordUpdate}>
            <h1>Set a new password</h1>
            <p>Your new password must meet every requirement.</p>
            <ul
              id="password-recovery-requirements"
              className="password-requirements"
              aria-label="Password requirements"
            >
              <li>At least 8 characters.</li>
              <li>Different from your current password.</li>
              <li>Not found in known password breaches.</li>
              <li>Both password fields must match.</li>
              <li>Uppercase letters, numbers, and symbols are optional.</li>
            </ul>
            <label>
              <span>New password</span>
              <input
                type="password"
                autoComplete="new-password"
                required
                value={newPassword}
                aria-invalid={recoveryError !== null}
                aria-describedby="password-recovery-requirements password-recovery-message"
                onChange={(event) => setNewPassword(event.target.value)}
              />
            </label>
            <label>
              <span>Confirm password</span>
              <input
                type="password"
                autoComplete="new-password"
                required
                value={confirmPassword}
                aria-invalid={recoveryError !== null}
                aria-describedby="password-recovery-requirements password-recovery-message"
                onChange={(event) => setConfirmPassword(event.target.value)}
              />
            </label>
            <button className="black-button" type="submit" disabled={submitting}>
              {submitting ? "Updating…" : "Update password"}
            </button>
            <div
              id="password-recovery-message"
              className="login-message"
              role={recoveryError ? "alert" : "status"}
              aria-live="polite"
            >
              {recoveryError ? `! ${recoveryError}` : null}
            </div>
          </form>
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
          <div className="auth-divider" aria-hidden="true">
            <span>or</span>
          </div>
          <button
            className="black-button oauth-button"
            type="button"
            disabled={submitting}
            onClick={() => void handleGoogleSignIn()}
          >
            Continue with Google
          </button>
          <div className="login-message" role="status" aria-live="polite">
            {formMessage}
          </div>
        </form>
      </main>
    </div>
  );
}
