import { useCallback, useEffect, useState, type FormEvent } from "react";
import { DialogFrame } from "./Dialogs";
import { CarouselExportDialog } from "./CarouselExportDialog";
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
  type CuratorNoteDeclineReason,
  type CuratorNoteEdit,
  type CuratorNoteFacts,
  type CuratorNoteInsight,
  type CuratorNoteLanguage,
  type CuratorNoteQueueItem,
  type CuratorNoteStatus,
  type CuratorNoteVersion,
} from "../repositories/AdminCuratorNoteRepository";

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
 * Staff review curator notes (spec 090 US1): the queue of drafts, each draft beside the facts it may cite,
 * per-sentence unsupported markers that block approval, edit, approve, decline with a reason, and export.
 * Regenerate and on-demand drafting by exhibition id call the drafting function, so they render only when the
 * deployment says it exists (`draftingEnabled`, fail-closed); otherwise one line points to the desk flow.
 * Every write names the revision staff saw and is refused once it changed.
 */
export function CuratorNotesWorkspace({
  repository,
  draftingEnabled = false,
  now = () => new Date(),
}: {
  repository: AdminCuratorNoteRepository;
  draftingEnabled?: boolean;
  now?: () => Date;
}) {
  const { t, formatDateTime, localized, locale } = useI18n();
  const [queue, setQueue] = useState<CuratorNoteQueueItem[]>([]);
  const [facts, setFacts] = useState<CuratorNoteFacts | null>(null);
  const [versionId, setVersionId] = useState<string | null>(null);
  const [text, setText] = useState<EditorText | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [notice, setNotice] = useState<MessageKey | null>(null);
  const [busy, setBusy] = useState(false);
  const [requestId, setRequestId] = useState("");
  const [declining, setDeclining] = useState(false);
  const [declineReason, setDeclineReason] = useState<CuratorNoteDeclineReason | null>(null);
  const [declineNote, setDeclineNote] = useState("");
  const [exporting, setExporting] = useState<CuratorNoteVersion | null>(null);

  const version = facts?.versions.find((candidate) => candidate.id === versionId) ?? null;
  const dirty = version !== null && text !== null && Object.keys(editOf(version, text)).length > 0;
  const blocked = version !== null && version.unsupportedRefs.length > 0;
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

  useEffect(() => {
    void reloadQueue();
  }, [reloadQueue]);

  const loadFacts = async (exhibitionId: string, keepText: boolean) => {
    const loaded = await repository.get(exhibitionId);
    setFacts(loaded);
    if (!keepText) {
      const current = loaded.versions.find((candidate) => candidate.id === versionId);
      setText(current ? textOf(current) : null);
    }
    return loaded;
  };

  const open = async (item: CuratorNoteQueueItem) => {
    setProblem(null);
    setNotice(null);
    try {
      const loaded = await repository.get(item.exhibitionId);
      const current = loaded.versions.find((candidate) => candidate.id === item.versionId) ?? null;
      setFacts(loaded);
      setVersionId(item.versionId);
      setText(current ? textOf(current) : null);
    } catch (error) {
      setProblem(failure(error, "notes.loadFailed"));
    }
  };

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
    if (busy || !version) return;
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
    act(() => repository.approve(version!.id, version!.revision), "notes.approved", "notes.actionFailed", reloadQueue);

  const confirmDecline = () => {
    if (!declineReason) return;
    const reason = declineReason;
    const note = declineNote.trim() || null;
    setDeclining(false);
    void act(() => repository.decline(version!.id, reason, note, version!.revision), "notes.declined", "notes.actionFailed", reloadQueue);
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
          disabled={busy || version.status !== "draft"}
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
            disabled={busy || version.status !== "draft"}
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
              disabled={busy || version.status !== "draft"}
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
            disabled={busy || version.status !== "draft"}
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

      <section className="route-review-list" aria-label={t("notes.queue.title")}>
        {queue.length === 0 ? <p className="muted">{t("notes.queue.empty")}</p> : (
          <ul>
            {queue.map((item) => (
              <li key={item.versionId}>
                <button
                  type="button"
                  className={`route-review-row${item.versionId === versionId ? " is-active" : ""}`}
                  onClick={() => void open(item)}
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
        )}
      </section>

      {facts && version && text && (
        <section className="curator-note-review" aria-labelledby="curator-note-review-title">
          <h2 id="curator-note-review-title" className="visually-hidden">{t("notes.title")}</h2>
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
            {renderLanguage("ko", version.insightsKo)}
            {renderLanguage("en", version.insightsEn)}
            {blocked && <p className="inline-notice">{t("notes.unsupportedBlocks")}</p>}
            {sourceChanged && version.status === "draft" && <p className="inline-notice">{t("notes.sourceChanged")}</p>}
            {version.status === "draft" && (
              <div className="submission-review-actions">
                <button className="outlined-button" type="button" disabled={busy || !dirty} onClick={() => void saveEdits()}>
                  {t("notes.save")}
                </button>
                <button
                  className="black-button"
                  type="button"
                  disabled={busy || blocked || sourceChanged}
                  onClick={() => void approve()}
                >
                  {t("notes.approve")}
                </button>
                <button
                  className="outlined-button"
                  type="button"
                  disabled={busy}
                  onClick={() => {
                    setDeclineReason(null);
                    setDeclineNote("");
                    setDeclining(true);
                  }}
                >
                  {t("notes.decline")}
                </button>
                {draftingEnabled && (
                  <button className="outlined-button" type="button" disabled={busy} onClick={() => void regenerate()}>
                    {t("notes.regenerate")}
                  </button>
                )}
              </div>
            )}
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
                  {candidate.status === "approved" && (
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
        </section>
      )}

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
