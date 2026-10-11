import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { DialogFrame } from "./Dialogs";
import { useI18n, type MessageKey } from "../i18n";
import {
  RouteListingInvalidTransitionError,
  RouteListingStaleError,
  RouteModerationNotFoundError,
  RouteModerationNotStaffError,
  parseRouteReference,
  routeModerationStatus,
  type AdminRouteRepository,
  type AdminRouteReviewRepository,
  type ModeratedRoute,
  type ReportedRoute,
  type RouteDeclineReason,
  type RouteListingDecision,
  type RouteListingQueueItem,
  type RouteListingState,
  type RouteModerationStatus,
  type RouteReportReason,
} from "../repositories/AdminRouteRepository";

const statusKeys: Record<RouteModerationStatus, MessageKey> = {
  public: "routes.status.public",
  private: "routes.status.private",
  revoked: "routes.status.revoked",
};

const listingKeys: Record<RouteListingState, MessageKey> = {
  unlisted: "routes.listing.unlisted",
  requested: "routes.listing.requested",
  approved: "routes.listing.approved",
  declined: "routes.listing.declined",
  removed: "routes.listing.removed",
};

/** Chip variants that exist in styles.css: submitted (waiting), accepted (live), rejected (not shown). */
const listingChipClass: Record<RouteListingState, string> = {
  unlisted: "",
  requested: " status-submitted",
  approved: " status-accepted",
  declined: " status-rejected",
  removed: " status-rejected",
};

const declineReasons: readonly RouteDeclineReason[] = ["name_or_description", "promotional", "composition", "other"];
const declineReasonKeys: Record<RouteDeclineReason, MessageKey> = {
  name_or_description: "routes.reason.name_or_description",
  promotional: "routes.reason.promotional",
  composition: "routes.reason.composition",
  other: "routes.reason.other",
};
const reportReasons: readonly RouteReportReason[] = ["inappropriate", "promotional", "wrong_information", "other"];
const reportReasonKeys: Record<RouteReportReason, MessageKey> = {
  inappropriate: "routes.reportReason.inappropriate",
  promotional: "routes.reportReason.promotional",
  wrong_information: "routes.reportReason.wrong_information",
  other: "routes.reportReason.other",
};

type View = "lookUp" | "queue" | "reports";
type Problem = { key: MessageKey; retry?: "lookUp" | "revoke" };

/**
 * Staff look up a reported personal route by its share link or id, check what it shows, and revoke it
 * (spec 089 US5, DR-D13). Revoking is irreversible; the public page follows within its cache window.
 *
 * Staff also review public listing requests oldest first and act on reader reports (spec 089 US8, DD11): a decision
 * applies only to the version staff saw (R11), and reports never hide a route by themselves.
 */
export function RouteModerationWorkspace({
  repository,
  reviewRepository,
  now = () => new Date(),
}: {
  repository: AdminRouteRepository;
  reviewRepository: AdminRouteReviewRepository;
  now?: () => Date;
}) {
  const { t, formatDateTime, localized, locale } = useI18n();
  const [view, setView] = useState<View>("lookUp");
  const [reference, setReference] = useState("");
  const [route, setRoute] = useState<ModeratedRoute | null>(null);
  const [lookingUp, setLookingUp] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [revoking, setRevoking] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [justRevoked, setJustRevoked] = useState(false);
  const [queue, setQueue] = useState<RouteListingQueueItem[]>([]);
  const [reported, setReported] = useState<ReportedRoute[]>([]);
  const [reviewingId, setReviewingId] = useState<string | null>(null);
  const [declining, setDeclining] = useState(false);
  const [unlisting, setUnlisting] = useState<{ id: string; name: string; viaReports: boolean } | null>(null);
  const [busy, setBusy] = useState(false);

  /** The queue row under review as the queue last read it; null once the route left the queue. */
  const reviewing = reviewingId === null ? null : (queue.find((item) => item.id === reviewingId) ?? null);
  /** The displayed route while it is the queue row under review. */
  const reviewed = reviewing && route && reviewing.id === route.id && route.listingState === "requested" ? route : null;
  /** The queue row moved past the preview: the author edited the route and staff must read it again (R11). */
  const previewStale = reviewed !== null && reviewing?.revision !== reviewed.revision;

  const reloadReview = useCallback(async () => {
    try {
      const [waiting, reports] = await Promise.all([reviewRepository.listQueue(), reviewRepository.listReported()]);
      setQueue(waiting);
      setReported(reports);
    } catch (error) {
      setProblem(failure(error, "routes.reviewFailed", undefined));
    }
  }, [reviewRepository]);

  useEffect(() => {
    void reloadReview();
  }, [reloadReview]);

  /**
   * Reads the route again after the author changed it. A failed read says so and keeps the last preview; a route
   * that is gone clears the preview and says so instead of leaving the stale notice behind.
   */
  const reloadRoute = useCallback(
    async (routeId: string) => {
      try {
        const found = await repository.lookUp(routeId);
        setRoute(found);
        if (!found) setProblem({ key: "routes.notFound" });
      } catch (error) {
        setProblem(failure(error, "routes.lookUpFailed", undefined));
      }
    },
    [repository],
  );

  /** The revision pair the last reconcile started from; the same pair is not read again without a user action. */
  const reconciledPair = useRef<string | null>(null);
  /** The reconcile in flight, so a request made during a run joins it instead of starting another. */
  const reconcileRun = useRef<Promise<void> | null>(null);
  const [reconciling, setReconciling] = useState(false);
  /** Whether the pair disagreed when last compared, so agreement reached afterwards retires the stale notice. */
  const disagreedBefore = useRef(false);

  /**
   * Reads the queue and the displayed route again, once, when their revisions disagree (R11). Neither side is taken
   * as the fresher one: a queue refresh and a preview refresh can land together or out of order, and reading one side
   * only can then leave the two apart for good. Staff see the stale notice while the reads run.
   */
  const reconcile = (routeId: string): Promise<void> => {
    if (reconcileRun.current) return reconcileRun.current;
    reconciledPair.current = revisionPair(reviewing?.revision, reviewed?.revision);
    setReconciling(true);
    setProblem({ key: "routes.stale" });
    const run = Promise.all([reloadReview(), reloadRoute(routeId)])
      .then(() => undefined)
      .finally(() => {
        reconcileRun.current = null;
        setReconciling(false);
      });
    reconcileRun.current = run;
    return run;
  };

  // A mismatch is noticed however it arose and each revision pair is reconciled once: a pair still apart after both
  // sides were read is read again only when it differs from the pair read for (a real edit landed meanwhile) or when
  // staff choose a tab or decide again, so the reads cannot loop. Agreement reached after a disagreement retires the
  // stale notice; the notice of a refused decision whose re-read shows no change stays, so the refusal is not lost.
  useEffect(() => {
    if (!reviewing || !reviewed) {
      disagreedBefore.current = false;
      return;
    }
    const disagree = reviewing.revision !== reviewed.revision;
    if (!disagree && disagreedBefore.current) {
      setProblem((current) => (current?.key === "routes.stale" ? null : current));
    }
    disagreedBefore.current = disagree;
    if (!disagree || reconciling) return;
    if (revisionPair(reviewing.revision, reviewed.revision) === reconciledPair.current) return;
    void reconcile(reviewed.id);
  }, [reviewing?.revision, reviewed?.revision, reconciling]);

  const lookUp = async (event?: FormEvent) => {
    event?.preventDefault();
    if (lookingUp) return;
    const routeId = parseRouteReference(reference);
    setProblem(null);
    setJustRevoked(false);
    setReviewingId(null);
    if (!routeId) {
      setRoute(null);
      setProblem({ key: "routes.invalidReference" });
      return;
    }
    setLookingUp(true);
    try {
      const found = await repository.lookUp(routeId);
      setRoute(found);
      if (!found) setProblem({ key: "routes.notFound" });
    } catch (error) {
      setRoute(null);
      setProblem(failure(error, "routes.lookUpFailed", "lookUp"));
    } finally {
      setLookingUp(false);
    }
  };

  const revoke = async () => {
    if (!route || revoking) return;
    setConfirming(false);
    setRevoking(true);
    setProblem(null);
    try {
      setRoute(await repository.revoke(route.id));
      setJustRevoked(true);
    } catch (error) {
      setProblem(failure(error, "routes.revokeFailed", "revoke"));
    } finally {
      setRevoking(false);
    }
  };

  /** Shows a route from the queue or the reports list; a queue route is reviewed against its queue row. */
  const open = async (routeId: string, fromQueue: boolean) => {
    setProblem(null);
    setJustRevoked(false);
    setReviewingId(fromQueue ? routeId : null);
    try {
      setRoute(await repository.lookUp(routeId));
    } catch (error) {
      setRoute(null);
      setProblem(failure(error, "routes.lookUpFailed", undefined));
    }
  };

  /** Runs one review action and reads the lists again after it; a decision refused as stale reads both sides again. */
  const act = async (action: () => Promise<ModeratedRoute>) => {
    if (busy) return;
    setBusy(true);
    setProblem(null);
    try {
      setRoute(await action());
      setReviewingId(null);
      await reloadReview();
    } catch (error) {
      if (error instanceof RouteListingStaleError && route) {
        await reconcile(route.id);
      } else {
        setProblem(failure(error, "routes.decideFailed", undefined));
        await reloadReview();
      }
    } finally {
      setBusy(false);
    }
  };

  /** Decides on the route as displayed: its own revision is the one staff saw, so the database refuses any later edit. */
  const decide = (decision: RouteListingDecision) => {
    setDeclining(false);
    if (!reviewed || previewStale || reconciling) return;
    const { id, revision } = reviewed;
    void act(() => reviewRepository.decide(id, decision, revision));
  };

  const resolve = async (routeId: string, resolution: "dismissed" | "upheld") => {
    if (busy) return;
    setBusy(true);
    setProblem(null);
    try {
      const resolved = await reviewRepository.resolveReports(routeId, resolution);
      if (route?.id === routeId) setRoute(resolved);
    } catch (error) {
      setProblem(failure(error, "routes.listingActionFailed", undefined));
    } finally {
      await reloadReview();
      setBusy(false);
    }
  };

  const confirmUnlist = () => {
    const target = unlisting;
    setUnlisting(null);
    if (!target) return;
    if (target.viaReports) void resolve(target.id, "upheld");
    else void act(() => reviewRepository.unlist(target.id));
  };

  const status = route ? routeModerationStatus(route) : null;

  return (
    <main className="workspace submission-workspace route-moderation-workspace">
      <header className="workspace-header">
        <div className="workspace-title-row">
          <div>
            <h1>{t("routes.title")}</h1>
            <p className="workspace-subtitle">{t("routes.subtitle")}</p>
          </div>
        </div>
        <div className="route-review-tabs" role="tablist" aria-label={t("routes.views")}>
          {(
            [
              ["lookUp", t("routes.tab.lookUp")],
              ["queue", t("routes.tab.queue", { count: queue.length })],
              ["reports", t("routes.tab.reports", { count: reported.length })],
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
                if (key === "lookUp") return;
                // Other staff and authors change these lists, so choosing a tab reads them again; a preview its queue
                // row moved past is read again with them, which is also how staff retry after a failed read.
                if (reviewed && previewStale) void reconcile(reviewed.id);
                else void reloadReview();
              }}
            >
              {label}
            </button>
          ))}
        </div>
        {view === "lookUp" && (
          <form className="workspace-toolbar" onSubmit={(event) => void lookUp(event)}>
            <label className="search-field">
              <span className="visually-hidden">{t("routes.reference")}</span>
              <input
                type="text"
                aria-label={t("routes.reference")}
                value={reference}
                placeholder={t("routes.referencePlaceholder")}
                onChange={(event) => setReference(event.target.value)}
              />
            </label>
            <button className="black-button" type="submit" disabled={lookingUp} aria-busy={lookingUp}>
              {lookingUp ? t("routes.lookingUp") : t("routes.lookUp")}
            </button>
          </form>
        )}
        {problem && (
          <div className="inline-notice" role="status">
            <span>{t(problem.key)}</span>
            {problem.retry && (
              <button
                className="outlined-button"
                type="button"
                onClick={() => void (problem.retry === "revoke" ? revoke() : lookUp())}
              >
                {t("routes.retry")}
              </button>
            )}
          </div>
        )}
      </header>

      {view === "queue" && (
        <section className="route-review-list">
          {queue.length === 0 ? (
            <p className="muted">{t("routes.queue.empty")}</p>
          ) : (
            <ul aria-label={t("routes.queue.title")}>
              {queue.map((item) => (
                <li key={item.id}>
                  <button type="button" className="route-review-row" onClick={() => void open(item.id, true)}>
                    <strong>{item.name}</strong>{" "}
                    <span className="muted">
                      {t("routes.queue.meta", {
                        author: item.authorDisplayName ?? t("routes.unknownAuthor"),
                        count: item.stopCount,
                        waited: waitedLabel(item.requestedAt, now(), locale),
                      })}
                    </span>
                    {item.wasApprovedBefore && (
                      <>
                        {" "}
                        <span className="submission-status">{t("routes.queue.edited")}</span>
                      </>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {view === "reports" && (
        <section className="route-review-list">
          {reported.length === 0 ? (
            <p className="muted">{t("routes.reports.empty")}</p>
          ) : (
            <ul aria-label={t("routes.reports.title")}>
              {reported.map((item) => (
                <li key={item.id} className="route-review-row">
                  <strong>{item.name}</strong>
                  <span className="muted">
                    {" "}
                    {t("routes.reports.count", { count: item.openCount })} ·{" "}
                    {reportReasons
                      .filter((reason) => item.reasons[reason])
                      .map((reason) => `${t(reportReasonKeys[reason])} ${item.reasons[reason]}`)
                      .join(" · ")}
                  </span>
                  <span className="submission-review-actions">
                    <button className="outlined-button" type="button" disabled={busy} onClick={() => void open(item.id, false)}>
                      {t("routes.open")}
                    </button>
                    <button className="outlined-button" type="button" disabled={busy} onClick={() => void resolve(item.id, "dismissed")}>
                      {t("routes.dismiss")}
                    </button>
                    <button
                      className="black-button"
                      type="button"
                      disabled={busy}
                      onClick={() => setUnlisting({ id: item.id, name: item.name, viaReports: true })}
                    >
                      {t("routes.unlist")}
                    </button>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {route && status && (
        <section className="route-moderation-preview" aria-labelledby="route-moderation-name">
          <span className={`submission-status${status === "revoked" ? " status-rejected" : status === "public" ? " status-accepted" : ""}`}>
            {t(statusKeys[status])}
          </span>
          <span className={`submission-status${listingChipClass[route.listingState]}`}>{t(listingKeys[route.listingState])}</span>
          <h2 id="route-moderation-name">{route.name}</h2>
          <p>
            {route.authorDisplayName
              ? t("routes.author", { name: route.authorDisplayName })
              : t("routes.unknownAuthor")}
          </p>
          {route.publishedAt && <p className="muted">{t("routes.published", { date: formatDateTime(route.publishedAt) })}</p>}
          {route.revokedAt && <p className="muted">{t("routes.revokedAt", { date: formatDateTime(route.revokedAt) })}</p>}
          <p className="muted">{t("routes.copies", { count: route.copyCount })}</p>
          {route.openReportCount > 0 && <p className="muted">{t("routes.reports.count", { count: route.openReportCount })}</p>}
          {route.listingState === "declined" && route.declineReason && (
            <p className="muted">{t("routes.declinedBecause", { reason: t(declineReasonKeys[route.declineReason]) })}</p>
          )}
          {justRevoked && <p role="status">{t("routes.revokedNote")}</p>}
          <h3 id="route-moderation-stops">{t("routes.stops")}</h3>
          <ol aria-labelledby="route-moderation-stops">
            {route.stops.map((stop) => (
              <li key={stop.position}>
                {localized(stop.nameKo, stop.nameEn)} · {localized(stop.venueNameKo, stop.venueNameEn)}
              </li>
            ))}
          </ol>
          <div className="submission-review-actions">
            {reviewed && (
              <>
                <button
                  className="black-button"
                  type="button"
                  disabled={busy || previewStale || reconciling}
                  onClick={() => decide({ kind: "approve" })}
                >
                  {t("routes.approve")}
                </button>
                <button
                  className="outlined-button"
                  type="button"
                  disabled={busy || previewStale || reconciling}
                  onClick={() => setDeclining(true)}
                >
                  {t("routes.decline")}
                </button>
              </>
            )}
            {/* unlist_route takes a route that was ever listed off the list, one withdrawn after approval included. */}
            {!reviewed && everListed(route) && (
              <button
                className="outlined-button"
                type="button"
                disabled={busy}
                onClick={() => setUnlisting({ id: route.id, name: route.name, viaReports: false })}
              >
                {t("routes.unlist")}
              </button>
            )}
            {route.listingState === "removed" && (
              <button
                className="outlined-button"
                type="button"
                disabled={busy}
                onClick={() => void act(() => reviewRepository.restore(route.id))}
              >
                {t("routes.restore")}
              </button>
            )}
            <button
              className="black-button"
              type="button"
              disabled={status === "revoked" || revoking}
              onClick={() => setConfirming(true)}
            >
              {revoking ? t("routes.revoking") : t("routes.revoke")}
            </button>
          </div>
        </section>
      )}

      {confirming && route && (
        <DialogFrame
          role="alertdialog"
          title={t("routes.revokeTitle")}
          onClose={() => setConfirming(false)}
          footer={
            <>
              <button className="outlined-button" type="button" onClick={() => setConfirming(false)}>
                {t("common.cancel")}
              </button>
              <button className="black-button" type="button" disabled={revoking} onClick={() => void revoke()}>
                {t("routes.revokeConfirm")}
              </button>
            </>
          }
        >
          <p><strong>{route.name}</strong></p>
          <p className="muted contract-id">{route.id}</p>
          <p>{t("routes.revokeBody")}</p>
        </DialogFrame>
      )}

      {declining && (
        <DeclineDialog onCancel={() => setDeclining(false)} onDecline={(reason, note) => decide({ kind: "decline", reason, note })} />
      )}

      {unlisting && (
        <DialogFrame
          role="alertdialog"
          title={t("routes.unlistTitle")}
          onClose={() => setUnlisting(null)}
          footer={
            <>
              <button className="outlined-button" type="button" onClick={() => setUnlisting(null)}>
                {t("common.cancel")}
              </button>
              <button className="black-button" type="button" onClick={confirmUnlist}>
                {t("routes.unlist")}
              </button>
            </>
          }
        >
          <p><strong>{unlisting.name}</strong></p>
          <p>{t("routes.unlistBody")}</p>
        </DialogFrame>
      )}
    </main>
  );
}

/** Asks for one fixed reason and an optional note the author will see (DD16). */
function DeclineDialog({
  onCancel,
  onDecline,
}: {
  onCancel: () => void;
  onDecline: (reason: RouteDeclineReason, note: string | null) => void;
}) {
  const { t } = useI18n();
  const [reason, setReason] = useState<RouteDeclineReason | null>(null);
  const [note, setNote] = useState("");
  return (
    <DialogFrame
      title={t("routes.declineTitle")}
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
            onClick={() => reason && onDecline(reason, note.trim() || null)}
          >
            {t("routes.decline")}
          </button>
        </>
      }
    >
      <fieldset className="route-decline-reasons">
        <legend>{t("routes.declineReason")}</legend>
        {declineReasons.map((option) => (
          <label key={option} className="radio-row">
            <input type="radio" name="decline-reason" checked={reason === option} onChange={() => setReason(option)} />
            {t(declineReasonKeys[option])}
          </label>
        ))}
      </fieldset>
      <label className="field">
        <span>{t("routes.declineNote")}</span>
        <textarea
          aria-label={t("routes.declineNote")}
          maxLength={500}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
      </label>
    </DialogFrame>
  );
}

/** "3 hours ago" / "3시간 전" from the request time. */
function waitedLabel(requestedAt: string, now: Date, locale: "ko" | "en"): string {
  const minutes = Math.max(0, Math.round((now.getTime() - new Date(requestedAt).getTime()) / 60_000));
  const format = new Intl.RelativeTimeFormat(locale === "ko" ? "ko-KR" : "en-US", { numeric: "auto" });
  if (minutes < 60) return format.format(-minutes, "minute");
  if (minutes < 48 * 60) return format.format(-Math.round(minutes / 60), "hour");
  return format.format(-Math.round(minutes / (24 * 60)), "day");
}

/**
 * Whether the route was ever on the list or asked to be on it. unlist_route refuses a route that never was
 * (route_listing_invalid_transition); one withdrawn after an approval can still be taken off.
 */
function everListed(route: ModeratedRoute): boolean {
  if (route.listingState === "unlisted") return route.listingLastApprovedAt !== null;
  return route.listingState !== "removed";
}

/** One key for a queue-row revision and a preview revision, so a pair can be remembered and compared. */
function revisionPair(queue: string | undefined, route: string | undefined): string {
  return `${queue ?? ""}|${route ?? ""}`;
}

function failure(error: unknown, fallback: MessageKey, retry: Problem["retry"]): Problem {
  if (error instanceof RouteModerationNotStaffError) return { key: "routes.notStaff" };
  if (error instanceof RouteModerationNotFoundError) return { key: "routes.notFound" };
  // The listing left the state the action applies to; the lists read again afterwards show where it stands now.
  if (error instanceof RouteListingInvalidTransitionError) return { key: "routes.listingActionFailed" };
  return { key: fallback, retry };
}
