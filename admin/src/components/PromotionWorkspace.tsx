import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { AdminLocalPromotion, LocalPromotionFilters, LocalPromotionStatus } from "../domain";
import type { AdminExhibitionRepository } from "../repositories/AdminExhibitionRepository";
import { SearchIcon } from "./Icons";

type Repository = Pick<
  AdminExhibitionRepository,
  "listLocalPromotions" | "approveLocalPromotion" | "rejectLocalPromotion"
>;

const statuses: Array<{ value: LocalPromotionFilters["status"]; label: string }> = [
  { value: "all", label: "All" },
  { value: "submitted", label: "Submitted" },
  { value: "approved", label: "Scheduled" },
  { value: "active", label: "Active" },
  { value: "rejected", label: "Rejected" },
  { value: "ended", label: "Ended" },
];
const labels: Record<LocalPromotionStatus, string> = {
  submitted: "Submitted", approved: "Scheduled", active: "Active",
  rejected: "Rejected", ended: "Ended",
};

function date(value: string | null): string {
  return value ? new Intl.DateTimeFormat("en", {
    year: "numeric", month: "short", day: "2-digit", hour: "2-digit", minute: "2-digit",
  }).format(new Date(value)) : "—";
}

function replace(records: AdminLocalPromotion[], changed: AdminLocalPromotion) {
  return records.map((record) => record.id === changed.id ? changed : record);
}

export function PromotionWorkspace({ repository }: { repository: Repository }) {
  const [filters, setFilters] = useState<LocalPromotionFilters>({ search: "", status: "submitted" });
  const [records, setRecords] = useState<AdminLocalPromotion[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [startsAt, setStartsAt] = useState("");
  const [endsAt, setEndsAt] = useState("");
  const [reviewNotes, setReviewNotes] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const requestIds = useRef(new Map<string, string>());

  const load = useCallback(async () => {
    setLoading(true);
    setNotice(null);
    try {
      const next = await repository.listLocalPromotions(filters);
      setRecords(next);
      setSelectedId((current) => current && next.some((item) => item.id === current)
        ? current : next[0]?.id ?? null);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Promotions could not be loaded.");
    } finally { setLoading(false); }
  }, [filters, repository]);

  useEffect(() => { void load(); }, [load]);
  const selected = useMemo(
    () => records.find((record) => record.id === selectedId) ?? null,
    [records, selectedId],
  );
  useEffect(() => {
    setStartsAt(selected?.startsAt?.slice(0, 16) ?? "");
    setEndsAt(selected?.endsAt?.slice(0, 16) ?? "");
    setReviewNotes(selected?.reviewNotes ?? "");
  }, [selected?.id, selected?.startsAt, selected?.endsAt, selected?.reviewNotes]);

  const requestId = (action: string, id: string) => {
    const key = `${action}:${id}`;
    const retained = requestIds.current.get(key);
    if (retained) return retained;
    const created = crypto.randomUUID();
    requestIds.current.set(key, created);
    return created;
  };

  const approve = async () => {
    if (!selected || selected.status !== "submitted" || !startsAt || !endsAt || busy) return;
    setBusy(true); setNotice(null);
    try {
      const changed = await repository.approveLocalPromotion(
        selected.id, new Date(startsAt).toISOString(), new Date(endsAt).toISOString(),
        requestId("approve", selected.id),
      );
      setRecords((current) => replace(current, changed));
      requestIds.current.delete(`approve:${selected.id}`);
      setNotice("Promotion schedule approved.");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Promotion approval failed.");
    } finally { setBusy(false); }
  };

  const reject = async () => {
    if (!selected || selected.status !== "submitted" || !reviewNotes.trim() || busy) return;
    setBusy(true); setNotice(null);
    try {
      const changed = await repository.rejectLocalPromotion(
        selected.id, reviewNotes.trim(), requestId("reject", selected.id),
      );
      setRecords((current) => replace(current, changed));
      requestIds.current.delete(`reject:${selected.id}`);
      setNotice("Promotion request rejected.");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Promotion rejection failed.");
    } finally { setBusy(false); }
  };

  return <>
    <main className="workspace submission-workspace promotion-workspace">
      <header className="workspace-header">
        <div className="workspace-title-row"><div>
          <h1>Promotions</h1>
          <p className="workspace-subtitle">Free local promotions, separate from editorial Featured and organic discovery.</p>
        </div></div>
        <div className="workspace-toolbar">
          <label className="search-field"><span className="visually-hidden">Search promotions</span><SearchIcon />
            <input type="search" value={filters.search} placeholder="Search promotions..."
              onChange={(event) => setFilters((current) => ({ ...current, search: event.target.value }))} />
          </label>
          <div className="status-filter" aria-label="Promotion status filter">
            {statuses.map((status) => <button type="button" key={status.value}
              className={filters.status === status.value ? "is-active" : ""}
              aria-pressed={filters.status === status.value}
              onClick={() => setFilters((current) => ({ ...current, status: status.value }))}>{status.label}</button>)}
          </div>
        </div>
        {notice && <div className="inline-notice" role="status">{notice}</div>}
      </header>
      <div className="submission-table-wrap"><table className="submission-table">
        <thead><tr><th>Requested</th><th>Exhibition</th><th>Gallery</th><th>Locality</th><th>Status</th></tr></thead>
        <tbody>{records.map((promotion) => <tr key={promotion.id}
          className={promotion.id === selectedId ? "is-selected" : ""}
          onClick={() => setSelectedId(promotion.id)}>
          <td>{date(promotion.requestedAt)}</td>
          <td><strong>{promotion.nameEn || promotion.nameKo}</strong><span>{promotion.venueNameEn || promotion.venueNameKo}</span></td>
          <td>{promotion.galleryNameEn || promotion.galleryNameKo}</td>
          <td>{promotion.cityEn || promotion.cityKo} · {promotion.regionEn || promotion.regionKo}</td>
          <td><span className={`submission-status status-${promotion.status}`}>{labels[promotion.status]}</span></td>
        </tr>)}</tbody>
      </table>
      {loading && <p className="table-empty">Loading promotions…</p>}
      {!loading && records.length === 0 && <p className="table-empty">No matching promotions.</p>}
      </div>
      <footer className="table-footer"><span>{records.length} promotions</span><span>Free promotion queue</span></footer>
    </main>
    <aside className="submission-inspector" aria-label="Promotion details">
      {!selected ? <div className="submission-inspector-empty">Select a promotion request.</div> : <>
        <header className="submission-inspector-header"><div>
          <span className={`submission-status status-${selected.status}`}>{labels[selected.status]}</span>
          <h2>{selected.nameEn || selected.nameKo}</h2>
          <p>{selected.galleryNameEn || selected.galleryNameKo}</p>
        </div></header>
        <div className="submission-inspector-scroll">
          <section className="submission-detail-section"><h3>Placement terms</h3>
            <p>Promoted placement · Shown at most once per visitor per day</p>
            <p>{selected.cityEn || selected.cityKo} · {selected.regionEn || selected.regionKo}</p>
            <p>Exhibition closes {selected.closingDate}</p>
          </section>
          {selected.startsAt && <section className="submission-detail-section"><h3>Schedule</h3><p>{date(selected.startsAt)} — {date(selected.endsAt)}</p></section>}
          {selected.reviewNotes && <section className="submission-detail-section"><h3>Review notes</h3><p>{selected.reviewNotes}</p></section>}
        </div>
        {selected.status === "submitted" && <footer className="submission-review-actions promotion-review-actions">
          <div className="promotion-schedule-fields">
            <label><span>Starts</span><input aria-label="Starts" type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} /></label>
            <label><span>Ends</span><input aria-label="Ends" type="datetime-local" value={endsAt} onChange={(event) => setEndsAt(event.target.value)} /></label>
          </div>
          <label><span>Reason if rejected</span><textarea rows={3} maxLength={2000} value={reviewNotes} onChange={(event) => setReviewNotes(event.target.value)} /></label>
          <div>
            <button className="outlined-button" type="button" disabled={busy || !reviewNotes.trim()} onClick={() => void reject()}>Reject request</button>
            <button className="black-button" type="button" disabled={busy || !startsAt || !endsAt} onClick={() => void approve()}>Approve schedule</button>
          </div>
          <p>Approval never changes catalogue ordering or editorial Featured.</p>
        </footer>}
      </>}
    </aside>
  </>;
}
