import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  AdminExhibition,
  AdminExhibitionSubmission,
  SubmissionFilters,
  SubmissionStatus,
} from "../domain";
import type { AdminExhibitionRepository } from "../repositories/AdminExhibitionRepository";
import { CloseIcon, SearchIcon } from "./Icons";
import {
  LanguageSwitch,
  alternateLocalizedText,
  interfaceMessage,
  translateMessage,
  uiErrorMessage,
  uiMessageText,
  useI18n,
  type MessageKey,
  type PortalLocale,
  type UiMessage,
} from "../i18n";

const statusOptions: Array<{
  value: SubmissionFilters["status"];
  key: MessageKey;
}> = [
  { value: "all", key: "common.all" },
  { value: "submitted", key: "status.submitted" },
  { value: "in_review", key: "status.inReview" },
  { value: "accepted", key: "status.accepted" },
  { value: "rejected", key: "status.rejected" },
];

const statusKeys: Record<SubmissionStatus, MessageKey> = {
  submitted: "status.submitted",
  in_review: "status.inReview",
  accepted: "status.accepted",
  rejected: "status.rejected",
};

export function submissionSourceLabel(
  source: AdminExhibitionSubmission["source"],
  locale: PortalLocale = "en",
): string {
  if (source === "owner_workspace") return translateMessage(locale, "submissions.sourceOwner");
  if (source === "editor_workspace") return translateMessage(locale, "submissions.sourceEditor");
  return translateMessage(locale, "submissions.sourcePublic");
}

function replaceSubmission(
  records: AdminExhibitionSubmission[],
  changed: AdminExhibitionSubmission,
): AdminExhibitionSubmission[] {
  return records.map((record) => record.id === changed.id ? changed : record);
}

export function SubmissionWorkspace({
  repository,
  onAccepted,
}: {
  repository: AdminExhibitionRepository;
  onAccepted: (exhibition: AdminExhibition) => void;
}) {
  const { locale, t, formatDate, formatNumber, localized } = useI18n();
  const [filters, setFilters] = useState<SubmissionFilters>({
    search: "",
    status: "all",
  });
  const [records, setRecords] = useState<AdminExhibitionSubmission[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<UiMessage | null>(null);
  const [reviewNotes, setReviewNotes] = useState("");
  const requestIds = useRef(new Map<string, string>());

  const load = useCallback(async () => {
    setLoading(true);
    setNotice(null);
    try {
      const next = await repository.listSubmissions(filters);
      setRecords(next);
      setSelectedId((current) =>
        current && next.some((record) => record.id === current)
          ? current
          : next[0]?.id ?? null
      );
    } catch (error) {
      setNotice(uiErrorMessage(error, "submissions.loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [filters, repository]);

  useEffect(() => {
    void load();
  }, [load]);

  const selected = useMemo(
    () => records.find((record) => record.id === selectedId) ?? null,
    [records, selectedId],
  );

  useEffect(() => {
    setReviewNotes(selected?.reviewNotes ?? "");
  }, [selected?.id, selected?.reviewNotes]);

  const mutate = async (
    operation: () => Promise<AdminExhibitionSubmission>,
    successMessage: MessageKey,
  ) => {
    if (busy) return;
    setBusy(true);
    setNotice(null);
    try {
      const changed = await operation();
      setRecords((current) => replaceSubmission(current, changed));
      setNotice(interfaceMessage(successMessage));
    } catch (error) {
      setNotice(uiErrorMessage(error, "submissions.reviewFailed"));
    } finally {
      setBusy(false);
    }
  };

  const requestId = (action: string, id: string): string => {
    const key = `${action}:${id}`;
    const retained = requestIds.current.get(key);
    if (retained) return retained;
    const created = crypto.randomUUID();
    requestIds.current.set(key, created);
    return created;
  };

  const accept = async () => {
    if (!selected || busy) return;
    setBusy(true);
    setNotice(null);
    try {
      const result = await repository.acceptSubmission(
        selected.id,
        requestId("accept", selected.id),
      );
      setRecords((current) =>
        replaceSubmission(current, result.submission)
      );
      requestIds.current.delete(`accept:${selected.id}`);
      onAccepted(result.exhibition);
    } catch (error) {
      setNotice(uiErrorMessage(error, "submissions.acceptFailed"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <main className="workspace submission-workspace">
        <header className="workspace-header">
          <div className="workspace-title-row">
            <div>
              <h1>{t("submissions.title")}</h1>
              <p className="workspace-subtitle">
                {t("submissions.subtitle")}
              </p>
            </div>
          </div>
          <div className="workspace-toolbar">
            <label className="search-field">
              <span className="visually-hidden">{t("submissions.search")}</span>
              <SearchIcon />
              <input
                type="search"
                value={filters.search}
                placeholder={t("submissions.searchPlaceholder")}
                onChange={(event) =>
                  setFilters((current) => ({
                    ...current,
                    search: event.target.value,
                  }))
                }
              />
            </label>
            <div className="status-filter" aria-label={t("submissions.filter")}>
              {statusOptions.map((status) => (
                <button
                  type="button"
                  className={filters.status === status.value ? "is-active" : ""}
                  aria-pressed={filters.status === status.value}
                  onClick={() =>
                    setFilters((current) => ({
                      ...current,
                      status: status.value,
                    }))
                  }
                  key={status.value}
                >
                  {t(status.key)}
                </button>
              ))}
            </div>
          </div>
          {notice && (
            <div className="inline-notice" role="status">
              {uiMessageText(notice, t)}
            </div>
          )}
        </header>

        <div className="submission-table-wrap">
          <table className="submission-table">
            <thead>
              <tr>
                <th>{t("submissions.submitted")}</th>
                <th>{t("table.exhibition")}</th>
                <th>{t("table.venue")}</th>
                <th>{t("submissions.source")}</th>
                <th>{t("table.status")}</th>
              </tr>
            </thead>
            <tbody>
              {records.map((submission) => (
                <tr
                  key={submission.id}
                  className={selectedId === submission.id ? "is-selected" : ""}
                  onClick={() => setSelectedId(submission.id)}
                >
                  <td>{formatDate(submission.submittedAt)}</td>
                  <td>
                    <strong>{localized(submission.nameKo, submission.nameEn)}</strong>
                    {alternateLocalizedText(locale, submission.nameKo, submission.nameEn) && (
                      <span>{alternateLocalizedText(locale, submission.nameKo, submission.nameEn)}</span>
                    )}
                  </td>
                  <td>{localized(submission.venueNameKo, submission.venueNameEn)}</td>
                  <td>
                    {t(submission.source === "owner_workspace"
                      ? "submissions.sourceOwner"
                      : submission.source === "editor_workspace"
                        ? "submissions.sourceEditor"
                        : "submissions.sourcePublic")}
                    <span>{submission.submitterEmail}</span>
                  </td>
                  <td>
                    <span className={`submission-status status-${submission.status}`}>
                      {t(statusKeys[submission.status])}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {loading && <p className="table-empty">{t("submissions.loading")}</p>}
          {!loading && records.length === 0 && (
            <p className="table-empty">{t("submissions.empty")}</p>
          )}
        </div>
        <footer className="table-footer">
          <span>{t("submissions.count", { count: formatNumber(records.length) })}</span>
          <span>{t("submissions.privateQueue")}</span>
        </footer>
      </main>

      <aside className={`submission-inspector${selected ? "" : " is-empty"}`} aria-label={t("submissions.detailsLabel")}>
        {!selected ? (
          <div className="submission-inspector-empty">
            {t("submissions.select")}
          </div>
        ) : (
          <>
            <header className="submission-inspector-header">
              <div>
                <span className={`submission-status status-${selected.status}`}>
                  {t(statusKeys[selected.status])}
                </span>
                <h2>{localized(selected.nameKo, selected.nameEn)}</h2>
                {alternateLocalizedText(locale, selected.nameKo, selected.nameEn) && (
                  <p>{alternateLocalizedText(locale, selected.nameKo, selected.nameEn)}</p>
                )}
              </div>
              <div className="submission-inspector-header-actions">
                <div className="inspector-language-switch"><LanguageSwitch /></div>
                <button
                  className="icon-button inspector-mobile-close"
                  type="button"
                  aria-label={t("submissions.back")}
                  onClick={() => setSelectedId(null)}
                >
                  <CloseIcon />
                </button>
              </div>
            </header>

            <div className="submission-inspector-scroll">
              <section className="submission-detail-section">
                <h3>{t(selected.source === "owner_workspace"
                  ? "submissions.galleryOwner"
                  : selected.source === "editor_workspace"
                    ? "submissions.sourceEditor"
                    : "submissions.submittedBy")}</h3>
                <a href={`mailto:${selected.submitterEmail}`}>
                  {selected.submitterEmail}
                </a>
                {selected.source === "owner_workspace" && (
                  <p>{localized(selected.galleryNameKo, selected.galleryNameEn)}</p>
                )}
                <p>{formatDate(selected.submittedAt)}</p>
              </section>
              <section className="submission-detail-section">
                <h3>{t("submissions.details")}</h3>
                <dl>
                  <div><dt>{t("table.venue")}</dt><dd>{localized(selected.venueNameKo, selected.venueNameEn)}</dd></div>
                  <div>
                    <dt>{t("table.dates")}</dt>
                    <dd>{formatDate(selected.openingDate)} — {formatDate(selected.closingDate)}</dd>
                  </div>
                  <div><dt>{t("submissions.address")}</dt><dd>{localized(selected.addressKo, selected.addressEn)}</dd></div>
                  <div><dt>{t("field.hours")}</dt><dd>{selected.hours}</dd></div>
                </dl>
                {(selected.descriptionKo || selected.descriptionEn) && (
                  <p>{localized(selected.descriptionKo, selected.descriptionEn)}</p>
                )}
              </section>
              <section className="submission-detail-section">
                <h3>{t("submissions.images", { count: formatNumber(selected.media.length) })}</h3>
                <div className="submission-media-grid">
                  {selected.media.map((asset) => (
                    <figure key={asset.assetId}>
                      {asset.previewUrl ? (
                        <img src={asset.previewUrl} alt="" />
                      ) : (
                        <div className="submission-media-placeholder">{t("submissions.previewUnavailable")}</div>
                      )}
                      <figcaption>{asset.originalFilename}</figcaption>
                    </figure>
                  ))}
                </div>
              </section>
              {selected.status === "rejected" && (
                <section className="submission-detail-section">
                  <h3>{t("submissions.rejectionReason")}</h3>
                  <p>{selected.reviewNotes}</p>
                </section>
              )}
            </div>

            {(selected.status === "submitted" ||
              selected.status === "in_review") && (
              <footer className="submission-review-actions">
                {selected.status === "submitted" && (
                  <button
                    className="outlined-button"
                    type="button"
                    disabled={busy}
                    onClick={() =>
                      void mutate(
                        () => repository.startSubmissionReview(selected.id),
                        "submissions.reviewStarted",
                      )
                    }
                  >
                    {t("submissions.startReview")}
                  </button>
                )}
                <label>
                  <span>{t(selected.source === "owner_workspace" ? "submissions.changesRequested" : "submissions.reasonRejected")}</span>
                  <textarea
                    rows={3}
                    value={reviewNotes}
                    maxLength={2000}
                    onChange={(event) => setReviewNotes(event.target.value)}
                  />
                </label>
                <div>
                  <button
                    className="outlined-button"
                    type="button"
                    disabled={busy || !reviewNotes.trim()}
                    onClick={() =>
                      void mutate(
                        () =>
                          repository.rejectSubmission(
                            selected.id,
                            reviewNotes,
                            requestId("reject", selected.id),
                          ),
                        selected.source === "owner_workspace"
                          ? "submissions.galleryChangesRequested"
                          : "submissions.rejectedNotice",
                      )
                    }
                  >
                    {t(selected.source === "owner_workspace" ? "submissions.requestChanges" : "common.reject")}
                  </button>
                  <button
                    className="black-button"
                    type="button"
                    disabled={busy}
                    onClick={() => void accept()}
                  >
                    {t(selected.source === "owner_workspace" ? "submissions.acceptOwner" : "submissions.acceptDraft")}
                  </button>
                </div>
                <p>
                  {selected.source === "owner_workspace"
                    ? t("submissions.acceptOwnerHelp")
                    : t("submissions.acceptHelp")}
                </p>
              </footer>
            )}
          </>
        )}
      </aside>
    </>
  );
}
