import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  AdminGalleryClaim,
  GalleryClaimFilters,
  GalleryClaimStatus,
} from "../domain";
import type { AdminExhibitionRepository } from "../repositories/AdminExhibitionRepository";
import { SearchIcon } from "./Icons";
import {
  LanguageSwitch,
  alternateLocalizedText,
  interfaceMessage,
  uiErrorMessage,
  uiMessageText,
  useI18n,
  type MessageKey,
  type UiMessage,
} from "../i18n";

type GalleryClaimsRepository = Pick<
  AdminExhibitionRepository,
  "listGalleryClaims" | "approveGalleryClaim" | "rejectGalleryClaim"
>;

const statuses: Array<{ value: GalleryClaimFilters["status"]; key: MessageKey }> = [
  { value: "all", key: "common.all" },
  { value: "pending", key: "status.pending" },
  { value: "active", key: "status.active" },
  { value: "rejected", key: "status.rejected" },
];

const statusKeys: Record<GalleryClaimStatus, MessageKey> = {
  pending: "status.pending",
  active: "status.active",
  rejected: "status.rejected",
  suspended: "status.suspended",
  revoked: "status.revoked",
};

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
  const { locale, t, formatDate, formatNumber, localized } = useI18n();
  const [filters, setFilters] = useState<GalleryClaimFilters>({ search: "", status: "pending" });
  const [records, setRecords] = useState<AdminGalleryClaim[]>([]);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [reviewNotes, setReviewNotes] = useState("");
  const [notice, setNotice] = useState<UiMessage | null>(null);
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
      setNotice(uiErrorMessage(error, "claims.loadFailed"));
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
      setNotice(interfaceMessage(approve ? "claims.approved" : "claims.rejected"));
    } catch (error) {
      setNotice(uiErrorMessage(error, "claims.reviewFailed"));
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
              <h1>{t("claims.title")}</h1>
              <p className="workspace-subtitle">{t("claims.subtitle")}</p>
            </div>
          </div>
          <div className="workspace-toolbar">
            <label className="search-field">
              <span className="visually-hidden">{t("claims.search")}</span>
              <SearchIcon />
              <input
                type="search"
                value={filters.search}
                placeholder={t("claims.searchPlaceholder")}
                onChange={(event) => setFilters((current) => ({ ...current, search: event.target.value }))}
              />
            </label>
            <div className="status-filter" aria-label={t("claims.filter")}>
              {statuses.map((status) => (
                <button
                  type="button"
                  className={filters.status === status.value ? "is-active" : ""}
                  aria-pressed={filters.status === status.value}
                  onClick={() => setFilters((current) => ({ ...current, status: status.value }))}
                  key={status.value}
                >
                  {t(status.key)}
                </button>
              ))}
            </div>
          </div>
          {notice && <div className="inline-notice" role="status">{uiMessageText(notice, t)}</div>}
        </header>

        <div className="submission-table-wrap">
          <table className="submission-table claim-table">
            <thead><tr><th>{t("claims.requested")}</th><th>{t("claims.gallery")}</th><th>{t("claims.owner")}</th><th>{t("table.status")}</th></tr></thead>
            <tbody>
              {records.map((claim) => {
                const key = `${claim.galleryId}:${claim.userId}`;
                return (
                  <tr key={key} className={selectedKey === key ? "is-selected" : ""} onClick={() => setSelectedKey(key)}>
                    <td>{formatDate(claim.createdAt)}</td>
                    <td>
                      <strong>{localized(claim.galleryNameKo, claim.galleryNameEn)}</strong>
                      {alternateLocalizedText(locale, claim.galleryNameKo, claim.galleryNameEn) && (
                        <span>{alternateLocalizedText(locale, claim.galleryNameKo, claim.galleryNameEn)}</span>
                      )}
                    </td>
                    <td>{claim.ownerEmail}</td>
                    <td><span className={`submission-status status-${claim.membershipStatus}`}>{t(statusKeys[claim.membershipStatus])}</span></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {loading && <p className="table-empty">{t("claims.loading")}</p>}
          {!loading && records.length === 0 && <p className="table-empty">{t("claims.empty")}</p>}
        </div>
        <footer className="table-footer"><span>{t("claims.count", { count: formatNumber(records.length) })}</span><span>{t("claims.queue")}</span></footer>
      </main>

      <aside className={`submission-inspector${selected ? "" : " is-empty"}`} aria-label={t("claims.detailsLabel")}>
        {!selected ? (
          <div className="submission-inspector-empty">{t("claims.select")}</div>
        ) : (
          <>
            <header className="submission-inspector-header">
              <div>
                <span className={`submission-status status-${selected.membershipStatus}`}>{t(statusKeys[selected.membershipStatus])}</span>
                <h2>{localized(selected.galleryNameKo, selected.galleryNameEn)}</h2>
                {alternateLocalizedText(locale, selected.galleryNameKo, selected.galleryNameEn) && (
                  <p>{alternateLocalizedText(locale, selected.galleryNameKo, selected.galleryNameEn)}</p>
                )}
              </div>
              <div className="inspector-language-switch"><LanguageSwitch /></div>
            </header>
            <div className="submission-inspector-scroll">
              <section className="submission-detail-section">
                <h3>{t("claims.requestedBy")}</h3>
                <a href={`mailto:${selected.ownerEmail}`}>{selected.ownerEmail}</a>
                <p>{formatDate(selected.createdAt)}</p>
              </section>
              <section className="submission-detail-section">
                <h3>{t("claims.evidence")}</h3>
                {selected.websiteUrl && <p><a href={selected.websiteUrl} target="_blank" rel="noreferrer">{t("claims.website")}</a></p>}
                {selected.socialUrl && <p><a href={selected.socialUrl} target="_blank" rel="noreferrer">{t("claims.social")}</a></p>}
                {selected.claimNote && <p>{selected.claimNote}</p>}
                {!selected.websiteUrl && !selected.socialUrl && !selected.claimNote && <p>{t("claims.noEvidence")}</p>}
              </section>
              {selected.reviewNotes && (
                <section className="submission-detail-section"><h3>{t("claims.reviewNotes")}</h3><p>{selected.reviewNotes}</p></section>
              )}
              {selected.reviewedAt && (
                <section className="submission-detail-section"><h3>{t("claims.reviewed")}</h3><p>{formatDate(selected.reviewedAt)}</p></section>
              )}
            </div>
            {selected.membershipStatus === "pending" && (
              <footer className="submission-review-actions">
                <label>
                  <span>{t("claims.reason")}</span>
                  <textarea rows={3} value={reviewNotes} maxLength={2000} onChange={(event) => setReviewNotes(event.target.value)} />
                </label>
                <div>
                  <button className="outlined-button" type="button" disabled={busy || !reviewNotes.trim()} onClick={() => void decide(false)}>{t("claims.reject")}</button>
                  <button className="black-button" type="button" disabled={busy} onClick={() => void decide(true)}>{t("claims.approve")}</button>
                </div>
                <p>{t("claims.approvalHelp")}</p>
              </footer>
            )}
          </>
        )}
      </aside>
    </>
  );
}
