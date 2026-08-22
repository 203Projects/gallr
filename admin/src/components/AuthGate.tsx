import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { Session, SupabaseClient } from "@supabase/supabase-js";
import { SignOutIcon } from "./Icons";
import { LanguageSwitch, useI18n, type MessageKey } from "../i18n";

export type AdminStaffRole = "contributor" | "publisher" | "admin";
export type StaffRole = AdminStaffRole | "editor" | "editor_onboarding";

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
    })
  | (BaseAccess & {
      role: "editor_onboarding";
      editorId: null;
      editorName: null;
    });

interface AuthGateProps {
  client: SupabaseClient;
  children: (
    access: StaffAccess,
    signOut: () => Promise<void>,
    refreshAccess: () => void,
  ) => ReactNode;
  portal?: Portal;
  onPortalRedirect?: (url: string) => void;
}

export type Portal = "admin" | "editor" | "shared";

const ADMIN_PORTAL_URL = "https://admin.gallrmap.com/";
const EDITOR_PORTAL_URL = "https://editor.gallrmap.com/";

type AccessState =
  | { kind: "checking" }
  | { kind: "signed-out" }
  | { kind: "password-recovery"; session: Session }
  | { kind: "authorized"; access: StaffAccess }
  | { kind: "unauthorized"; messageKey: MessageKey };

const ACCESS_VERIFICATION_FAILURE: AccessState = {
  kind: "unauthorized",
  messageKey: "auth.accessVerifyFailed",
};

export function portalForHostname(hostname: string): Portal {
  switch (hostname.trim().toLowerCase()) {
    case "admin.gallrmap.com":
      return "admin";
    case "editor.gallrmap.com":
      return "editor";
    default:
      return "shared";
  }
}

function portalRedirect(portal: Portal, access: StaffAccess): string | null {
  if (
    portal === "admin" &&
    (access.role === "editor" || access.role === "editor_onboarding")
  ) {
    return EDITOR_PORTAL_URL;
  }
  if (
    portal === "editor" &&
    access.role !== "editor" &&
    access.role !== "editor_onboarding"
  ) {
    return ADMIN_PORTAL_URL;
  }
  return null;
}

function replacePortal(url: string): void {
  window.location.replace(url);
}

function passwordResetMessage(error: { code?: string; status?: number } | null): MessageKey {
  if (!error) return "auth.resetSent";
  if (error.code === "over_email_send_rate_limit" || error.status === 429) {
    return "auth.resetRateLimited";
  }
  return "auth.resetFailed";
}

interface PasswordUpdateError {
  code?: string;
  reasons?: readonly string[];
}

function passwordUpdateMessage(error: PasswordUpdateError): MessageKey {
  if (error.code === "weak_password") {
    if (error.reasons?.includes("pwned")) {
      return "auth.passwordBreached";
    }
    if (error.reasons?.includes("length")) {
      return "auth.passwordTooShort";
    }
    if (error.reasons?.includes("characters")) {
      return "auth.passwordCharacters";
    }
    return "auth.passwordWeak";
  }
  if (error.code === "same_password") {
    return "auth.passwordSame";
  }
  if (
    error.code === "session_expired" ||
    error.code === "session_not_found" ||
    error.code === "refresh_token_not_found" ||
    error.code === "bad_jwt" ||
    error.code === "otp_expired"
  ) {
    return "auth.resetExpired";
  }
  return "auth.passwordUpdateFailed";
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
      role !== "editor" &&
      role !== "editor_onboarding") ||
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
  if (role === "editor_onboarding") {
    return {
      userId,
      role,
      active,
      editorId: null,
      editorName: null,
    };
  }
  return { userId, role, active, editorId: null, editorName: null };
}

async function resolveAccess(
  client: SupabaseClient,
  session: Session | null,
  portal: Portal,
): Promise<AccessState> {
  if (!session) return { kind: "signed-out" };

  let result: Awaited<ReturnType<SupabaseClient["rpc"]>>;
  try {
    result = await client.rpc("admin_current_staff");
  } catch {
    return ACCESS_VERIFICATION_FAILURE;
  }
  const { data, error } = result;
  if (error) return ACCESS_VERIFICATION_FAILURE;

  const access = parseStaffAccess(data);
  if (!access) {
    return {
      kind: "unauthorized",
      messageKey: portal === "editor" ? "auth.noEditorAccess" : "auth.noAdminAccess",
    };
  }
  if (!access.active) {
    return {
      kind: "unauthorized",
      messageKey: "auth.accountInactive",
    };
  }
  return { kind: "authorized", access };
}

function LoginRail({ portal }: { portal: Portal }) {
  const label = portal === "editor" ? "gallr editor" : "gallr admin";
  return (
    <aside className="login-rail" aria-label={label}>
      <strong>{label}</strong>
      <LanguageSwitch />
      <span className="login-rail-mark" aria-hidden="true">
        <SignOutIcon />
      </span>
    </aside>
  );
}

export function AuthGate({
  client,
  children,
  portal = portalForHostname(window.location.hostname),
  onPortalRedirect = replacePortal,
}: AuthGateProps) {
  const { t } = useI18n();
  const [accessState, setAccessState] = useState<AccessState>({ kind: "checking" });
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [recoveryError, setRecoveryError] = useState<MessageKey | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [formMessage, setFormMessage] = useState<MessageKey | null>(null);
  const [accessRefresh, setAccessRefresh] = useState(0);
  const recoveryActive = useRef(false);
  const editorInvitationActive = useRef(
    new URLSearchParams(window.location.search).get("onboarding") === "editor",
  );
  const synchronizationGeneration = useRef(0);
  const verifiedUserId = useRef<string | null>(null);
  const redirectedTo = useRef<string | null>(null);

  useEffect(() => {
    document.title = portal === "editor" ? "gallr editor" : "gallr admin";
  }, [portal]);

  useEffect(() => {
    let current = true;
    recoveryActive.current = false;

    const beginPasswordSetup = (session: Session | null) => {
      synchronizationGeneration.current += 1;
      verifiedUserId.current = null;
      if (!session) {
        recoveryActive.current = false;
        setAccessState({ kind: "signed-out" });
        setFormMessage("auth.invalidLink");
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
      let next = ACCESS_VERIFICATION_FAILURE;
      try {
        next = await resolveAccess(client, session, portal);
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
          setAccessState(ACCESS_VERIFICATION_FAILURE);
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
          setAccessState(ACCESS_VERIFICATION_FAILURE);
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
  }, [accessRefresh, client, portal]);

  const redirectTarget =
    accessState.kind === "authorized"
      ? portalRedirect(portal, accessState.access)
      : null;

  useEffect(() => {
    if (redirectTarget === null || redirectedTo.current === redirectTarget) return;
    redirectedTo.current = redirectTarget;
    onPortalRedirect(redirectTarget);
  }, [onPortalRedirect, redirectTarget]);

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
    if (error) setFormMessage("auth.emailPasswordIncorrect");
    setSubmitting(false);
  };

  const handlePasswordReset = async () => {
    if (!email.trim()) {
      setFormMessage("auth.enterEmailForReset");
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
      if (error) setFormMessage("auth.googleFailed");
    } catch {
      setFormMessage("auth.googleFailed");
    } finally {
      setSubmitting(false);
    }
  };

  const handlePasswordUpdate = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setRecoveryError(null);

    if (newPassword.length < 8) {
      setRecoveryError("auth.passwordMinimum");
      return;
    }
    if (newPassword !== confirmPassword) {
      setRecoveryError("auth.passwordMismatch");
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
      const next = await resolveAccess(client, accessState.session, portal);
      if (generation === synchronizationGeneration.current) {
        setAccessState(next);
      }
    } catch {
      if (generation === synchronizationGeneration.current) {
        setAccessState(ACCESS_VERIFICATION_FAILURE);
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (accessState.kind === "authorized" && redirectTarget === null) {
    return <>
      {children(
        accessState.access,
        signOut,
        () => setAccessRefresh((current) => current + 1),
      )}
    </>;
  }

  if (accessState.kind === "checking" || redirectTarget !== null) {
    return (
      <div className="login-shell" aria-busy="true">
        <LoginRail portal={portal} />
        <main className="login-stage">
          <p className="login-checking" role="status">
            {t(redirectTarget === null ? "auth.checkingSession" : "auth.openingPortal")}
          </p>
        </main>
      </div>
    );
  }

  if (accessState.kind === "password-recovery") {
    return (
      <div className="login-shell">
        <LoginRail portal={portal} />
        <main className="login-stage">
          <form className="login-form access-denied" onSubmit={handlePasswordUpdate}>
            <h1>{t("auth.setNewPassword")}</h1>
            <p>{t("auth.requirementsIntro")}</p>
            <ul
              id="password-recovery-requirements"
              className="password-requirements"
              aria-label={t("auth.requirementsLabel")}
            >
              <li>{t("auth.requirementLength")}</li>
              <li>{t("auth.requirementDifferent")}</li>
              <li>{t("auth.requirementBreached")}</li>
              <li>{t("auth.requirementMatch")}</li>
              <li>{t("auth.requirementOptional")}</li>
            </ul>
            <label>
              <span>{t("auth.newPassword")}</span>
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
              <span>{t("auth.confirmPassword")}</span>
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
              {t(submitting ? "auth.updating" : "auth.updatePassword")}
            </button>
            <div
              id="password-recovery-message"
              className="login-message"
              role={recoveryError ? "alert" : "status"}
              aria-live="polite"
            >
              {recoveryError ? `! ${t(recoveryError)}` : null}
            </div>
          </form>
        </main>
      </div>
    );
  }

  if (accessState.kind === "unauthorized") {
    return (
      <div className="login-shell">
        <LoginRail portal={portal} />
        <main className="login-stage">
          <section className="access-denied" aria-labelledby="access-denied-title">
            <h1 id="access-denied-title">{t("auth.accessUnavailable")}</h1>
            <p>{t(accessState.messageKey)}</p>
            <button className="black-button" type="button" onClick={signOut}>
              {t("actions.signOut")}
            </button>
          </section>
        </main>
      </div>
    );
  }

  return (
    <div className="login-shell">
      <LoginRail portal={portal} />
      <main className="login-stage">
        <form className="login-form" onSubmit={handleSignIn}>
          <h1>gallr</h1>
          <p>{t(portal === "editor" ? "auth.editorCuration" : "auth.contentAdmin")}</p>
          <label>
            <span>{t("auth.email")}</span>
            <input
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <label>
            <span>{t("auth.password")}</span>
            <input
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <button className="black-button" type="submit" disabled={submitting}>
            {t(submitting ? "auth.signingIn" : "auth.signIn")}
          </button>
          <button
            className="forgot-password-button"
            type="button"
            disabled={submitting}
            onClick={handlePasswordReset}
          >
            {t("auth.forgotPassword")}
          </button>
          <div className="auth-divider" aria-hidden="true">
            <span>{t("auth.or")}</span>
          </div>
          <button
            className="black-button oauth-button"
            type="button"
            disabled={submitting}
            onClick={() => void handleGoogleSignIn()}
          >
            {t("auth.continueGoogle")}
          </button>
          <div className="login-message" role="status" aria-live="polite">
            {formMessage ? t(formMessage) : null}
          </div>
        </form>
      </main>
    </div>
  );
}
