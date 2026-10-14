import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { DialogFrame } from "./Dialogs";
import { CarouselExportDialog } from "./CarouselExportDialog";
import { RoundupExportDialog } from "./RoundupExportDialog";
import { useI18n, type MessageKey } from "../i18n";
import {
  CURATOR_NOTE_DECLINE_REASONS,
  CuratorNoteDraftingUnavailableError,
  CuratorNoteInvalidTransitionError,
  CuratorNoteNotQualifyingError,
  CuratorNoteNotStaffError,
  CuratorNoteSourceChangedError,
  CuratorNoteStaleError,
  CuratorNoteUnsupportedError,
  sentenceRef,
  type AdminCuratorNoteRepository,
  type ApprovedCuratorNote,
  type CuratorNoteDeclineReason,
  type CuratorNoteEdit,
  type CuratorNoteFacts,
  type CuratorNoteImage,
  type CuratorNoteInsight,
  type CuratorNoteLanguage,
  type CuratorNoteQueueItem,
  type CuratorNoteStatus,
  type CuratorNoteVersion,
} from "../repositories/AdminCuratorNoteRepository";
import { notePictures } from "../repositories/curatorNotePictures";

const statusKeys: Record<CuratorNoteStatus, MessageKey> = {
  draft: "notes.status.draft",
  unusable: "notes.status.unusable",
  replaced: "notes.status.replaced",
  declined: "notes.status.declined",
  approved: "notes.status.approved",
  superseded: "notes.status.superseded",
};

const statusChipClass: Record<CuratorNoteStatus, string> = {
  draft: " status-submitted",
  unusable: " status-rejected",
  replaced: "",
  declined: " status-rejected",
  approved: " status-accepted",
  superseded: "",
};

const reasonKeys: Record<CuratorNoteDeclineReason, MessageKey> = {
  not_insightful: "notes.reason.not_insightful",
  wrong_facts: "notes.reason.wrong_facts",
  tone: "notes.reason.tone",
  exhibition_not_suitable: "notes.reason.exhibition_not_suitable",
  other: "notes.reason.other",
};

/** Short staff-facing provenance label: the Edge Function (`claude`) or a session at the desk (`claude-desk`). */
function producerLabel(producer: string, t: (key: MessageKey) => string): string {
  if (producer === "claude") return t("notes.producer.api");
  if (producer === "claude-desk") return t("notes.producer.desk");
  return producer;
}

/** The editable text of one language of a draft. */
interface LanguageText {
  hook: string;
  insights: string[];
  tip: string;
}

interface EditorText {
  ko: LanguageText;
  en: LanguageText;
}

type Problem = { key: MessageKey; parameters?: Record<string, string | number> };

type NoteView = "waiting" | "approved";

/** The note in the review pane. `queueIndex` is its place among the waiting drafts, null when opened from Approved. */
interface OpenNote {
  exhibitionId: string;
  versionId: string;
  nameKo: string;
  nameEn: string;
  queueIndex: number | null;
}

const STACKED_LAYOUT = "(max-width: 760px)";

function textOf(version: CuratorNoteVersion): EditorText {
  return {
    ko: { hook: version.hookKo, insights: version.insightsKo.map((insight) => insight.text), tip: version.tipKo },
    en: { hook: version.hookEn, insights: version.insightsEn.map((insight) => insight.text), tip: version.tipEn },
  };
}

/** Only the fields whose text changed; insights keep their grounding and only swap the text. */
function editOf(version: CuratorNoteVersion, text: EditorText): CuratorNoteEdit {
  const edit: CuratorNoteEdit = {};
  if (text.ko.hook !== version.hookKo) edit.hookKo = text.ko.hook;
  if (text.en.hook !== version.hookEn) edit.hookEn = text.en.hook;
  if (text.ko.tip !== version.tipKo) edit.tipKo = text.ko.tip;
  if (text.en.tip !== version.tipEn) edit.tipEn = text.en.tip;
  const koChanged = version.insightsKo.some((insight, index) => insight.text !== text.ko.insights[index]);
  if (koChanged) edit.insightsKo = version.insightsKo.map((insight, index) => ({ ...insight, text: text.ko.insights[index] }));
  const enChanged = version.insightsEn.some((insight, index) => insight.text !== text.en.insights[index]);
  if (enChanged) edit.insightsEn = version.insightsEn.map((insight, index) => ({ ...insight, text: text.en.insights[index] }));
  return edit;
}

function waitedLabel(since: string, now: Date, locale: string): string {
  const minutes = Math.max(0, Math.round((now.getTime() - new Date(since).getTime()) / 60_000));
  const formatter = new Intl.RelativeTimeFormat(locale === "ko" ? "ko" : "en", { numeric: "always" });
  if (minutes < 60) return formatter.format(-minutes, "minute");
  if (minutes < 60 * 48) return formatter.format(-Math.round(minutes / 60), "hour");
  return formatter.format(-Math.round(minutes / 60 / 24), "day");
}

function factValue(value: unknown): string {
  if (Array.isArray(value)) {
    return value.map((item) => {
      if (item && typeof item === "object") {
        const row = item as Record<string, unknown>;
        return [row.name_ko, row.name_en].filter((part) => typeof part === "string" && part).join(" / ");
      }
      return String(item);
    }).join(" · ");
  }
  if (value && typeof value === "object") return JSON.stringify(value);
  return String(value ?? "");
}

/**
 * Staff review curator notes (spec 090 US1): the waiting drafts and the approved notes in a list on the left, the
 * open note beside it with its decisions in a bar that stays in view, the pictures readers get with it, the facts
 * it may cite, per-sentence unsupported markers that block approval, edit, approve, decline with a reason, and export.
 * Regenerate and on-demand drafting by exhibition id call the drafting function, so they render only when the
 * deployment says it exists (`draftingEnabled`, fail-closed); otherwise one line points to the desk flow.
 * Every write names the revision staff saw and is refused once it changed.
 */
export function CuratorNotesWorkspace({
  repository,
  draftingEnabled = false,
  onOpenExhibition,
  now = () => new Date(),
}: {
  repository: AdminCuratorNoteRepository;
  draftingEnabled?: boolean;
  /** Takes staff to the exhibition in Exhibitions, where gallery pictures are added under Media. */
  onOpenExhibition?: (exhibitionId: string) => void;
  now?: () => Date;
}) {
  const { t, formatDateTime, localized, locale } = useI18n();
  const [view, setView] = useState<NoteView>("waiting");
  const [queue, setQueue] = useState<CuratorNoteQueueItem[]>([]);
  const [approvedNotes, setApprovedNotes] = useState<ApprovedCuratorNote[]>([]);
  const [facts, setFacts] = useState<CuratorNoteFacts | null>(null);
  const [selected, setSelected] = useState<OpenNote | null>(null);
  const [opening, setOpening] = useState(false);
  const [leavingFor, setLeavingFor] = useState<OpenNote | "exhibition" | null>(null);
  const [text, setText] = useState<EditorText | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [notice, setNotice] = useState<MessageKey | null>(null);
  const [busy, setBusy] = useState(false);
  const [requestId, setRequestId] = useState("");
  const [declining, setDeclining] = useState(false);
  const [declineReason, setDeclineReason] = useState<CuratorNoteDeclineReason | null>(null);
  const [declineNote, setDeclineNote] = useState("");
  const [exporting, setExporting] = useState<CuratorNoteVersion | null>(null);
  const [roundup, setRoundup] = useState(false);
  const reviewPane = useRef<HTMLElement>(null);
  const openRequest = useRef(0);

  const versionId = selected?.versionId ?? null;
  const version = facts?.versions.find((candidate) => candidate.id === versionId) ?? null;
  const dirty = version !== null && text !== null && Object.keys(editOf(version, text)).length > 0;
  const blocked = version !== null && version.unsupportedRefs.length > 0;
  /** A write or a load is in flight: the draft and its decisions wait, so one note's result never lands on another. */
  const locked = busy || opening;
  const sourceChanged = version !== null && version.stale;

  const failure = useCallback((error: unknown, fallback: MessageKey): Problem => {
    if (error instanceof CuratorNoteNotStaffError) return { key: "notes.notStaff" };
    if (error instanceof CuratorNoteStaleError || error instanceof CuratorNoteInvalidTransitionError) return { key: "notes.stale" };
    if (error instanceof CuratorNoteSourceChangedError) return { key: "notes.sourceChanged" };
    if (error instanceof CuratorNoteUnsupportedError) return { key: "notes.unsupportedBlocks" };
    if (error instanceof CuratorNoteNotQualifyingError) return { key: "notes.notQualifying" };
    if (error instanceof CuratorNoteDraftingUnavailableError) return { key: "notes.draftingUnavailable" };
    return { key: fallback };
  }, []);

  const reloadQueue = useCallback(async () => {
    try {
      setQueue(await repository.listQueue());
    } catch (error) {
      setProblem(failure(error, "notes.loadFailed"));
    }
  }, [repository, failure]);

  const reloadApproved = useCallback(async () => {
    try {
      setApprovedNotes(await repository.listApproved());
    } catch (error) {
      setProblem(failure(error, "notes.loadFailed"));
    }
  }, [repository, failure]);

  /** A decision moves a note between the two lists, so both are read again. */
  const reloadLists = useCallback(async () => {
    await Promise.all([reloadQueue(), reloadApproved()]);
  }, [reloadQueue, reloadApproved]);

  useEffect(() => {
    void reloadLists();
  }, [reloadLists]);

  useEffect(() => {
    const pane = reviewPane.current;
    if (!pane || !versionId) return;
    pane.scrollTop = 0;
    // Under the stacked layout the pane sits below the list, so opening a note brings it into view.
    if (typeof window.matchMedia === "function" && window.matchMedia(STACKED_LAYOUT).matches) {
      pane.scrollIntoView?.({ block: "start" });
    }
  }, [versionId]);

  const loadFacts = async (exhibitionId: string, keepText: boolean) => {
    const loaded = await repository.get(exhibitionId);
    setFacts(loaded);
    if (!keepText) {
      const current = loaded.versions.find((candidate) => candidate.id === versionId);
      setText(current ? textOf(current) : null);
    }
    return loaded;
  };

  /** Only the latest request takes effect, so a slow load never replaces the note staff chose after it. */
  const open = async (note: OpenNote) => {
    const request = ++openRequest.current;
    setOpening(true);
    setProblem(null);
    setNotice(null);
    try {
      const loaded = await repository.get(note.exhibitionId);
      if (request !== openRequest.current) return;
      const current = loaded.versions.find((candidate) => candidate.id === note.versionId);
      if (!current) {
        // The version left the exhibition's history since the list was read, so the lists are stale.
        setProblem({ key: "notes.stale" });
        await reloadLists();
        return;
      }
      setFacts(loaded);
      setSelected(note);
      setText(textOf(current));
    } catch (error) {
      if (request === openRequest.current) setProblem(failure(error, "notes.loadFailed"));
    } finally {
      if (request === openRequest.current) setOpening(false);
    }
  };

  /** Leaving a draft drops unsaved edits, so staff confirm that first. Nothing opens while a write is in flight. */
  const requestOpen = (note: OpenNote) => {
    if (busy || note.versionId === versionId) return;
    if (dirty) setLeavingFor(note);
    else void open(note);
  };

  const requestOpenExhibition = () => {
    if (busy || !selected || !onOpenExhibition) return;
    if (dirty) setLeavingFor("exhibition");
    else onOpenExhibition(selected.exhibitionId);
  };

  const waitingNote = (item: CuratorNoteQueueItem, queueIndex: number): OpenNote => ({
    exhibitionId: item.exhibitionId,
    versionId: item.versionId,
    nameKo: item.nameKo,
    nameEn: item.nameEn,
    queueIndex,
  });

  /**
   * The waiting draft after the open one. A draft that was just decided has left the queue, and the draft that
   * followed it now holds its place.
   */
  const nextDraft = (() => {
    if (!selected || selected.queueIndex === null || queue.length === 0) return null;
    const at = queue.findIndex((item) => item.versionId === selected.versionId);
    const nextIndex = (at >= 0 ? at + 1 : selected.queueIndex) % queue.length;
    return nextIndex === at ? null : waitingNote(queue[nextIndex], nextIndex);
  })();

  const replaceVersion = (next: CuratorNoteVersion) => {
    setFacts((current) =>
      current
        ? { ...current, versions: current.versions.map((candidate) => (candidate.id === next.id ? next : candidate)) }
        : current
    );
    setText(textOf(next));
  };

  /** Runs a write; a stale or source-changed refusal reloads the facts so staff see the current version. */
  const act = async (
    work: () => Promise<CuratorNoteVersion | null>,
    done: MessageKey | null,
    fallback: MessageKey,
    afterwards?: () => Promise<void>,
  ) => {
    if (locked || !version) return;
    setBusy(true);
    setProblem(null);
    setNotice(null);
    try {
      const next = await work();
      if (next) replaceVersion(next);
      if (afterwards) await afterwards();
      setNotice(done);
    } catch (error) {
      setProblem(failure(error, fallback));
      if (
        error instanceof CuratorNoteStaleError ||
        error instanceof CuratorNoteInvalidTransitionError ||
        error instanceof CuratorNoteSourceChangedError
      ) {
        try {
          await loadFacts(version.exhibitionId, false);
        } catch (reloadError) {
          setProblem(failure(reloadError, "notes.loadFailed"));
        }
      }
    } finally {
      setBusy(false);
    }
  };

  const saveEdits = () =>
    act(
      () => repository.saveEdit(version!.id, editOf(version!, text!), version!.revision),
      "notes.saved",
      "notes.actionFailed",
    );

  const toggleUnsupported = (ref: string) =>
    act(
      () => repository.markSentence(version!.id, ref, !version!.unsupportedRefs.includes(ref), version!.revision),
      null,
      "notes.actionFailed",
    );

  const approve = () =>
    act(() => repository.approve(version!.id, version!.revision), "notes.approved", "notes.actionFailed", reloadLists);

  const confirmDecline = () => {
    if (!declineReason) return;
    const reason = declineReason;
    const note = declineNote.trim() || null;
    setDeclining(false);
    void act(() => repository.decline(version!.id, reason, note, version!.revision), "notes.declined", "notes.actionFailed", reloadLists);
  };

  const regenerate = () =>
    act(
      async () => {
        const result = await repository.requestDraft(version!.exhibitionId, "regenerate");
        if (result.outcome === "unusable") {
          setProblem({ key: "notes.draftUnusable", parameters: { code: result.code ?? "" } });
        }
        return null;
      },
      "notes.drafted",
      "notes.actionFailed",
      async () => {
        await reloadQueue();
        await loadFacts(version!.exhibitionId, false);
      },
    );

  const requestNew = async (event: FormEvent) => {
    event.preventDefault();
    const exhibitionId = requestId.trim();
    if (!exhibitionId || busy) return;
    setBusy(true);
    setProblem(null);
    setNotice(null);
    try {
      const result = await repository.requestDraft(exhibitionId, "single");
      if (result.outcome === "unusable") setProblem({ key: "notes.draftUnusable", parameters: { code: result.code ?? "" } });
      else setNotice("notes.drafted");
      await reloadQueue();
    } catch (error) {
      setProblem(failure(error, "notes.actionFailed"));
    } finally {
      setBusy(false);
    }
  };

  const updateText = (language: CuratorNoteLanguage, change: (current: LanguageText) => LanguageText) =>
    setText((current) => (current ? { ...current, [language]: change(current[language]) } : current));

  const renderLanguage = (language: CuratorNoteLanguage, insights: CuratorNoteInsight[]) => {
    if (!text || !version) return null;
    const current = text[language];
    if (language === "en" && !current.hook && insights.length === 0 && !current.tip) return null;
    const toggle = (ref: string) => {
      const marked = version.unsupportedRefs.includes(ref);
      return (
        <button
          type="button"
          className={`outlined-button${marked ? " is-active" : ""}`}
          aria-pressed={marked}
          disabled={locked || version.status !== "draft"}
          onClick={() => void toggleUnsupported(ref)}
        >
          {t(marked ? "notes.clearUnsupported" : "notes.markUnsupported")}
        </button>
      );
    };
    return (
      <fieldset className="curator-note-language" key={language}>
        <legend>{t(language === "ko" ? "notes.draft.ko" : "notes.draft.en")}</legend>
        <div className="field">
          <label htmlFor={`note-${language}-hook`}>{t("notes.hook")}</label>
          <textarea
            id={`note-${language}-hook`}
            rows={2}
            value={current.hook}
            disabled={locked || version.status !== "draft"}
            onChange={(event) => updateText(language, (value) => ({ ...value, hook: event.target.value }))}
          />
          {toggle(sentenceRef(language, "hook", 0))}
        </div>
        {insights.map((insight, index) => (
          <div className="field" key={index}>
            <label htmlFor={`note-${language}-insight-${index}`}>{t("notes.insight", { index: index + 1 })}</label>
            <textarea
              id={`note-${language}-insight-${index}`}
              rows={4}
              value={current.insights[index] ?? ""}
              disabled={locked || version.status !== "draft"}
              onChange={(event) =>
                updateText(language, (value) => ({
                  ...value,
                  insights: value.insights.map((item, at) => (at === index ? event.target.value : item)),
                }))}
            />
            <p className="muted curator-note-grounding">
              <span>{t("notes.grounding")}</span>
              {insight.grounding.map((key) => <code key={key}>{key}</code>)}
            </p>
            {toggle(sentenceRef(language, "insight", index))}
          </div>
        ))}
        <div className="field">
          <label htmlFor={`note-${language}-tip`}>{t("notes.tip")}</label>
          <textarea
            id={`note-${language}-tip`}
            rows={2}
            value={current.tip}
            disabled={locked || version.status !== "draft"}
            onChange={(event) => updateText(language, (value) => ({ ...value, tip: event.target.value }))}
          />
          {toggle(sentenceRef(language, "tip", 0))}
        </div>
      </fieldset>
    );
  };

  return (
    <main className="workspace submission-workspace curator-notes-workspace">
      <header className="workspace-header">
        <div className="workspace-title-row">
          <h1>{t("notes.title")}</h1>
          <button type="button" className="outlined-button" onClick={() => setRoundup(true)}>
            {t("notes.roundup.open")}
          </button>
        </div>
        <p className="workspace-subtitle">{t("notes.subtitle")}</p>
        {draftingEnabled ? (
          <form className="workspace-toolbar" onSubmit={(event) => void requestNew(event)}>
            <label className="search-field">
              <span className="visually-hidden">{t("notes.requestLabel")}</span>
              <input
                type="text"
                value={requestId}
                placeholder={t("notes.requestPlaceholder")}
                aria-label={t("notes.requestLabel")}
                onChange={(event) => setRequestId(event.target.value)}
              />
            </label>
            <button className="black-button" type="submit" disabled={busy || !requestId.trim()}>
              {t(busy ? "notes.requesting" : "notes.requestDraft")}
            </button>
          </form>
        ) : (
          <p className="muted">{t("notes.deskOnly")}</p>
        )}
        {problem && (
          <div className="inline-notice" role="alert">
            <span>{t(problem.key, problem.parameters)}</span>
            <button type="button" className="outlined-button" onClick={() => setProblem(null)}>
              {t("common.dismiss")}
            </button>
          </div>
        )}
        {notice && <p role="status">{t(notice)}</p>}
      </header>

      <div className="curator-notes-body">
        <div className="curator-notes-lists">
          <div className="route-review-tabs" role="tablist" aria-label={t("notes.views")}>
            {(
              [
                ["waiting", t("notes.tab.waiting", { count: queue.length })],
                ["approved", t("notes.tab.approved", { count: approvedNotes.length })],
              ] as const
            ).map(([key, label]) => (
              <button
                key={key}
                type="button"
                role="tab"
                id={`curator-notes-tab-${key}`}
                aria-selected={view === key}
                aria-controls="curator-notes-list"
                className={view === key ? "black-button" : "outlined-button"}
                onClick={() => setView(key)}
              >
                {label}
              </button>
            ))}
          </div>
          <div
            className="route-review-list"
            role="tabpanel"
            id="curator-notes-list"
            aria-labelledby={`curator-notes-tab-${view}`}
          >
            {view === "waiting" && (queue.length === 0 ? <p className="muted">{t("notes.queue.empty")}</p> : (
              <ul aria-label={t("notes.queue.title")}>
                {queue.map((item, index) => (
                  <li key={item.versionId}>
                    <button
                      type="button"
                      className={`route-review-row${item.versionId === versionId ? " is-active" : ""}`}
                      aria-current={item.versionId === versionId ? "true" : undefined}
                      onClick={() => requestOpen(waitingNote(item, index))}
                    >
                      <strong>{localized(item.nameKo, item.nameEn)}</strong>
                      {item.isFeatured && <span className="submission-status status-accepted">{t("notes.queue.featured")}</span>}
                      <span className="muted">
                        {t("notes.queue.meta", {
                          version: item.versionNumber,
                          prompt: item.promptVersion,
                          waited: waitedLabel(item.generatedAt, now(), locale),
                        })}
                      </span>
                      {item.edited && <span className="submission-status">{t("notes.queue.edited")}</span>}
                      {item.stale && <span className="submission-status">{t("notes.queue.stale")}</span>}
                      {item.unsupportedCount > 0 && (
                        <span className="submission-status status-rejected">
                          {t("notes.queue.unsupported", { count: item.unsupportedCount })}
                        </span>
                      )}
                    </button>
                  </li>
                ))}
              </ul>
            ))}
            {view === "approved" && (approvedNotes.length === 0 ? <p className="muted">{t("notes.approved.empty")}</p> : (
              <ul aria-label={t("notes.approved.title")}>
                {approvedNotes.map((note) => (
                  <li key={note.versionId}>
                    <button
                      type="button"
                      className={`route-review-row${note.versionId === versionId ? " is-active" : ""}`}
                      aria-current={note.versionId === versionId ? "true" : undefined}
                      onClick={() =>
                        requestOpen({
                          exhibitionId: note.exhibitionId,
                          versionId: note.versionId,
                          nameKo: note.nameKo,
                          nameEn: note.nameEn,
                          queueIndex: null,
                        })}
                    >
                      <strong>{localized(note.nameKo, note.nameEn)}</strong>
                      <span className="muted">{localized(note.venueNameKo, note.venueNameEn)}</span>
                      <span className="muted">
                        {t("notes.approved.meta", { version: note.versionNumber, approved: formatDateTime(note.approvedAt) })}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            ))}
          </div>
        </div>

        <section className="curator-note-pane" aria-label={t("notes.review")} ref={reviewPane}>
          {selected && facts && version && text ? (
            <>
              <header className="curator-note-pane-header">
                <div className="curator-note-pane-title">
                  <h2>{localized(selected.nameKo, selected.nameEn)}</h2>
                  <p className="muted">
                    <span className={`submission-status${statusChipClass[version.status]}`}>{t(statusKeys[version.status])}</span>{" "}
                    {t("notes.meta", {
                      version: version.versionNumber,
                      producer: producerLabel(version.producer, t),
                      model: version.model,
                      prompt: version.promptVersion,
                      kind: version.requestKind,
                      generated: formatDateTime(version.generatedAt),
                    })}
                    {version.edited && <>{" "}<span className="submission-status">{t("notes.queue.edited")}</span></>}
                  </p>
                </div>
                <div className="curator-note-actions" role="group" aria-label={t("notes.actions")}>
                  {version.status === "draft" && (
                    <>
                      <button className="outlined-button" type="button" disabled={locked || !dirty} onClick={() => void saveEdits()}>
                        {t("notes.save")}
                      </button>
                      <button
                        className="outlined-button"
                        type="button"
                        disabled={locked}
                        onClick={() => {
                          setDeclineReason(null);
                          setDeclineNote("");
                          setDeclining(true);
                        }}
                      >
                        {t("notes.decline")}
                      </button>
                      {draftingEnabled && (
                        <button className="outlined-button" type="button" disabled={locked} onClick={() => void regenerate()}>
                          {t("notes.regenerate")}
                        </button>
                      )}
                      <button
                        className="black-button"
                        type="button"
                        disabled={locked || blocked || sourceChanged || dirty}
                        onClick={() => void approve()}
                      >
                        {t("notes.approve")}
                      </button>
                    </>
                  )}
                  {version.status === "approved" && (
                    <button type="button" className="outlined-button" disabled={locked} onClick={() => setExporting(version)}>
                      {t("notes.export.open")}
                    </button>
                  )}
                  {nextDraft && (
                    <button type="button" className="outlined-button" disabled={busy} onClick={() => requestOpen(nextDraft)}>
                      {t("notes.next")}
                    </button>
                  )}
                </div>
                {blocked && <p className="inline-notice">{t("notes.unsupportedBlocks")}</p>}
                {sourceChanged && version.status === "draft" && <p className="inline-notice">{t("notes.sourceChanged")}</p>}
              </header>

              <NotePictures
                facts={facts}
                insightCount={version.insightsKo.length}
                onOpenExhibition={onOpenExhibition && requestOpenExhibition}
              />

              <div className="curator-note-review">
                <div className="curator-note-facts">
                  <h3>{t("notes.facts")}</h3>
                  {facts.packet ? (
                    <dl>
                      {Object.entries(facts.packet).map(([key, value]) => (
                        <div key={key}>
                          <dt><code>{key}</code></dt>
                          <dd>{factValue(value)}</dd>
                        </div>
                      ))}
                    </dl>
                  ) : <p className="muted">{t("notes.factsMissing")}</p>}
                </div>
                <div className="curator-note-draft">
                  {renderLanguage("ko", version.insightsKo)}
                  {renderLanguage("en", version.insightsEn)}
                  <h3>{t("notes.versions")}</h3>
                  <ul className="curator-note-versions">
                    {facts.versions.map((candidate) => (
                      <li key={candidate.id}>
                        <span className={`submission-status${statusChipClass[candidate.status]}`}>{t(statusKeys[candidate.status])}</span>{" "}
                        v{candidate.versionNumber} · {formatDateTime(candidate.generatedAt)}
                        {candidate.reviewerNameKo && (
                          <>{" "}· {t("notes.reviewedBy", { name: localized(candidate.reviewerNameKo, candidate.reviewerNameEn) })}</>
                        )}
                        {candidate.rejectionCode && <>{" "}· <code>{candidate.rejectionCode}</code></>}
                        {candidate.status === "approved" && candidate.id !== version.id && (
                          <>
                            {" "}
                            <button type="button" className="outlined-button" disabled={busy} onClick={() => setExporting(candidate)}>
                              {t("notes.export.open")}
                            </button>
                          </>
                        )}
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            </>
          ) : <p className="muted curator-note-pane-empty">{t("notes.select")}</p>}
        </section>
      </div>

      {exporting && facts && (
        <CarouselExportDialog
          version={exporting}
          facts={facts}
          onClose={() => setExporting(null)}
          onExported={async (language, slideCount) => {
            try {
              await repository.recordExport(exporting.id, language, slideCount);
            } catch (error) {
              setProblem(failure(error, "notes.actionFailed"));
            }
          }}
        />
      )}

      {roundup && <RoundupExportDialog repository={repository} onClose={() => setRoundup(false)} />}

      {leavingFor && (
        <DialogFrame
          title={t("notes.discardTitle")}
          role="alertdialog"
          onClose={() => setLeavingFor(null)}
          footer={
            <>
              <button type="button" className="outlined-button" onClick={() => setLeavingFor(null)}>
                {t("notes.discardKeep")}
              </button>
              <button
                type="button"
                className="black-button"
                disabled={busy}
                onClick={() => {
                  const next = leavingFor;
                  setLeavingFor(null);
                  if (next === "exhibition") onOpenExhibition?.(selected!.exhibitionId);
                  else void open(next);
                }}
              >
                {t("notes.discardConfirm")}
              </button>
            </>
          }
        >
          <p>{t("notes.discardBody")}</p>
        </DialogFrame>
      )}

      {declining && version && (
        <DialogFrame
          title={t("notes.declineTitle")}
          role="alertdialog"
          onClose={() => setDeclining(false)}
          footer={
            <>
              <button type="button" className="outlined-button" onClick={() => setDeclining(false)}>
                {t("common.cancel")}
              </button>
              <button type="button" className="black-button" disabled={!declineReason} onClick={confirmDecline}>
                {t("notes.decline")}
              </button>
            </>
          }
        >
          <p>{t("notes.declineBody")}</p>
          <fieldset className="route-decline-reasons">
            <legend>{t("notes.declineReason")}</legend>
            {CURATOR_NOTE_DECLINE_REASONS.map((reason) => (
              <label className="radio-row" key={reason}>
                <input
                  type="radio"
                  name="curator-note-decline-reason"
                  value={reason}
                  checked={declineReason === reason}
                  onChange={() => setDeclineReason(reason)}
                />
                {t(reasonKeys[reason])}
              </label>
            ))}
          </fieldset>
          <div className="field">
            <label htmlFor="curator-note-decline-note">{t("notes.declineNote")}</label>
            <textarea
              id="curator-note-decline-note"
              rows={3}
              maxLength={500}
              value={declineNote}
              onChange={(event) => setDeclineNote(event.target.value)}
            />
          </div>
        </DialogFrame>
      )}
    </main>
  );
}

/**
 * The pictures readers get with the note, as the app places them: the cover, then one gallery picture above
 * each insight. Staff see here whether the note will be read with pictures before they approve it.
 */
function NotePictures({
  facts,
  insightCount,
  onOpenExhibition,
}: {
  facts: CuratorNoteFacts;
  insightCount: number;
  onOpenExhibition?: () => void;
}) {
  const { t, localized } = useI18n();
  const pictures = notePictures(facts.coverImageUrl, facts.images, insightCount);
  const hasGallery = pictures.placed.length + pictures.unused.length > 0;
  const missing = pictures.withoutPicture.length > 0;
  const figure = (image: CuratorNoteImage, label: string, flagMissingAlt: boolean) => {
    const alt = localized(image.altKo, image.altEn);
    return (
      <li key={`${label}:${image.url}`}>
        <figure>
          <a href={image.url} target="_blank" rel="noreferrer">
            {/* The picture is the link's only content, so it is named by its place when it has no alt text. */}
            <img src={image.url} alt={alt || label} loading="lazy" />
          </a>
          <figcaption>
            <span className="curator-note-picture-label">{label}</span>
            {image.credit && <span className="muted">{image.credit}</span>}
            {flagMissingAlt && !alt && <span className="submission-status status-rejected">{t("notes.pictures.noAlt")}</span>}
          </figcaption>
        </figure>
      </li>
    );
  };
  return (
    <section className="curator-note-pictures" aria-label={t("notes.pictures")}>
      <h3>{t("notes.pictures")}</h3>
      <ul>
        {pictures.cover
          ? figure(pictures.cover, t("notes.pictures.cover"), false)
          : <li className="muted">{t("notes.pictures.noCover")}</li>}
        {pictures.placed.map(({ image, insight }) => figure(image, t("notes.pictures.above", { index: insight }), true))}
        {pictures.unused.map((image) => figure(image, t("notes.pictures.galleryOnly"), true))}
      </ul>
      {hasGallery
        ? missing && (
          <p className="muted">{t("notes.pictures.missing", { list: pictures.withoutPicture.join(", ") })}</p>
        )
        : <p className="inline-notice">{t("notes.pictures.none")}</p>}
      {(missing || onOpenExhibition) && (
        <p className="muted curator-note-pictures-how">
          {missing && <span>{t("notes.pictures.howTo")}</span>}
          {onOpenExhibition && (
            <button type="button" className="outlined-button" onClick={onOpenExhibition}>
              {t(missing ? "notes.pictures.open" : "notes.pictures.edit")}
            </button>
          )}
        </p>
      )}
    </section>
  );
}
