import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  EditorCurationChange,
  EditorCurationHistoryItem,
  EditorExhibitionSuggestion,
  EditorPickCandidate,
  EditorProfile,
} from "../domain";
import type { EditorPickRepository } from "../repositories/EditorPickRepository";
import { LanguageSwitch, useI18n, type MessageKey } from "../i18n";
import { SearchIcon, SignOutIcon } from "./Icons";

type Translate = ReturnType<typeof useI18n>["t"];
type MessageParameters = Record<string, string | number>;

type UiNotice = {
  kind: "interface";
  key: MessageKey;
  parameters?: MessageParameters;
};

function interfaceNotice(
  key: MessageKey,
  parameters?: MessageParameters,
): UiNotice {
  return { kind: "interface", key, parameters };
}

function noticeText(notice: UiNotice, t: Translate): string {
  return t(notice.key, notice.parameters);
}

const emptySuggestion: EditorExhibitionSuggestion = {
  nameKo: "", nameEn: "", venueNameKo: "", venueNameEn: "",
  openingDate: "", closingDate: "", addressKo: "", addressEn: "",
  hours: "", descriptionKo: "", descriptionEn: "",
};

function statusLabel(
  candidate: EditorPickCandidate,
  staged: boolean | undefined,
  t: Translate,
): string {
  if (!candidate.available) {
    return candidate.assignedEditorName
      ? t("editorPortal.status.curatedBy", { name: candidate.assignedEditorName })
      : t("editorPortal.status.curatedElsewhere");
  }
  if (staged !== undefined) {
    return staged
      ? t("editorPortal.status.unsentAddition")
      : t("editorPortal.status.unsentRemoval");
  }
  if (candidate.selected && candidate.live) return t("editorPortal.status.live");
  if (candidate.selected) return t("editorPortal.status.awaitingApproval");
  if (candidate.live) return t("editorPortal.status.removalAwaitingApproval");
  return t("editorPortal.status.available");
}

function historyStatusLabel(
  status: EditorCurationHistoryItem["status"],
  t: Translate,
): string {
  if (status === "submitted") return t("editorPortal.history.status.submitted");
  if (status === "accepted") return t("editorPortal.history.status.accepted");
  return t("editorPortal.history.status.rejected");
}

function CurationHistoryWorkspace({
  history,
  loading,
  editorName,
  pending,
  message,
}: {
  history: EditorCurationHistoryItem[];
  loading: boolean;
  editorName: string;
  pending: boolean;
  message: UiNotice | null;
}) {
  const { locale, t, formatDate, formatNumber, localized } = useI18n();
  return (
    <main className="workspace editor-curation-history-workspace">
      <header className="workspace-header">
        <div className="workspace-title-row">
          <div>
            <h1>{t("editorPortal.navigation.curation")}</h1>
            <p className="editor-identity">{t("editorPortal.history.identity", { name: editorName })}</p>
          </div>
          <span className="editor-pick-count">
            {t(history.length === 1
              ? "editorPortal.history.count.one"
              : "editorPortal.history.count.other", {
              count: formatNumber(history.length),
            })}
          </span>
        </div>
        <p className="editor-picks-guidance">{t("editorPortal.history.guidance")}</p>
        {pending ? (
          <div className="inline-notice">{t("editorPortal.history.pending")}</div>
        ) : null}
        {message ? <div className="inline-notice" role="status">{noticeText(message, t)}</div> : null}
      </header>

      {loading ? (
        <div className="table-state"><p>{t("editorPortal.history.loading")}</p></div>
      ) : history.length === 0 ? (
        <div className="table-state editor-curation-history-empty">
          <span className="workspace-kicker">{t("editorPortal.history.emptyKicker")}</span>
          <h2>{t("editorPortal.history.emptyTitle")}</h2>
          <p>{t("editorPortal.history.emptyBody")}</p>
        </div>
      ) : (
        <section className="editor-curation-history" aria-label={t("editorPortal.history.aria")}>
          {history.map((item) => {
            const statement = localized(
              item.curationDescriptionKo,
              item.curationDescriptionEn,
              t("editorPortal.history.noStatement"),
            );
            const alternateStatement = (
              locale === "ko"
                ? item.curationDescriptionEn
                : item.curationDescriptionKo
            ).trim();
            return (
              <article className="editor-curation-history-card" key={item.id}>
              <header>
                <div>
                  <span className="workspace-kicker">{t("editorPortal.history.submitted", {
                    date: formatDate(item.submittedAt),
                  })}</span>
                  <h2>{historyStatusLabel(item.status, t)}</h2>
                </div>
                {item.reviewedAt ? (
                  <span className="editor-curation-reviewed-date">
                    {t("editorPortal.history.reviewed", {
                      date: formatDate(item.reviewedAt),
                    })}
                  </span>
                ) : null}
              </header>

              {item.reviewNotes ? (
                <div className="editor-curation-review-note">
                  <strong>{t("editorPortal.history.adminNote")}</strong>
                  <p>{item.reviewNotes}</p>
                </div>
              ) : null}

              <div className="editor-curation-history-statement">
                <span>{t("editorPortal.history.statement")}</span>
                <p>{statement}</p>
                {alternateStatement && alternateStatement !== statement ? <p>{alternateStatement}</p> : null}
              </div>

              <div className="editor-curation-history-changes">
                <span>{t("editorPortal.history.changes")}</span>
                {item.changes.length === 0 ? (
                  <p>{t("editorPortal.history.statementOnly")}</p>
                ) : (
                  <ul>
                    {item.changes.map((change) => {
                      const changeName = localized(change.nameKo, change.nameEn, change.exhibitionId);
                      const alternateChangeName = (
                        locale === "ko" ? change.nameEn : change.nameKo
                      ).trim();
                      return (
                        <li key={`${item.id}-${change.exhibitionId}`}>
                          <strong>{change.selected
                            ? t("editorPortal.history.added")
                            : t("editorPortal.history.removed")}</strong>
                          <div>
                            <span>{changeName}</span>
                            {alternateChangeName && alternateChangeName !== changeName
                              ? <small>{alternateChangeName}</small>
                              : null}
                            <small>
                              {localized(change.venueNameKo, change.venueNameEn)} · {formatDate(change.openingDate)} — {formatDate(change.closingDate)}
                            </small>
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
              </article>
            );
          })}
        </section>
      )}
    </main>
  );
}

function MissingExhibitionForm({
  repository,
  onClose,
  onSent,
}: {
  repository: EditorPickRepository;
  onClose: () => void;
  onSent: (message: UiNotice) => void;
}) {
  const { t } = useI18n();
  const [form, setForm] = useState(emptySuggestion);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<UiNotice | null>(null);
  const update = <Key extends keyof EditorExhibitionSuggestion>(
    key: Key,
    value: EditorExhibitionSuggestion[Key],
  ) => setForm((current) => ({ ...current, [key]: value }));

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form.nameKo.trim() || !form.venueNameKo.trim() ||
        !form.openingDate || !form.closingDate || !form.addressKo.trim() ||
        !form.hours.trim()) {
      setError(interfaceNotice("editorPortal.suggestion.validation.required"));
      return;
    }
    if (form.closingDate < form.openingDate) {
      setError(interfaceNotice("editorPortal.suggestion.validation.dates"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await repository.submitExhibition(form);
      onSent(interfaceNotice("editorPortal.suggestion.success"));
      onClose();
    } catch {
      setError(interfaceNotice("editorPortal.suggestion.error"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="editor-suggestion-panel" aria-labelledby="suggestion-title">
      <header>
        <div>
          <span className="workspace-kicker">{t("editorPortal.suggestion.kicker")}</span>
          <h2 id="suggestion-title">{t("editorPortal.suggestion.title")}</h2>
        </div>
        <button className="text-button" type="button" onClick={onClose}>{t("editorPortal.actions.close")}</button>
      </header>
      <p>{t("editorPortal.suggestion.introduction")}</p>
      <form onSubmit={submit} noValidate>
        <div className="editor-form-grid">
          <label className="field"><span>{t("editorPortal.fields.exhibitionNameKo")}</span><input aria-label={t("editorPortal.aria.exhibitionNameKo")} value={form.nameKo} onChange={(event) => update("nameKo", event.target.value)} /></label>
          <label className="field"><span>{t("editorPortal.fields.exhibitionNameEn")}</span><input value={form.nameEn} onChange={(event) => update("nameEn", event.target.value)} /></label>
          <label className="field"><span>{t("editorPortal.fields.venueNameKo")}</span><input aria-label={t("editorPortal.aria.venueNameKo")} value={form.venueNameKo} onChange={(event) => update("venueNameKo", event.target.value)} /></label>
          <label className="field"><span>{t("editorPortal.fields.venueNameEn")}</span><input value={form.venueNameEn} onChange={(event) => update("venueNameEn", event.target.value)} /></label>
          <label className="field"><span>{t("editorPortal.fields.openingDate")}</span><input aria-label={t("editorPortal.aria.openingDate")} type="date" value={form.openingDate} onChange={(event) => update("openingDate", event.target.value)} /></label>
          <label className="field"><span>{t("editorPortal.fields.closingDate")}</span><input aria-label={t("editorPortal.aria.closingDate")} type="date" value={form.closingDate} onChange={(event) => update("closingDate", event.target.value)} /></label>
          <label className="field editor-form-wide"><span>{t("editorPortal.fields.addressKo")}</span><input aria-label={t("editorPortal.aria.addressKo")} value={form.addressKo} onChange={(event) => update("addressKo", event.target.value)} /></label>
          <label className="field editor-form-wide"><span>{t("editorPortal.fields.addressEn")}</span><input value={form.addressEn} onChange={(event) => update("addressEn", event.target.value)} /></label>
          <label className="field editor-form-wide"><span>{t("editorPortal.fields.hours")}</span><input aria-label={t("editorPortal.aria.hours")} value={form.hours} onChange={(event) => update("hours", event.target.value)} /></label>
          <label className="field editor-form-wide"><span>{t("editorPortal.fields.descriptionKo")}</span><textarea value={form.descriptionKo} onChange={(event) => update("descriptionKo", event.target.value)} /></label>
          <label className="field editor-form-wide"><span>{t("editorPortal.fields.descriptionEn")}</span><textarea value={form.descriptionEn} onChange={(event) => update("descriptionEn", event.target.value)} /></label>
        </div>
        {error ? <div className="inline-notice" role="alert">! {noticeText(error, t)}</div> : null}
        <button className="black-button" type="submit" disabled={busy}>
          {busy ? t("editorPortal.actions.sending") : t("editorPortal.suggestion.send")}
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
  const { locale, t, localized } = useI18n();
  const [bioKo, setBioKo] = useState("");
  const [bioEn, setBioEn] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<UiNotice | null>(null);

  useEffect(() => {
    if (!profile) return;
    setBioKo(profile.bioKo);
    setBioEn(profile.bioEn);
  }, [profile]);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!bioKo.trim()) {
      setMessage(interfaceNotice("editorPortal.profile.validation.bioKo"));
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      await repository.submitProfile(bioKo, bioEn);
      setMessage(interfaceNotice("editorPortal.profile.success"));
      onSubmitted(bioKo, bioEn);
    } catch {
      setMessage(interfaceNotice("editorPortal.profile.error"));
    } finally {
      setBusy(false);
    }
  };

  const profileName = profile
    ? localized(profile.nameKo, profile.nameEn, profile.editorId)
    : "";
  const alternateProfileName = profile
    ? (locale === "ko" ? profile.nameEn : profile.nameKo).trim()
    : "";

  return (
    <main className="workspace editor-profile-workspace">
      <header className="workspace-header">
        <div className="workspace-title-row"><div><h1>{t("editorPortal.navigation.profile")}</h1><p className="editor-identity">{t("editorPortal.profile.identity")}</p></div></div>
      </header>
      {loading ? <div className="table-state"><p>{t("editorPortal.profile.loading")}</p></div> : profile ? (
        <form className="editor-profile-form" onSubmit={submit} noValidate>
          <section className="editor-profile-identity">
            <span>{t("editorPortal.profile.publicIdentity")}</span>
            <h2>{profileName}</h2>
            {alternateProfileName && alternateProfileName !== profileName
              ? <p>{alternateProfileName}</p>
              : null}
          </section>
          <label className="field"><span>{t("editorPortal.fields.bioKo")}</span><textarea aria-label={t("editorPortal.aria.bioKo")} value={bioKo} onChange={(event) => setBioKo(event.target.value)} /></label>
          <label className="field"><span>{t("editorPortal.fields.bioEn")}</span><textarea aria-label={t("editorPortal.aria.bioEn")} value={bioEn} onChange={(event) => setBioEn(event.target.value)} /></label>
          {profile.pendingProfile ? <div className="inline-notice">{t("editorPortal.profile.pending")}</div> : null}
          {message ? <div className="inline-notice" role="status">{noticeText(message, t)}</div> : null}
          <button className="black-button" type="submit" disabled={busy || profile.pendingProfile}>
            {busy ? t("editorPortal.actions.sending") : t("editorPortal.profile.send")}
          </button>
        </form>
      ) : <div className="table-state"><p>{t("editorPortal.profile.loadError")}</p></div>}
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
  const { locale, t, formatDate, formatNumber, localized } = useI18n();
  const [tab, setTab] = useState<"curation" | "add" | "profile">("curation");
  const [search, setSearch] = useState("");
  const [candidates, setCandidates] = useState<EditorPickCandidate[]>([]);
  const [staged, setStaged] = useState<Record<string, EditorCurationChange>>({});
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<UiNotice | null>(null);
  const [suggesting, setSuggesting] = useState(false);
  const [profile, setProfile] = useState<EditorProfile | null>(null);
  const [profileLoading, setProfileLoading] = useState(true);
  const [history, setHistory] = useState<EditorCurationHistoryItem[]>([]);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [historyError, setHistoryError] = useState<UiNotice | null>(null);
  const [curationDescriptionKo, setCurationDescriptionKo] = useState("");
  const [curationDescriptionEn, setCurationDescriptionEn] = useState("");
  const loadGeneration = useRef(0);

  const loadCandidates = useCallback(async () => {
    const generation = ++loadGeneration.current;
    setLoading(true);
    try {
      const next = await repository.list(search);
      if (loadGeneration.current === generation) setCandidates(next);
    } catch {
      if (loadGeneration.current === generation) {
        setMessage(interfaceNotice("editorPortal.error.loadExhibitions"));
      }
    } finally {
      if (loadGeneration.current === generation) setLoading(false);
    }
  }, [repository, search]);

  const loadProfile = useCallback(async () => {
    setProfileLoading(true);
    try {
      const next = await repository.getProfile();
      setProfile(next);
      setCurationDescriptionKo(next.curationDescriptionKo);
      setCurationDescriptionEn(next.curationDescriptionEn);
    } catch {
      setProfile(null);
    } finally {
      setProfileLoading(false);
    }
  }, [repository]);

  const loadHistory = useCallback(async () => {
    setHistoryLoading(true);
    setHistoryError(null);
    try {
      setHistory(await repository.listCurationHistory());
    } catch {
      setHistoryError(interfaceNotice("editorPortal.error.loadHistory"));
    } finally {
      setHistoryLoading(false);
    }
  }, [repository]);

  useEffect(() => {
    void Promise.all([loadProfile(), loadHistory()]);
  }, [loadHistory, loadProfile]);
  useEffect(() => {
    if (tab === "add") void loadCandidates();
  }, [loadCandidates, tab]);

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
    history.some((item) => item.status === "submitted") ||
    candidates.some((candidate) => candidate.selected !== candidate.live);

  const toggle = (candidate: EditorPickCandidate) => {
    if (!candidate.available) return;
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
      setMessage(interfaceNotice("editorPortal.validation.statementKo"));
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
      setMessage(interfaceNotice("editorPortal.curation.success"));
      await loadHistory();
      setTab("curation");
    } catch {
      setMessage(interfaceNotice("editorPortal.curation.error"));
    } finally {
      setBusy(false);
    }
  };

  const displayEditorName = profile
    ? localized(profile.nameKo, profile.nameEn, editorName)
    : editorName;

  return (
    <div className="admin-shell editor-portal-shell">
      <aside className="primary-navigation" aria-label={t("editorPortal.navigation.label")}>
        <div className="wordmark">{t("editorPortal.wordmark")}</div>
        <nav>
          <button className={`navigation-item${tab === "curation" ? " is-active" : ""}`} type="button" aria-current={tab === "curation" ? "page" : undefined} onClick={() => { setMessage(null); setTab("curation"); }}>{t("editorPortal.navigation.curation")}</button>
          <button className={`navigation-item${tab === "add" ? " is-active" : ""}`} type="button" aria-current={tab === "add" ? "page" : undefined} onClick={() => { setMessage(null); setTab("add"); }}>{t("editorPortal.navigation.add")}</button>
          <button className={`navigation-item${tab === "profile" ? " is-active" : ""}`} type="button" aria-current={tab === "profile" ? "page" : undefined} onClick={() => setTab("profile")}>{t("editorPortal.navigation.profile")}</button>
        </nav>
        <div className="navigation-footer">
          <LanguageSwitch />
          <button className="sign-out-button" type="button" aria-label={t("actions.signOut")} onClick={onSignOut} disabled={!onSignOut}><SignOutIcon /></button>
        </div>
      </aside>

      {tab === "profile" ? (
        <EditorProfileWorkspace repository={repository} profile={profile} loading={profileLoading} onSubmitted={(bioKo, bioEn) => setProfile((current) => current ? { ...current, bioKo, bioEn, pendingProfile: true } : current)} />
      ) : tab === "curation" ? (
        <CurationHistoryWorkspace
          history={history}
          loading={historyLoading}
          editorName={displayEditorName}
          pending={curationPending}
          message={message ?? historyError}
        />
      ) : (
        <main className="workspace editor-picks-workspace">
          <header className="workspace-header">
            <div className="workspace-title-row">
              <div><h1>{t("editorPortal.navigation.add")}</h1><p className="editor-identity">{t("editorPortal.add.identity", { name: displayEditorName })}</p></div>
              <span className="editor-pick-count">{t(unsentChangeCount === 1
                ? "editorPortal.add.unsent.one"
                : "editorPortal.add.unsent.other", {
                count: formatNumber(unsentChangeCount),
              })}</span>
            </div>
            <p className="editor-picks-guidance">{t("editorPortal.add.guidance")}</p>
            <div className="editor-curation-actions">
              <label className="search-field"><span className="visually-hidden">{t("editorPortal.search.label")}</span><SearchIcon /><input type="search" value={search} placeholder={t("editorPortal.search.placeholder")} onChange={(event) => setSearch(event.target.value)} /></label>
              <button className="outlined-button" type="button" onClick={() => setSuggesting(true)}>{t("editorPortal.suggestion.open")}</button>
            </div>
            {message ? <div className="inline-notice" role="status">{noticeText(message, t)}</div> : null}
          </header>

          {suggesting ? <MissingExhibitionForm repository={repository} onClose={() => setSuggesting(false)} onSent={setMessage} /> : null}

          <section className="editor-curation-statement" aria-labelledby="curation-statement-title">
            <div className="editor-curation-statement-heading">
              <div>
                <span className="workspace-kicker">{t("editorPortal.statement.kicker")}</span>
                <h2 id="curation-statement-title">{t("editorPortal.statement.title")}</h2>
              </div>
              <p>{t("editorPortal.statement.help")}</p>
            </div>
            {profileLoading ? <p className="muted">{t("editorPortal.statement.loading")}</p> : profile ? (
              <div className="editor-form-grid">
                <label className="field"><span>{t("editorPortal.fields.statementKo")}</span><textarea aria-label={t("editorPortal.aria.statementKo")} value={curationDescriptionKo} disabled={busy || curationPending} onChange={(event) => setCurationDescriptionKo(event.target.value)} /></label>
                <label className="field"><span>{t("editorPortal.fields.statementEn")}</span><textarea aria-label={t("editorPortal.aria.statementEn")} value={curationDescriptionEn} disabled={busy || curationPending} onChange={(event) => setCurationDescriptionEn(event.target.value)} /></label>
              </div>
            ) : <p className="muted">{t("editorPortal.statement.loadError")}</p>}
          </section>

          <section className="editor-picks-list" aria-busy={loading}>
            <div className="editor-picks-header" aria-hidden="true"><span>{t("editorPortal.table.exhibition")}</span><span>{t("editorPortal.table.venue")}</span><span>{t("editorPortal.table.dates")}</span><span>{t("editorPortal.table.status")}</span><span>{t("editorPortal.table.curation")}</span></div>
            {loading ? <div className="table-state"><p>{t("editorPortal.loading.exhibitions")}</p></div> : candidates.length === 0 ? <div className="table-state"><p>{t("editorPortal.empty.exhibitions")}</p></div> : (
              <div className="editor-picks-body">
                {candidates.map((candidate) => {
                  const selected = staged[candidate.id]?.selected ?? candidate.selected;
                  const candidateName = localized(
                    candidate.nameKo,
                    candidate.nameEn,
                    candidate.id,
                  );
                  const alternateCandidateName = (
                    locale === "ko" ? candidate.nameEn : candidate.nameKo
                  ).trim();
                  const venueName = localized(
                    candidate.venueNameKo,
                    candidate.venueNameEn,
                  );
                  const alternateVenueName = (
                    locale === "ko" ? candidate.venueNameEn : candidate.venueNameKo
                  ).trim();
                  const actionName = candidateName;
                  return (
                    <article className={`editor-pick-row${selected ? " is-selected" : ""}${candidate.available ? "" : " is-unavailable"}`} key={candidate.id}>
                      <div className="editor-pick-title"><strong>{candidateName}</strong>{alternateCandidateName && alternateCandidateName !== candidateName ? <small>{alternateCandidateName}</small> : null}</div>
                      <div><span>{venueName}</span>{alternateVenueName && alternateVenueName !== venueName ? <small>{alternateVenueName}</small> : null}</div>
                      <div><span>{formatDate(candidate.openingDate)}</span><small>{formatDate(candidate.closingDate)}</small></div>
                      <strong className="editor-pick-status">{statusLabel(candidate, staged[candidate.id]?.selected, t)}</strong>
                      <button
                        className="outlined-button"
                        type="button"
                        disabled={busy || profileLoading || !profile || curationPending || !candidate.available}
                        aria-pressed={candidate.available ? selected : undefined}
                        aria-label={candidate.available
                          ? t(selected
                            ? "editorPortal.aria.remove"
                            : "editorPortal.aria.add", { name: actionName })
                          : t("editorPortal.aria.unavailable", { name: actionName })}
                        onClick={() => toggle(candidate)}
                      >
                        {candidate.available
                          ? (selected
                            ? t("editorPortal.actions.remove")
                            : t("editorPortal.actions.add"))
                          : t("editorPortal.actions.unavailable")}
                      </button>
                    </article>
                  );
                })}
              </div>
            )}
          </section>
          <footer className="editor-curation-footer">
            <span>{curationPending
              ? t("editorPortal.history.pending")
              : unsentChangeCount === 0
                ? t("editorPortal.footer.start")
                : t(unsentChangeCount === 1
                  ? "editorPortal.footer.ready.one"
                  : "editorPortal.footer.ready.other", {
                  count: formatNumber(unsentChangeCount),
                })}</span>
            <button className="black-button" type="button" disabled={busy || curationPending || unsentChangeCount === 0 || profileLoading || !profile} onClick={() => void submitCuration()}>{busy ? t("editorPortal.actions.sending") : t("editorPortal.curation.send")}</button>
          </footer>
        </main>
      )}
    </div>
  );
}
