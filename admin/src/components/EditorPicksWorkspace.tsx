import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  EditorCurationChange,
  EditorExhibitionSuggestion,
  EditorPickCandidate,
  EditorProfile,
} from "../domain";
import type { EditorPickRepository } from "../repositories/EditorPickRepository";
import { SearchIcon, SignOutIcon } from "./Icons";

const emptySuggestion: EditorExhibitionSuggestion = {
  nameKo: "", nameEn: "", venueNameKo: "", venueNameEn: "",
  openingDate: "", closingDate: "", addressKo: "", addressEn: "",
  hours: "", descriptionKo: "", descriptionEn: "",
};

function statusLabel(
  candidate: EditorPickCandidate,
  staged: boolean | undefined,
): string {
  if (staged !== undefined) {
    return staged ? "Unsent addition" : "Unsent removal";
  }
  if (candidate.selected && candidate.live) return "Live";
  if (candidate.selected) return "Awaiting approval";
  if (candidate.live) return "Removal awaiting approval";
  return "Available";
}

function MissingExhibitionForm({
  repository,
  onClose,
  onSent,
}: {
  repository: EditorPickRepository;
  onClose: () => void;
  onSent: (message: string) => void;
}) {
  const [form, setForm] = useState(emptySuggestion);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const update = <Key extends keyof EditorExhibitionSuggestion>(
    key: Key,
    value: EditorExhibitionSuggestion[Key],
  ) => setForm((current) => ({ ...current, [key]: value }));

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.nameKo.trim() || !form.venueNameKo.trim() ||
        !form.openingDate || !form.closingDate || !form.addressKo.trim() ||
        !form.hours.trim()) {
      setError("Complete the required Korean exhibition details.");
      return;
    }
    if (form.closingDate < form.openingDate) {
      setError("Closing date cannot be earlier than opening date.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await repository.submitExhibition(form);
      onSent("Your exhibition suggestion was sent to the admin review queue.");
      onClose();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "The exhibition suggestion could not be sent.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="editor-suggestion-panel" aria-labelledby="suggestion-title">
      <header>
        <div>
          <span className="workspace-kicker">MISSING FROM GALLR</span>
          <h2 id="suggestion-title">Suggest an exhibition</h2>
        </div>
        <button className="text-button" type="button" onClick={onClose}>Close</button>
      </header>
      <p>Submit the essential details. An admin will verify and create the canonical draft.</p>
      <form onSubmit={submit} noValidate>
        <div className="editor-form-grid">
          <label className="field"><span>Exhibition name (Korean) *</span><input aria-label="Exhibition name (Korean)" value={form.nameKo} onChange={(event) => update("nameKo", event.target.value)} /></label>
          <label className="field"><span>Exhibition name (English)</span><input value={form.nameEn} onChange={(event) => update("nameEn", event.target.value)} /></label>
          <label className="field"><span>Venue name (Korean) *</span><input aria-label="Venue name (Korean)" value={form.venueNameKo} onChange={(event) => update("venueNameKo", event.target.value)} /></label>
          <label className="field"><span>Venue name (English)</span><input value={form.venueNameEn} onChange={(event) => update("venueNameEn", event.target.value)} /></label>
          <label className="field"><span>Opening date *</span><input aria-label="Opening date" type="date" value={form.openingDate} onChange={(event) => update("openingDate", event.target.value)} /></label>
          <label className="field"><span>Closing date *</span><input aria-label="Closing date" type="date" value={form.closingDate} onChange={(event) => update("closingDate", event.target.value)} /></label>
          <label className="field editor-form-wide"><span>Address (Korean) *</span><input aria-label="Address (Korean)" value={form.addressKo} onChange={(event) => update("addressKo", event.target.value)} /></label>
          <label className="field editor-form-wide"><span>Address (English)</span><input value={form.addressEn} onChange={(event) => update("addressEn", event.target.value)} /></label>
          <label className="field editor-form-wide"><span>Hours *</span><input aria-label="Hours" value={form.hours} onChange={(event) => update("hours", event.target.value)} /></label>
          <label className="field editor-form-wide"><span>Description (Korean)</span><textarea value={form.descriptionKo} onChange={(event) => update("descriptionKo", event.target.value)} /></label>
          <label className="field editor-form-wide"><span>Description (English)</span><textarea value={form.descriptionEn} onChange={(event) => update("descriptionEn", event.target.value)} /></label>
        </div>
        {error ? <div className="inline-notice" role="alert">! {error}</div> : null}
        <button className="black-button" type="submit" disabled={busy}>
          {busy ? "Sending…" : "Send exhibition for review"}
        </button>
      </form>
    </section>
  );
}

function EditorProfileWorkspace({
  repository,
  profile,
  loading,
  onSubmitted,
}: {
  repository: EditorPickRepository;
  profile: EditorProfile | null;
  loading: boolean;
  onSubmitted: (bioKo: string, bioEn: string) => void;
}) {
  const [bioKo, setBioKo] = useState("");
  const [bioEn, setBioEn] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!profile) return;
    setBioKo(profile.bioKo);
    setBioEn(profile.bioEn);
  }, [profile]);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!bioKo.trim()) {
      setMessage("! Korean bio is required.");
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      await repository.submitProfile(bioKo, bioEn);
      setMessage("Your bio update was sent for admin approval.");
      onSubmitted(bioKo, bioEn);
    } catch (caught) {
      setMessage(caught instanceof Error ? caught.message : "Your bio update could not be sent.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="workspace editor-profile-workspace">
      <header className="workspace-header">
        <div className="workspace-title-row"><div><h1>My profile</h1><p className="editor-identity">Only your bio can be proposed here.</p></div></div>
      </header>
      {loading ? <div className="table-state"><p>Loading profile…</p></div> : profile ? (
        <form className="editor-profile-form" onSubmit={submit} noValidate>
          <section className="editor-profile-identity">
            <span>PUBLIC IDENTITY</span>
            <h2>{profile.nameKo}</h2>
            <p>{profile.nameEn}</p>
          </section>
          <label className="field"><span>Bio (Korean) *</span><textarea aria-label="Bio (Korean)" value={bioKo} onChange={(event) => setBioKo(event.target.value)} /></label>
          <label className="field"><span>Bio (English)</span><textarea aria-label="Bio (English)" value={bioEn} onChange={(event) => setBioEn(event.target.value)} /></label>
          {profile.pendingProfile ? <div className="inline-notice">A bio request is already awaiting admin review.</div> : null}
          {message ? <div className="inline-notice" role="status">{message}</div> : null}
          <button className="black-button" type="submit" disabled={busy || profile.pendingProfile}>
            {busy ? "Sending…" : "Send bio for approval"}
          </button>
        </form>
      ) : <div className="table-state"><p>Profile could not be loaded.</p></div>}
    </main>
  );
}

export function EditorPicksWorkspace({
  repository,
  editorName,
  onSignOut,
}: {
  repository: EditorPickRepository;
  editorName: string;
  onSignOut?: () => void;
}) {
  const [tab, setTab] = useState<"curation" | "profile">("curation");
  const [search, setSearch] = useState("");
  const [candidates, setCandidates] = useState<EditorPickCandidate[]>([]);
  const [staged, setStaged] = useState<Record<string, EditorCurationChange>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [suggesting, setSuggesting] = useState(false);
  const [profile, setProfile] = useState<EditorProfile | null>(null);
  const [profileLoading, setProfileLoading] = useState(true);
  const [curationDescriptionKo, setCurationDescriptionKo] = useState("");
  const [curationDescriptionEn, setCurationDescriptionEn] = useState("");
  const loadGeneration = useRef(0);

  const loadCandidates = useCallback(async () => {
    const generation = ++loadGeneration.current;
    setLoading(true);
    try {
      const next = await repository.list(search);
      if (loadGeneration.current === generation) setCandidates(next);
    } catch (caught) {
      if (loadGeneration.current === generation) {
        setMessage(caught instanceof Error ? caught.message : "Ongoing exhibitions could not be loaded.");
      }
    } finally {
      if (loadGeneration.current === generation) setLoading(false);
    }
  }, [repository, search]);

  const loadProfile = useCallback(async () => {
    setProfileLoading(true);
    try {
      setProfile(await repository.getProfile());
    } catch {
      setProfile(null);
    } finally {
      setProfileLoading(false);
    }
  }, [repository]);

  useEffect(() => { void loadCandidates(); }, [loadCandidates]);
  useEffect(() => { void loadProfile(); }, [loadProfile]);
  useEffect(() => {
    if (!profile) return;
    setCurationDescriptionKo(profile.curationDescriptionKo);
    setCurationDescriptionEn(profile.curationDescriptionEn);
  }, [profile]);

  const changes = useMemo<EditorCurationChange[]>(
    () => Object.values(staged),
    [staged],
  );
  const statementDirty = profile !== null && (
    curationDescriptionKo.trim() !== profile.curationDescriptionKo.trim() ||
    curationDescriptionEn.trim() !== profile.curationDescriptionEn.trim()
  );
  const unsentChangeCount = changes.length + (statementDirty ? 1 : 0);
  const curationPending = Boolean(profile?.pendingCuration) ||
    candidates.some((candidate) => candidate.selected !== candidate.live);

  const toggle = (candidate: EditorPickCandidate) => {
    const current = staged[candidate.id]?.selected ?? candidate.selected;
    const next = !current;
    setStaged((values) => {
      const updated = { ...values };
      if (next === candidate.selected) delete updated[candidate.id];
      else {
        updated[candidate.id] = {
          exhibitionId: candidate.id,
          expectedVersionId: candidate.workingVersionId,
          expectedRevision: candidate.revision,
          selected: next,
        };
      }
      return updated;
    });
  };

  const submitCuration = async () => {
    if (unsentChangeCount === 0) return;
    if (!curationDescriptionKo.trim()) {
      setMessage("Korean curatorial statement is required.");
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const nextKo = curationDescriptionKo.trim();
      const nextEn = curationDescriptionEn.trim();
      const result = await repository.submitCuration(changes, nextKo, nextEn);
      const changed = new Map(result.candidates.map((candidate) => [candidate.id, candidate]));
      setCandidates((current) => current.map((candidate) => changed.get(candidate.id) ?? candidate));
      setStaged({});
      setProfile((current) => current ? {
        ...current,
        curationDescriptionKo: nextKo,
        curationDescriptionEn: nextEn,
        pendingCuration: true,
      } : current);
      setMessage("Your curation request was sent for admin approval.");
    } catch (caught) {
      setMessage(caught instanceof Error ? caught.message : "Your curation request could not be sent.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="admin-shell editor-portal-shell">
      <aside className="primary-navigation" aria-label="Editor navigation">
        <div className="wordmark">gallr editor</div>
        <nav>
          <button className={`navigation-item${tab === "curation" ? " is-active" : ""}`} type="button" aria-current={tab === "curation" ? "page" : undefined} onClick={() => setTab("curation")}>My curation</button>
          <button className={`navigation-item${tab === "profile" ? " is-active" : ""}`} type="button" aria-current={tab === "profile" ? "page" : undefined} onClick={() => setTab("profile")}>My profile</button>
        </nav>
        <button className="sign-out-button" type="button" aria-label="Sign out" onClick={onSignOut} disabled={!onSignOut}><SignOutIcon /></button>
      </aside>

      {tab === "profile" ? (
        <EditorProfileWorkspace repository={repository} profile={profile} loading={profileLoading} onSubmitted={(bioKo, bioEn) => setProfile((current) => current ? { ...current, bioKo, bioEn, pendingProfile: true } : current)} />
      ) : (
        <main className="workspace editor-picks-workspace">
          <header className="workspace-header">
            <div className="workspace-title-row">
              <div><h1>My curation</h1><p className="editor-identity">Curating as {editorName}</p></div>
              <span className="editor-pick-count">{unsentChangeCount === 1 ? "1 unsent change" : `${unsentChangeCount} unsent changes`}</span>
            </div>
            <p className="editor-picks-guidance">Choose from exhibitions happening now, then send the grouped changes for admin approval.</p>
            <div className="editor-curation-actions">
              <label className="search-field"><span className="visually-hidden">Search ongoing exhibitions</span><SearchIcon /><input type="search" value={search} placeholder="Search ongoing exhibitions..." onChange={(event) => setSearch(event.target.value)} /></label>
              <button className="outlined-button" type="button" onClick={() => setSuggesting(true)}>Suggest missing exhibition</button>
            </div>
            {message ? <div className="inline-notice" role="status">{message}</div> : null}
          </header>

          {suggesting ? <MissingExhibitionForm repository={repository} onClose={() => setSuggesting(false)} onSent={setMessage} /> : null}

          <section className="editor-curation-statement" aria-labelledby="curation-statement-title">
            <div className="editor-curation-statement-heading">
              <div>
                <span className="workspace-kicker">COLLECTION INTRODUCTION</span>
                <h2 id="curation-statement-title">Curatorial statement</h2>
              </div>
              <p>Shown above your curated exhibitions. This is different from your personal biography in My profile.</p>
            </div>
            {profileLoading ? <p className="muted">Loading statement…</p> : profile ? (
              <div className="editor-form-grid">
                <label className="field"><span>Curatorial statement (Korean) *</span><textarea aria-label="Curatorial statement (Korean)" value={curationDescriptionKo} disabled={busy || curationPending} onChange={(event) => setCurationDescriptionKo(event.target.value)} /></label>
                <label className="field"><span>Curatorial statement (English)</span><textarea aria-label="Curatorial statement (English)" value={curationDescriptionEn} disabled={busy || curationPending} onChange={(event) => setCurationDescriptionEn(event.target.value)} /></label>
              </div>
            ) : <p className="muted">The curatorial statement could not be loaded.</p>}
          </section>

          <section className="editor-picks-list" aria-busy={loading}>
            <div className="editor-picks-header" aria-hidden="true"><span>Exhibition</span><span>Venue</span><span>Dates</span><span>Status</span><span>Curation</span></div>
            {loading ? <div className="table-state"><p>Loading ongoing exhibitions…</p></div> : candidates.length === 0 ? <div className="table-state"><p>No ongoing exhibitions match this search. Suggest it if gallr is missing one.</p></div> : (
              <div className="editor-picks-body">
                {candidates.map((candidate) => {
                  const selected = staged[candidate.id]?.selected ?? candidate.selected;
                  return (
                    <article className={`editor-pick-row${selected ? " is-selected" : ""}`} key={candidate.id}>
                      <div className="editor-pick-title"><strong>{candidate.nameKo || candidate.nameEn}</strong>{candidate.nameEn ? <small>{candidate.nameEn}</small> : null}</div>
                      <div><span>{candidate.venueNameKo || candidate.venueNameEn}</span>{candidate.venueNameEn ? <small>{candidate.venueNameEn}</small> : null}</div>
                      <div><span>{candidate.openingDate || "—"}</span><small>{candidate.closingDate || "—"}</small></div>
                      <strong className="editor-pick-status">{statusLabel(candidate, staged[candidate.id]?.selected)}</strong>
                      <button className="outlined-button" type="button" disabled={busy || profileLoading || !profile || curationPending} aria-pressed={selected} aria-label={`${selected ? "Remove" : "Add"} ${candidate.nameKo || candidate.nameEn} ${selected ? "from" : "to"} my curation`} onClick={() => toggle(candidate)}>{selected ? "Remove" : "Add"}</button>
                    </article>
                  );
                })}
              </div>
            )}
          </section>
          <footer className="editor-curation-footer">
            <span>{curationPending ? "A curation request is awaiting review." : unsentChangeCount === 0 ? "Edit the statement or select an exhibition to begin." : `${unsentChangeCount} change${unsentChangeCount === 1 ? "" : "s"} ready for review.`}</span>
            <button className="black-button" type="button" disabled={busy || curationPending || unsentChangeCount === 0 || profileLoading || !profile} onClick={() => void submitCuration()}>{busy ? "Sending…" : "Send for approval"}</button>
          </footer>
        </main>
      )}
    </div>
  );
}
