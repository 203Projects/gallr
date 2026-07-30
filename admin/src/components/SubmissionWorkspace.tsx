import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  AdminExhibition,
  AdminExhibitionSubmission,
  SubmissionFilters,
  SubmissionStatus,
} from "../domain";
import type { AdminExhibitionRepository } from "../repositories/AdminExhibitionRepository";
import { SearchIcon } from "./Icons";

const statusOptions: Array<{
  value: SubmissionFilters["status"];
  label: string;
}> = [
  { value: "all", label: "All" },
  { value: "submitted", label: "Submitted" },
  { value: "in_review", label: "In review" },
  { value: "accepted", label: "Accepted" },
  { value: "rejected", label: "Rejected" },
];

const statusLabels: Record<SubmissionStatus, string> = {
  submitted: "Submitted",
  in_review: "In review",
  accepted: "Accepted",
  rejected: "Rejected",
};

function formatDate(value: string): string {
  if (!value) return "—";
  return new Intl.DateTimeFormat("en", {
    year: "numeric",
    month: "short",
    day: "2-digit",
  }).format(new Date(value));
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
  const [filters, setFilters] = useState<SubmissionFilters>({
    search: "",
    status: "all",
  });
  const [records, setRecords] = useState<AdminExhibitionSubmission[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
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
      setNotice(
        error instanceof Error
          ? error.message
          : "Submissions could not be loaded.",
      );
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
    successMessage: string,
  ) => {
    if (busy) return;
    setBusy(true);
    setNotice(null);
    try {
      const changed = await operation();
      setRecords((current) => replaceSubmission(current, changed));
      setNotice(successMessage);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Review failed.");
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
      setNotice(
        error instanceof Error ? error.message : "Submission was not accepted.",
      );
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
              <h1>Submissions</h1>
              <p className="workspace-subtitle">
                Gallery-submitted exhibitions waiting for editorial review.
              </p>
            </div>
          </div>
          <div className="workspace-toolbar">
            <label className="search-field">
              <span className="visually-hidden">Search submissions</span>
              <SearchIcon />
              <input
                type="search"
                value={filters.search}
                placeholder="Search submissions..."
                onChange={(event) =>
                  setFilters((current) => ({
                    ...current,
                    search: event.target.value,
                  }))
                }
              />
            </label>
            <div className="status-filter" aria-label="Submission status filter">
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
                  {status.label}
                </button>
              ))}
            </div>
          </div>
          {notice && (
            <div className="inline-notice" role="status">
              {notice}
            </div>
          )}
        </header>

        <div className="submission-table-wrap">
          <table className="submission-table">
            <thead>
              <tr>
                <th>Submitted</th>
                <th>Exhibition</th>
                <th>Venue</th>
                <th>Contact</th>
                <th>Status</th>
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
                    <strong>{submission.nameKo}</strong>
                    <span>{submission.nameEn || "—"}</span>
                  </td>
                  <td>{submission.venueNameKo}</td>
                  <td>{submission.submitterEmail}</td>
                  <td>
                    <span className={`submission-status status-${submission.status}`}>
                      {statusLabels[submission.status]}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {loading && <p className="table-empty">Loading submissions…</p>}
          {!loading && records.length === 0 && (
            <p className="table-empty">No matching submissions.</p>
          )}
        </div>
        <footer className="table-footer">
          <span>{records.length} submissions</span>
          <span>Private review queue</span>
        </footer>
      </main>

      <aside className="submission-inspector" aria-label="Submission details">
        {!selected ? (
          <div className="submission-inspector-empty">
            Select a submission to review its details.
          </div>
        ) : (
          <>
            <header className="submission-inspector-header">
              <div>
                <span className={`submission-status status-${selected.status}`}>
                  {statusLabels[selected.status]}
                </span>
                <h2>{selected.nameKo}</h2>
                <p>{selected.nameEn || "No English title"}</p>
              </div>
            </header>

            <div className="submission-inspector-scroll">
              <section className="submission-detail-section">
                <h3>Submitted by</h3>
                <a href={`mailto:${selected.submitterEmail}`}>
                  {selected.submitterEmail}
                </a>
                <p>{formatDate(selected.submittedAt)}</p>
              </section>
              <section className="submission-detail-section">
                <h3>Exhibition details</h3>
                <dl>
                  <div><dt>Venue</dt><dd>{selected.venueNameKo}</dd></div>
                  <div>
                    <dt>Dates</dt>
                    <dd>{selected.openingDate} — {selected.closingDate}</dd>
                  </div>
                  <div><dt>Address</dt><dd>{selected.addressKo}</dd></div>
                  <div><dt>Hours</dt><dd>{selected.hours}</dd></div>
                </dl>
                {selected.descriptionKo && <p>{selected.descriptionKo}</p>}
              </section>
              <section className="submission-detail-section">
                <h3>Images · {selected.media.length}</h3>
                <div className="submission-media-grid">
                  {selected.media.map((asset) => (
                    <figure key={asset.assetId}>
                      {asset.previewUrl ? (
                        <img src={asset.previewUrl} alt="" />
                      ) : (
                        <div className="submission-media-placeholder">Preview unavailable</div>
                      )}
                      <figcaption>{asset.originalFilename}</figcaption>
                    </figure>
                  ))}
                </div>
              </section>
              {selected.status === "rejected" && (
                <section className="submission-detail-section">
                  <h3>Rejection reason</h3>
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
                        "Review started.",
                      )
                    }
                  >
                    Start review
                  </button>
                )}
                <label>
                  <span>Reason if rejected</span>
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
                        "Submission rejected.",
                      )
                    }
                  >
                    Reject
                  </button>
                  <button
                    className="black-button"
                    type="button"
                    disabled={busy}
                    onClick={() => void accept()}
                  >
                    Accept as draft
                  </button>
                </div>
                <p>
                  Acceptance creates an unpublished draft. Review coordinates,
                  media metadata, and all public fields before publishing.
                </p>
              </footer>
            )}
          </>
        )}
      </aside>
    </>
  );
}
