import { useCallback, useEffect, useState } from "react";
import { DialogFrame } from "./Dialogs";
import { useI18n, type MessageKey } from "../i18n";
import {
  THOUGHT_REJECTION_REASONS,
  THOUGHT_REPORT_REASONS,
  ThoughtInvalidTransitionError,
  ThoughtModerationNotStaffError,
  ThoughtNotFoundError,
  ThoughtReviewOwnError,
  ThoughtReviewStaleError,
  type AdminThoughtRepository,
  type PendingThought,
  type ReportedThought,
  type ThoughtRejectionReason,
  type ThoughtReportReason,
} from "../repositories/AdminThoughtRepository";

const rejectionReasonKeys: Record<ThoughtRejectionReason, MessageKey> = {
  not_about_exhibition: "thoughts.reason.not_about_exhibition",
  promotional: "thoughts.reason.promotional",
  inappropriate_language: "thoughts.reason.inappropriate_language",
  personal_information: "thoughts.reason.personal_information",
  other: "thoughts.reason.other",
};

const reportReasonKeys: Record<ThoughtReportReason, MessageKey> = {
  inappropriate: "thoughts.reportReason.inappropriate",
  promotional: "thoughts.reportReason.promotional",
  not_about_exhibition: "thoughts.reportReason.not_about_exhibition",
  personal_information: "thoughts.reportReason.personal_information",
  other: "thoughts.reportReason.other",
};

/** Staff decide every report within 48 hours of its sending (owner decision 6). */
const REPORT_WINDOW_HOURS = 48;
const HOUR_MS = 3_600_000;

type View = "pending" | "reported";
type Notice = { key: MessageKey; retry?: boolean };
type ReasonRequest = { kind: "reject"; item: PendingThought } | { kind: "takeDown"; item: ReportedThought };

/**
 * Staff review the 감상 visitors asked to publish and act on reader reports (spec 103 US5). Both lists are oldest
 * first: pending by request, reported by its oldest open report (owner decision 9). A decision names the text staff
 * saw, so an author's edit in the meantime is refused and the list read again. Only the text, its author, its
 * exhibition and times are shown; a visit's feeling, tags, date and unpublished note never reach this screen.
 */
export function ThoughtModerationWorkspace({
  repository,
  now = () => new Date(),
}: {
  repository: AdminThoughtRepository;
  now?: () => Date;
}) {
  const { t, formatDateTime, localized } = useI18n();
  const [view, setView] = useState<View>("pending");
  const [pending, setPending] = useState<PendingThought[]>([]);
  const [reported, setReported] = useState<ReportedThought[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [reasonRequest, setReasonRequest] = useState<ReasonRequest | null>(null);

  /**
   * Reads both lists. A failure keeps what was shown before and says so; before the review functions exist (Admin
   * deployed ahead of its migration) this is where the section stops, with a retry rather than a crash.
   */
  const reload = useCallback(async (): Promise<boolean> => {
    setLoading(true);
    try {
      const [waiting, reports] = await Promise.all([repository.listPending(), repository.listReported()]);
      setPending(waiting);
      setReported(reports);
      setLoaded(true);
      return true;
    } catch (error) {
      setNotice(
        error instanceof ThoughtModerationNotStaffError
          ? { key: "thoughts.notStaff" }
          : { key: "thoughts.loadFailed", retry: true },
      );
      return false;
    } finally {
      setLoading(false);
    }
  }, [repository]);

  useEffect(() => {
    void reload();
  }, [reload]);

  /**
   * Runs one decision, then reads the lists again; a refusal reads them too and says why nothing changed. A refusal
   * because the 감상 is the reviewer's own changes nothing on the server, so the lists stay as they are.
   */
  const act = async (action: () => Promise<void>) => {
    if (busy) return;
    setBusy(true);
    setNotice(null);
    try {
      await action();
      await reload();
    } catch (error) {
      if (error instanceof ThoughtReviewOwnError) {
        setNotice({ key: "thoughts.ownNote" });
        return;
      }
      const refusal = refusalKey(error);
      if (refusal) {
        if (await reload()) setNotice({ key: refusal });
      } else {
        setNotice({ key: error instanceof ThoughtModerationNotStaffError ? "thoughts.notStaff" : "thoughts.actionFailed" });
      }
    } finally {
      setBusy(false);
    }
  };

  const confirmReason = (reason: ThoughtRejectionReason) => {
    const request = reasonRequest;
    setReasonRequest(null);
    if (!request) return;
    if (request.kind === "reject") {
      void act(() => repository.decide(request.item.id, { kind: "reject", reason }, request.item.requestedAt));
    } else {
      void act(() =>
        repository.resolveReports(request.item.id, { kind: "upheld", reason }, request.item.approvedAt),
      );
    }
  };

  const list: (PendingThought | ReportedThought)[] = view === "pending" ? pending : reported;
  /** The chosen 감상, or the oldest one once the chosen one left the list. */
  const selected = list.find((item) => item.id === selectedId) ?? list[0] ?? null;
  const author = (name: string | null) => name ?? t("thoughts.noAuthorName");
  const exhibition = (item: PendingThought | ReportedThought) =>
    localized(item.exhibitionNameKo, item.exhibitionNameEn, t("thoughts.exhibitionUnavailable"));
  const clockLabel = (item: ReportedThought) => {
    const clock = reportClock(item.firstReportedAt, now());
    return clock.overdue
      ? { text: t("thoughts.overdue"), overdue: true }
      : { text: t("thoughts.hoursLeft", { hours: clock.hoursLeft }), overdue: false };
  };
  const reportCount = (count: number) =>
    t(count === 1 ? "thoughts.reportCountOne" : "thoughts.reportCount", { count });
  const reasonSummary = (item: ReportedThought) =>
    THOUGHT_REPORT_REASONS.filter((reason) => item.reasons[reason])
      .map((reason) => `${t(reportReasonKeys[reason])} ${item.reasons[reason]}`)
      .join(" · ");

  return (
    <main className="workspace submission-workspace curator-notes-workspace thought-moderation-workspace">
      <header className="workspace-header">
        <div className="workspace-title-row">
          <h1>{t("thoughts.title")}</h1>
        </div>
        <p className="workspace-subtitle">{t("thoughts.subtitle")}</p>
        {notice && (
          <div className="inline-notice" role="status">
            <span>{t(notice.key)}</span>
            {notice.retry && (
              <button
                className="outlined-button"
                type="button"
                disabled={loading}
                onClick={() => {
                  setNotice(null);
                  void reload();
                }}
              >
                {t("thoughts.retry")}
              </button>
            )}
          </div>
        )}
      </header>

      <div className="curator-notes-body">
        <div className="curator-notes-lists">
          <div className="route-review-tabs" role="tablist" aria-label={t("thoughts.views")}>
            {(
              [
                ["pending", t("thoughts.tab.pending", { count: pending.length })],
                ["reported", t("thoughts.tab.reported", { count: reported.length })],
              ] as const
            ).map(([key, label]) => (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={view === key}
                className={view === key ? "black-button" : "outlined-button"}
                onClick={() => {
                  setView(key);
                  // Authors and other staff change these lists, so choosing a tab reads them again.
                  void reload();
                }}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="route-review-list" role="tabpanel">
            {!loaded ? (
              loading && <p className="muted">{t("thoughts.loading")}</p>
            ) : list.length === 0 ? (
              <p className="muted">{t(view === "pending" ? "thoughts.pending.empty" : "thoughts.reported.empty")}</p>
            ) : (
              <ul aria-label={t(view === "pending" ? "thoughts.pending.title" : "thoughts.reported.title")}>
                {list.map((item) => (
                  <li key={item.id}>
                    <button
                      type="button"
                      className={`route-review-row${item.id === selected?.id ? " is-active" : ""}`}
                      aria-current={item.id === selected?.id ? "true" : undefined}
                      onClick={() => setSelectedId(item.id)}
                    >
                      <strong>{item.content}</strong>
                      {"requestedAt" in item ? (
                        <>
                          <span className="muted">
                            {[
                              author(item.authorDisplayName),
                              exhibition(item),
                              t("thoughts.asked", { time: formatDateTime(item.requestedAt) }),
                            ].join(" · ")}
                          </span>
                          {item.wasApprovedBefore && <span className="submission-status">{t("thoughts.edited")}</span>}
                        </>
                      ) : (
                        <>
                          <span className="muted">
                            {[reportCount(item.openCount), exhibition(item)].join(" · ")}
                          </span>
                          <ReportClock {...clockLabel(item)} />
                        </>
                      )}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>

        <section className="curator-note-pane" aria-label={t("thoughts.selected")}>
          {!selected ? (
            <p className="muted curator-note-pane-empty">{loaded ? t("thoughts.select") : ""}</p>
          ) : (
            <>
              <header className="curator-note-pane-header">
                <div className="curator-note-pane-title">
                  {"requestedAt" in selected
                    ? selected.wasApprovedBefore && <span className="submission-status">{t("thoughts.edited")}</span>
                    : <ReportClock {...clockLabel(selected)} />}
                </div>
                {/* The decision staff should reach for first is the solid button; the one that hides text is outlined. */}
                <div className="curator-note-actions">
                  {"requestedAt" in selected ? (
                    <>
                      <button
                        className="black-button"
                        type="button"
                        disabled={busy}
                        onClick={() =>
                          void act(() => repository.decide(selected.id, { kind: "approve" }, selected.requestedAt))
                        }
                      >
                        {t("thoughts.approve")}
                      </button>
                      <button
                        className="outlined-button"
                        type="button"
                        disabled={busy}
                        onClick={() => setReasonRequest({ kind: "reject", item: selected })}
                      >
                        {t("thoughts.reject")}
                      </button>
                    </>
                  ) : (
                    <>
                      <button
                        className="black-button"
                        type="button"
                        disabled={busy}
                        onClick={() =>
                          void act(() =>
                            repository.resolveReports(selected.id, { kind: "dismissed" }, selected.approvedAt),
                          )
                        }
                      >
                        {t("thoughts.keep")}
                      </button>
                      <button
                        className="outlined-button"
                        type="button"
                        disabled={busy}
                        onClick={() => setReasonRequest({ kind: "takeDown", item: selected })}
                      >
                        {t("thoughts.takeDown")}
                      </button>
                    </>
                  )}
                </div>
              </header>
              <div className="thought-moderation-body">
                <p className="thought-moderation-text">{selected.content}</p>
                <dl className="thought-moderation-facts">
                  <div>
                    <dt>{t("thoughts.author")}</dt>
                    <dd>{author(selected.authorDisplayName)}</dd>
                  </div>
                  <div>
                    <dt>{t("thoughts.exhibition")}</dt>
                    <dd>{exhibition(selected)}</dd>
                  </div>
                </dl>
                {"requestedAt" in selected ? (
                  <p className="muted">{t("thoughts.asked", { time: formatDateTime(selected.requestedAt) })}</p>
                ) : (
                  <>
                    <p>{reportCount(selected.openCount)}</p>
                    <p className="muted">{reasonSummary(selected)}</p>
                    <p className="muted">{t("thoughts.firstReport", { time: formatDateTime(selected.firstReportedAt) })}</p>
                  </>
                )}
              </div>
            </>
          )}
        </section>
      </div>

      {reasonRequest && (
        <ReasonDialog
          kind={reasonRequest.kind}
          onCancel={() => setReasonRequest(null)}
          onConfirm={confirmReason}
        />
      )}
    </main>
  );
}

function ReportClock({ text, overdue }: { text: string; overdue: boolean }) {
  return <span className={`submission-status${overdue ? " status-rejected" : ""}`}>{text}</span>;
}

/** Asks for exactly one reason from the fixed list; the author sees it. */
function ReasonDialog({
  kind,
  onCancel,
  onConfirm,
}: {
  kind: ReasonRequest["kind"];
  onCancel: () => void;
  onConfirm: (reason: ThoughtRejectionReason) => void;
}) {
  const { t } = useI18n();
  const [reason, setReason] = useState<ThoughtRejectionReason | null>(null);
  const rejecting = kind === "reject";
  return (
    <DialogFrame
      title={t(rejecting ? "thoughts.rejectTitle" : "thoughts.takeDownTitle")}
      onClose={onCancel}
      footer={
        <>
          <button className="outlined-button" type="button" onClick={onCancel}>
            {t("common.cancel")}
          </button>
          <button
            className="black-button"
            type="button"
            disabled={reason === null}
            onClick={() => reason && onConfirm(reason)}
          >
            {t(rejecting ? "thoughts.rejectConfirm" : "thoughts.takeDownConfirm")}
          </button>
        </>
      }
    >
      <p>{t(rejecting ? "thoughts.rejectBody" : "thoughts.takeDownBody")}</p>
      <fieldset className="route-decline-reasons">
        <legend>{t("thoughts.reasonLegend")}</legend>
        {THOUGHT_REJECTION_REASONS.map((option) => (
          <label key={option} className="radio-row">
            <input type="radio" name="thought-reason" checked={reason === option} onChange={() => setReason(option)} />
            {t(rejectionReasonKeys[option])}
          </label>
        ))}
      </fieldset>
    </DialogFrame>
  );
}

/** Whole hours left until 48 hours after the oldest open report, or overdue once they have passed. */
function reportClock(
  firstReportedAt: string,
  now: Date,
): { overdue: true } | { overdue: false; hoursLeft: number } {
  const leftMs = REPORT_WINDOW_HOURS * HOUR_MS - (now.getTime() - new Date(firstReportedAt).getTime());
  if (leftMs <= 0) return { overdue: true };
  return { overdue: false, hoursLeft: Math.ceil(leftMs / HOUR_MS) };
}

/** The notice for a decision the database refused because the 감상 moved on; the lists are read again after it. */
function refusalKey(error: unknown): MessageKey | null {
  if (error instanceof ThoughtReviewStaleError) return "thoughts.stale";
  if (error instanceof ThoughtInvalidTransitionError) return "thoughts.alreadyDecided";
  if (error instanceof ThoughtNotFoundError) return "thoughts.notFound";
  return null;
}
