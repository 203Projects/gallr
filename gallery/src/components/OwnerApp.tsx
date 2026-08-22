import { useCallback, useEffect, useRef, useState } from "react";
import type {
  ExistingGalleryClaimInput,
  GallerySearchResult,
  NewGalleryClaimInput,
  OwnerAccess,
  OwnerAuth,
  OwnerOAuthCallbackError,
  OwnerRepository,
  OwnerSession,
} from "../domain";
import {
  LocaleToggle,
  alternateBilingual,
  localizeBilingual,
  useLocale,
  type PortalMessages,
} from "../i18n";
import { ExhibitionWorkspace } from "./ExhibitionWorkspace";
import { GalleryInfoWorkspace } from "./GalleryInfoWorkspace";
import { OwnerShell } from "./OwnerShell";
import { LaunchKitWorkspace } from "./LaunchKitWorkspace";

type WorkspaceState =
  | { kind: "checking" }
  | { kind: "signed-out"; callbackError: OwnerOAuthCallbackError | null }
  | { kind: "ready"; session: OwnerSession; access: OwnerAccess | null }
  | { kind: "error"; error: OwnerErrorKey };

type OwnerErrorKey = keyof PortalMessages["onboarding"]["errors"];

type OwnerWorkspace = "exhibitions" | "gallery-info" | "launch";

function callbackErrorKey(error: OwnerOAuthCallbackError | null): OwnerErrorKey | null {
  if (error === "signup-disabled") return "signupDisabled";
  if (error === "oauth-failed") return "oauthCallback";
  return null;
}

function SignIn({
  auth,
  callbackError,
}: {
  auth: OwnerAuth;
  callbackError: OwnerOAuthCallbackError | null;
}) {
  const { messages } = useLocale();
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState<"email" | "google" | null>(null);
  const [error, setError] = useState<OwnerErrorKey | null>(() => callbackErrorKey(callbackError));

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!email.trim() || busy) return;
    setBusy("email");
    setError(null);
    try {
      await auth.sendOtp(email.trim());
      setSent(true);
    } catch {
      setError("sendEmail");
    } finally {
      setBusy(null);
    }
  };

  const signInWithGoogle = async () => {
    if (busy) return;
    setBusy("google");
    setError(null);
    try {
      await auth.signInWithGoogle();
    } catch {
      setError("google");
    } finally {
      setBusy(null);
    }
  };

  return (
    <main className="auth-layout">
      <div className="auth-wordmark">{messages.common.brand}</div>
      <section className="auth-panel">
        <LocaleToggle className="standalone-locale-toggle" />
        <h1>{messages.auth.heading}</h1>
        {sent ? (
          <div className="auth-confirmation" role="status">
            <h2>{messages.auth.checkEmail}</h2>
            <p>{messages.auth.sentMessage(email.trim())}</p>
            <button className="text-button" type="button" onClick={() => setSent(false)}>
              {messages.auth.differentEmail}
            </button>
          </div>
        ) : (
          <form onSubmit={(event) => void submit(event)}>
            <p>{messages.auth.intro}</p>
            <label className="field">
              <span>{messages.auth.email}</span>
              <input
                type="email"
                autoComplete="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                required
              />
            </label>
            {error && <p className="field-error" role="alert">! {messages.onboarding.errors[error]}</p>}
            <button className="standard-button auth-submit" type="submit" disabled={busy !== null}>
              {busy === "email" ? messages.auth.sending : messages.auth.sendCode}
            </button>
            <div className="auth-divider" aria-hidden="true">
              <span>{messages.auth.or}</span>
            </div>
            <button
              className="standard-button auth-google"
              type="button"
              disabled={busy !== null}
              onClick={() => void signInWithGoogle()}
            >
              {busy === "google" ? messages.auth.openingGoogle : messages.auth.continueGoogle}
            </button>
          </form>
        )}
      </section>
    </main>
  );
}

interface EvidenceFieldsProps {
  value: { websiteUrl: string; socialUrl: string; claimNote: string };
  onChange: (value: EvidenceFieldsProps["value"]) => void;
}

function EvidenceFields({ value, onChange }: EvidenceFieldsProps) {
  const { messages } = useLocale();
  return (
    <div className="evidence-grid">
      <label className="field">
        <span>{messages.onboarding.officialWebsite}</span>
        <input
          type="url"
          value={value.websiteUrl}
          onChange={(event) => onChange({ ...value, websiteUrl: event.target.value })}
          placeholder="https://"
        />
      </label>
      <label className="field">
        <span>{messages.onboarding.officialSocial}</span>
        <input
          type="url"
          value={value.socialUrl}
          onChange={(event) => onChange({ ...value, socialUrl: event.target.value })}
          placeholder="https://"
        />
      </label>
      <label className="field field-wide">
        <span>{messages.onboarding.claimNote}</span>
        <textarea
          value={value.claimNote}
          onChange={(event) => onChange({ ...value, claimNote: event.target.value })}
          rows={4}
        />
      </label>
    </div>
  );
}

function GalleryOnboarding({
  repository,
  onAccess,
  onSignOut,
}: {
  repository: OwnerRepository;
  onAccess: (access: OwnerAccess) => void;
  onSignOut: () => void;
}) {
  const { locale, messages } = useLocale();
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<GallerySearchResult[] | null>(null);
  const [selected, setSelected] = useState<GallerySearchResult | null>(null);
  const [creating, setCreating] = useState(false);
  const [nameKo, setNameKo] = useState("");
  const [nameEn, setNameEn] = useState("");
  const [evidence, setEvidence] = useState({ websiteUrl: "", socialUrl: "", claimNote: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<OwnerErrorKey | null>(null);

  const search = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (query.trim().length < 2 || busy) return;
    setBusy(true);
    setError(null);
    try {
      setResults(await repository.searchGalleries(query.trim()));
    } catch {
      setError("search");
    } finally {
      setBusy(false);
    }
  };

  const evidencePresent = Boolean(
    evidence.websiteUrl.trim() || evidence.socialUrl.trim() || evidence.claimNote.trim(),
  );

  const claimExisting = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selected || busy) return;
    if (!evidencePresent) {
      setError("evidence");
      return;
    }
    const input: ExistingGalleryClaimInput = { galleryId: selected.galleryId, ...evidence };
    setBusy(true);
    setError(null);
    try {
      onAccess(await repository.claimExistingGallery(input));
    } catch {
      setError("claim");
    } finally {
      setBusy(false);
    }
  };

  const createGallery = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!nameKo.trim() || busy) return;
    if (!evidencePresent) {
      setError("evidence");
      return;
    }
    const input: NewGalleryClaimInput = { nameKo, nameEn, ...evidence };
    setBusy(true);
    setError(null);
    try {
      onAccess(await repository.createGalleryClaim(input));
    } catch {
      setError("create");
    } finally {
      setBusy(false);
    }
  };

  return (
    <OwnerShell active="setup" onSignOut={onSignOut}>
      <main className="workspace onboarding-workspace">
        <h1>{messages.onboarding.title}</h1>
        <p className="workspace-intro">{messages.onboarding.intro}</p>
        {!creating && !selected && (
          <>
            <form className="search-form" onSubmit={(event) => void search(event)}>
              <label className="field search-input">
                <span>{messages.onboarding.galleryName}</span>
                <input
                  type="search"
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder={messages.onboarding.searchPlaceholder}
                />
              </label>
              <button className="standard-button" type="submit" disabled={busy || query.trim().length < 2}>
                {messages.onboarding.search}
              </button>
            </form>
            <p className="search-help">{messages.onboarding.searchHelp}</p>
            {results && (
              <div className="search-results" aria-live="polite">
                {results.length === 0 ? (
                  <p>{messages.onboarding.noMatch}</p>
                ) : results.map((gallery) => (
                  <article className="gallery-result" key={gallery.galleryId}>
                    <div>
                      <h2>{localizeBilingual(gallery.nameKo, gallery.nameEn, locale)}</h2>
                      {alternateBilingual(gallery.nameKo, gallery.nameEn, locale) && (
                        <p>{alternateBilingual(gallery.nameKo, gallery.nameEn, locale)}</p>
                      )}
                      {(gallery.addressKo || gallery.addressEn) && (
                        <p>{localizeBilingual(gallery.addressKo, gallery.addressEn, locale)}</p>
                      )}
                    </div>
                    <button
                      className="outlined-button"
                      type="button"
                      disabled={gallery.isClaimed}
                      onClick={() => setSelected(gallery)}
                    >
                      {gallery.isClaimed ? messages.onboarding.alreadyClaimed : messages.onboarding.requestAccess}
                    </button>
                  </article>
                ))}
              </div>
            )}
            <div className="create-divider">
              <span>{messages.onboarding.cantFind}</span>
              <button className="text-button" type="button" onClick={() => setCreating(true)}>
                {messages.onboarding.createNew}
              </button>
            </div>
          </>
        )}

        {selected && (
          <form className="claim-form" onSubmit={(event) => void claimExisting(event)}>
            <button className="text-button back-action" type="button" onClick={() => setSelected(null)}>
              {messages.onboarding.backToSearch}
            </button>
            <h2>{messages.onboarding.requestAccessTo(localizeBilingual(selected.nameKo, selected.nameEn, locale))}</h2>
            <p>{messages.onboarding.shareReference}</p>
            <EvidenceFields value={evidence} onChange={setEvidence} />
            {error && <p className="field-error" role="alert">! {messages.onboarding.errors[error]}</p>}
            <button className="standard-button" type="submit" disabled={busy}>{messages.onboarding.submitClaim}</button>
          </form>
        )}

        {creating && (
          <form className="claim-form" onSubmit={(event) => void createGallery(event)}>
            <button className="text-button back-action" type="button" onClick={() => setCreating(false)}>
              {messages.onboarding.backToSearch}
            </button>
            <h2>{messages.onboarding.createTitle}</h2>
            <div className="evidence-grid">
              <label className="field">
                <span>{messages.onboarding.nameKo}</span>
                <input value={nameKo} onChange={(event) => setNameKo(event.target.value)} required />
              </label>
              <label className="field">
                <span>{messages.onboarding.nameEn}</span>
                <input value={nameEn} onChange={(event) => setNameEn(event.target.value)} />
              </label>
            </div>
            <EvidenceFields value={evidence} onChange={setEvidence} />
            {error && <p className="field-error" role="alert">! {messages.onboarding.errors[error]}</p>}
            <button className="standard-button" type="submit" disabled={busy}>{messages.onboarding.createGallery}</button>
          </form>
        )}
        {!selected && error && <p className="field-error" role="alert">! {messages.onboarding.errors[error]}</p>}
      </main>
    </OwnerShell>
  );
}

function SuspendedAccess({ onSignOut }: { onSignOut: () => void }) {
  const { messages } = useLocale();
  return (
    <main className="blocked-layout">
      <strong>{messages.common.brand}</strong>
      <section>
        <LocaleToggle className="standalone-locale-toggle" />
        <h1>{messages.onboarding.suspendedTitle}</h1>
        <p>{messages.onboarding.suspendedBody}</p>
        <button className="outlined-button" type="button" onClick={onSignOut}>{messages.common.signOut}</button>
      </section>
    </main>
  );
}

export function OwnerApp({
  auth,
  repository,
  launchKitEnabled = false,
  promotionEnabled = false,
  publicSiteUrl = "https://gallrmap.com",
}: {
  auth: OwnerAuth;
  repository: OwnerRepository;
  launchKitEnabled?: boolean;
  promotionEnabled?: boolean;
  publicSiteUrl?: string;
}) {
  const { messages } = useLocale();
  const [state, setState] = useState<WorkspaceState>({ kind: "checking" });
  const [activeWorkspace, setActiveWorkspace] = useState<OwnerWorkspace>("exhibitions");
  const callbackError = useRef<OwnerOAuthCallbackError | null>(
    auth.getOAuthCallbackError(),
  );

  const synchronize = useCallback(async (session: OwnerSession | null) => {
    if (!session) {
      setState((current) => {
        const nextError = callbackError.current ?? (
          current.kind === "signed-out" ? current.callbackError : null
        );
        callbackError.current = null;
        return { kind: "signed-out", callbackError: nextError };
      });
      return;
    }
    setState({ kind: "checking" });
    try {
      const access = await repository.currentAccess();
      setState({ kind: "ready", session, access });
    } catch {
      setState({ kind: "error", error: "access" });
    }
  }, [repository]);

  useEffect(() => {
    let current = true;
    void auth.getSession()
      .then((session) => {
        if (current) return synchronize(session);
      })
      .catch(() => {
        if (current) {
          setState({
            kind: "error",
            error: "session",
          });
        }
      });
    const unsubscribe = auth.subscribe((session) => {
      if (current) void synchronize(session);
    });
    return () => {
      current = false;
      unsubscribe();
    };
  }, [auth, synchronize]);

  const signOut = async () => {
    try {
      await auth.signOut();
      callbackError.current = null;
      setState({ kind: "signed-out", callbackError: null });
    } catch {
      setState({ kind: "error", error: "signOut" });
    }
  };

  if (state.kind === "checking") return <main className="loading-state"><LocaleToggle className="loading-locale-toggle" />{messages.common.loadingWorkspace}</main>;
  if (state.kind === "signed-out") {
    return <SignIn auth={auth} callbackError={state.callbackError} />;
  }
  if (state.kind === "error") {
    return (
      <main className="blocked-layout">
        <strong>{messages.common.brand}</strong>
        <section><LocaleToggle className="standalone-locale-toggle" /><h1>{messages.common.workspaceUnavailable}</h1><p>! {messages.onboarding.errors[state.error]}</p></section>
      </main>
    );
  }
  if (!state.access || state.access.membership.status === "rejected" || state.access.membership.status === "revoked") {
    return (
      <GalleryOnboarding
        repository={repository}
        onAccess={(access) => setState({ ...state, access })}
        onSignOut={() => void signOut()}
      />
    );
  }
  if (state.access.membership.status === "suspended") {
    return <SuspendedAccess onSignOut={() => void signOut()} />;
  }
  const galleryInfoEnabled = state.access.membership.status === "active" || (
    state.access.membership.status === "pending" && state.access.gallery.status === "pending"
  );
  const ownerLaunchKitEnabled = launchKitEnabled && state.access.membership.status === "active";
  if (ownerLaunchKitEnabled && activeWorkspace === "launch") return (
    <LaunchKitWorkspace
      repository={repository}
      onNavigate={setActiveWorkspace}
      onSignOut={() => void signOut()}
      promotionEnabled={promotionEnabled}
      publicSiteUrl={publicSiteUrl}
    />
  );
  if (galleryInfoEnabled && activeWorkspace === "gallery-info") return (
    <GalleryInfoWorkspace
      repository={repository}
      onNavigate={setActiveWorkspace}
      onSignOut={() => void signOut()}
      launchKitEnabled={ownerLaunchKitEnabled}
    />
  );
  return (
    <ExhibitionWorkspace
      membershipStatus={state.access.membership.status}
      repository={repository}
      onSignOut={() => void signOut()}
      onNavigateLaunch={() => setActiveWorkspace("launch")}
      onNavigateGalleryInfo={() => setActiveWorkspace("gallery-info")}
      galleryInfoEnabled={galleryInfoEnabled}
      launchKitEnabled={ownerLaunchKitEnabled}
      publicSiteUrl={publicSiteUrl}
    />
  );
}
