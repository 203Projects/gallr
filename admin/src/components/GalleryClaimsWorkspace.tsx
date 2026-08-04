import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  AdminGalleryClaim,
  GalleryClaimFilters,
  GalleryClaimStatus,
} from "../domain";
import type { AdminExhibitionRepository } from "../repositories/AdminExhibitionRepository";
import { SearchIcon } from "./Icons";

type GalleryClaimsRepository = Pick<
  AdminExhibitionRepository,
  "listGalleryClaims" | "approveGalleryClaim" | "rejectGalleryClaim"
>;

const statuses: Array<{ value: GalleryClaimFilters["status"]; label: string }> = [
  { value: "all", label: "All" },
  { value: "pending", label: "Pending" },
  { value: "active", label: "Active" },
  { value: "rejected", label: "Rejected" },
];

const statusLabels: Record<GalleryClaimStatus, string> = {
  pending: "Pending",
  active: "Active",
  rejected: "Rejected",
  suspended: "Suspended",
  revoked: "Revoked",
};

function formatDate(value: string | null): string {
  if (!value) return "—";
  return new Intl.DateTimeFormat("en", {
    year: "numeric",
    month: "short",
    day: "2-digit",
  }).format(new Date(value));
}

function replaceClaim(records: AdminGalleryClaim[], changed: AdminGalleryClaim) {
  return records.map((record) =>
    record.galleryId === changed.galleryId && record.userId === changed.userId
      ? changed
      : record
  );
}

export function GalleryClaimsWorkspace({
  repository,
}: {
  repository: GalleryClaimsRepository;
}) {
  const [filters, setFilters] = useState<GalleryClaimFilters>({ search: "", status: "pending" });
  const [records, setRecords] = useState<AdminGalleryClaim[]>([]);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [reviewNotes, setReviewNotes] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const requestIds = useRef(new Map<string, string>());

  const load = useCallback(async () => {
    setLoading(true);
    setNotice(null);
    try {
      const next = await repository.listGalleryClaims(filters);
      setRecords(next);
      setSelectedKey((current) =>
        current && next.some((record) => `${record.galleryId}:${record.userId}` === current)
          ? current
          : next[0] ? `${next[0].galleryId}:${next[0].userId}` : null
      );
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Gallery claims could not be loaded.");
    } finally {
      setLoading(false);
    }
  }, [filters, repository]);

  useEffect(() => { void load(); }, [load]);

  const selected = useMemo(
    () => records.find((record) => `${record.galleryId}:${record.userId}` === selectedKey) ?? null,
    [records, selectedKey],
  );

  useEffect(() => { setReviewNotes(selected?.reviewNotes ?? ""); }, [selected?.galleryId, selected?.userId, selected?.reviewNotes]);

  const requestId = (action: string, claim: AdminGalleryClaim) => {
    const key = `${action}:${claim.galleryId}:${claim.userId}`;
    const retained = requestIds.current.get(key);
    if (retained) return retained;
    const created = crypto.randomUUID();
    requestIds.current.set(key, created);
    return created;
  };

  const decide = async (approve: boolean) => {
    if (!selected || selected.membershipStatus !== "pending" || busy) return;
    setBusy(true);
    setNotice(null);
    const action = approve ? "approve" : "reject";
    try {
      const changed = approve
        ? await repository.approveGalleryClaim(
            selected.galleryId,
            selected.userId,
            requestId(action, selected),
          )
        : await repository.rejectGalleryClaim(
            selected.galleryId,
            selected.userId,
            reviewNotes.trim(),
            requestId(action, selected),
          );
      setRecords((current) => replaceClaim(current, changed));
      requestIds.current.delete(`${action}:${selected.galleryId}:${selected.userId}`);
      setNotice(approve ? "Gallery claim approved." : "Gallery claim rejected.");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Gallery claim review failed.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <main className="workspace submission-workspace gallery-claims-workspace">
        <header className="workspace-header">
          <div className="workspace-title-row">
            <div>
              <h1>Gallery claims</h1>
              <p className="workspace-subtitle">Verify gallery ownership before enabling exhibition submission.</p>
            </div>
          </div>
          <div className="workspace-toolbar">
            <label className="search-field">
              <span className="visually-hidden">Search gallery claims</span>
              <SearchIcon />
              <input
                type="search"
                value={filters.search}
                placeholder="Search gallery claims..."
                onChange={(event) => setFilters((current) => ({ ...current, search: event.target.value }))}
              />
            </label>
            <div className="status-filter" aria-label="Gallery claim status filter">
              {statuses.map((status) => (
                <button
                  type="button"
                  className={filters.status === status.value ? "is-active" : ""}
                  aria-pressed={filters.status === status.value}
                  onClick={() => setFilters((current) => ({ ...current, status: status.value }))}
                  key={status.value}
                >
                  {status.label}
                </button>
              ))}
            </div>
          </div>
          {notice && <div className="inline-notice" role="status">{notice}</div>}
        </header>

        <div className="submission-table-wrap">
          <table className="submission-table claim-table">
            <thead><tr><th>Requested</th><th>Gallery</th><th>Owner</th><th>Status</th></tr></thead>
            <tbody>
              {records.map((claim) => {
                const key = `${claim.galleryId}:${claim.userId}`;
                return (
                  <tr key={key} className={selectedKey === key ? "is-selected" : ""} onClick={() => setSelectedKey(key)}>
                    <td>{formatDate(claim.createdAt)}</td>
                    <td><strong>{claim.galleryNameKo}</strong><span>{claim.galleryNameEn || "—"}</span></td>
                    <td>{claim.ownerEmail}</td>
                    <td><span className={`submission-status status-${claim.membershipStatus}`}>{statusLabels[claim.membershipStatus]}</span></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {loading && <p className="table-empty">Loading gallery claims…</p>}
          {!loading && records.length === 0 && <p className="table-empty">No matching gallery claims.</p>}
        </div>
        <footer className="table-footer"><span>{records.length} claims</span><span>Staff-only ownership queue</span></footer>
      </main>

      <aside className="submission-inspector" aria-label="Gallery claim details">
        {!selected ? (
          <div className="submission-inspector-empty">Select a gallery claim to review its evidence.</div>
        ) : (
          <>
            <header className="submission-inspector-header">
              <div>
                <span className={`submission-status status-${selected.membershipStatus}`}>{statusLabels[selected.membershipStatus]}</span>
                <h2>{selected.galleryNameKo}</h2>
                <p>{selected.galleryNameEn || "No English name"}</p>
              </div>
            </header>
            <div className="submission-inspector-scroll">
              <section className="submission-detail-section">
                <h3>Requested by</h3>
                <a href={`mailto:${selected.ownerEmail}`}>{selected.ownerEmail}</a>
                <p>{formatDate(selected.createdAt)}</p>
              </section>
              <section className="submission-detail-section">
                <h3>Ownership evidence</h3>
                {selected.websiteUrl && <p><a href={selected.websiteUrl} target="_blank" rel="noreferrer">Official website</a></p>}
                {selected.socialUrl && <p><a href={selected.socialUrl} target="_blank" rel="noreferrer">Official social profile</a></p>}
                {selected.claimNote && <p>{selected.claimNote}</p>}
                {!selected.websiteUrl && !selected.socialUrl && !selected.claimNote && <p>No evidence supplied.</p>}
              </section>
              {selected.reviewNotes && (
                <section className="submission-detail-section"><h3>Review notes</h3><p>{selected.reviewNotes}</p></section>
              )}
              {selected.reviewedAt && (
                <section className="submission-detail-section"><h3>Reviewed</h3><p>{formatDate(selected.reviewedAt)}</p></section>
              )}
            </div>
            {selected.membershipStatus === "pending" && (
              <footer className="submission-review-actions">
                <label>
                  <span>Reason if rejected</span>
                  <textarea rows={3} value={reviewNotes} maxLength={2000} onChange={(event) => setReviewNotes(event.target.value)} />
                </label>
                <div>
                  <button className="outlined-button" type="button" disabled={busy || !reviewNotes.trim()} onClick={() => void decide(false)}>Reject claim</button>
                  <button className="black-button" type="button" disabled={busy} onClick={() => void decide(true)}>Approve claim</button>
                </div>
                <p>Approval grants this account owner access to the gallery workspace.</p>
              </footer>
            )}
          </>
        )}
      </aside>
    </>
  );
}
