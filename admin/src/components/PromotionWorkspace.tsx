import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { AdminLocalPromotion, LocalPromotionFilters, LocalPromotionStatus } from "../domain";
import type { AdminExhibitionRepository } from "../repositories/AdminExhibitionRepository";
import { SearchIcon } from "./Icons";
import {
  LanguageSwitch,
  interfaceMessage,
  uiErrorMessage,
  uiMessageText,
  useI18n,
  type MessageKey,
  type UiMessage,
} from "../i18n";

type Repository = Pick<
  AdminExhibitionRepository,
  "listLocalPromotions" | "approveLocalPromotion" | "rejectLocalPromotion"
>;

const statuses: Array<{ value: LocalPromotionFilters["status"]; key: MessageKey }> = [
  { value: "all", key: "common.all" },
  { value: "submitted", key: "promotions.statusSubmitted" },
  { value: "approved", key: "promotions.statusScheduled" },
  { value: "active", key: "promotions.statusActive" },
  { value: "rejected", key: "promotions.statusRejected" },
  { value: "ended", key: "promotions.statusEnded" },
];
const statusKeys: Record<LocalPromotionStatus, MessageKey> = {
  submitted: "promotions.statusSubmitted",
  approved: "promotions.statusScheduled",
  active: "promotions.statusActive",
  rejected: "promotions.statusRejected",
  ended: "promotions.statusEnded",
};

function seoulDateTimeLocal(value: string | null): string {
  if (!value) return "";
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(new Date(value));
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}T${part("hour")}:${part("minute")}`;
}

function seoulLocalToIso(value: string): string {
  return new Date(`${value}:00+09:00`).toISOString();
}

function replace(records: AdminLocalPromotion[], changed: AdminLocalPromotion) {
  return records.map((record) => record.id === changed.id ? changed : record);
}

export function PromotionWorkspace({ repository }: { repository: Repository }) {
  const { t, formatDate, formatDateTime, formatNumber, localized } = useI18n();
  const [filters, setFilters] = useState<LocalPromotionFilters>({ search: "", status: "submitted" });
  const [records, setRecords] = useState<AdminLocalPromotion[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [startsAt, setStartsAt] = useState("");
  const [endsAt, setEndsAt] = useState("");
  const [reviewNotes, setReviewNotes] = useState("");
  const [notice, setNotice] = useState<UiMessage | null>(null);
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
      setNotice(uiErrorMessage(error, "promotions.loadFailed"));
    } finally { setLoading(false); }
  }, [filters, repository]);

  useEffect(() => { void load(); }, [load]);
  const selected = useMemo(
    () => records.find((record) => record.id === selectedId) ?? null,
    [records, selectedId],
  );
  useEffect(() => {
    setStartsAt(seoulDateTimeLocal(selected?.startsAt ?? null));
    setEndsAt(seoulDateTimeLocal(selected?.endsAt ?? null));
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
        selected.id, seoulLocalToIso(startsAt), seoulLocalToIso(endsAt),
        requestId("approve", selected.id),
      );
      setRecords((current) => replace(current, changed));
      requestIds.current.delete(`approve:${selected.id}`);
      setNotice(interfaceMessage("promotions.approved"));
    } catch (error) {
      setNotice(uiErrorMessage(error, "promotions.approvalFailed"));
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
      setNotice(interfaceMessage("promotions.rejected"));
    } catch (error) {
      setNotice(uiErrorMessage(error, "promotions.rejectionFailed"));
    } finally { setBusy(false); }
  };

  return <>
    <main className="workspace submission-workspace promotion-workspace">
      <header className="workspace-header">
        <div className="workspace-title-row"><div>
          <h1>{t("promotions.title")}</h1>
          <p className="workspace-subtitle">{t("promotions.subtitle")}</p>
        </div></div>
        <div className="workspace-toolbar">
          <label className="search-field"><span className="visually-hidden">{t("promotions.search")}</span><SearchIcon />
            <input type="search" value={filters.search} placeholder={t("promotions.searchPlaceholder")}
              onChange={(event) => setFilters((current) => ({ ...current, search: event.target.value }))} />
          </label>
          <div className="status-filter" aria-label={t("promotions.filter")}>
            {statuses.map((status) => <button type="button" key={status.value}
              className={filters.status === status.value ? "is-active" : ""}
              aria-pressed={filters.status === status.value}
              onClick={() => setFilters((current) => ({ ...current, status: status.value }))}>{t(status.key)}</button>)}
          </div>
        </div>
        {notice && <div className="inline-notice" role="status">{uiMessageText(notice, t)}</div>}
      </header>
      <div className="submission-table-wrap"><table className="submission-table">
        <thead><tr><th>{t("promotions.requested")}</th><th>{t("table.exhibition")}</th><th>{t("promotions.gallery")}</th><th>{t("promotions.locality")}</th><th>{t("table.status")}</th></tr></thead>
        <tbody>{records.map((promotion) => <tr key={promotion.id}
          className={promotion.id === selectedId ? "is-selected" : ""}
          onClick={() => setSelectedId(promotion.id)}>
          <td>{formatDateTime(promotion.requestedAt)}</td>
          <td><strong>{localized(promotion.nameKo, promotion.nameEn)}</strong><span>{localized(promotion.venueNameKo, promotion.venueNameEn)}</span></td>
          <td>{localized(promotion.galleryNameKo, promotion.galleryNameEn)}</td>
          <td>{localized(promotion.cityKo, promotion.cityEn)} · {localized(promotion.regionKo, promotion.regionEn)}</td>
          <td><span className={`submission-status status-${promotion.status}`}>{t(statusKeys[promotion.status])}</span></td>
        </tr>)}</tbody>
      </table>
      {loading && <p className="table-empty">{t("promotions.loading")}</p>}
      {!loading && records.length === 0 && <p className="table-empty">{t("promotions.empty")}</p>}
      </div>
      <footer className="table-footer"><span>{t("promotions.count", { count: formatNumber(records.length) })}</span><span>{t("promotions.queue")}</span></footer>
    </main>
    <aside className={`submission-inspector${selected ? "" : " is-empty"}`} aria-label={t("promotions.detailsLabel")}>
      {!selected ? <div className="submission-inspector-empty">{t("promotions.select")}</div> : <>
        <header className="submission-inspector-header"><div>
          <span className={`submission-status status-${selected.status}`}>{t(statusKeys[selected.status])}</span>
          <h2>{localized(selected.nameKo, selected.nameEn)}</h2>
          <p>{localized(selected.galleryNameKo, selected.galleryNameEn)}</p>
        </div><div className="inspector-language-switch"><LanguageSwitch /></div></header>
        <div className="submission-inspector-scroll">
          <section className="submission-detail-section"><h3>{t("promotions.terms")}</h3>
            <p>{t("promotions.termsBody")}</p>
            <p>{localized(selected.cityKo, selected.cityEn)} · {localized(selected.regionKo, selected.regionEn)}</p>
            <p>{t("promotions.closes", { date: formatDate(selected.closingDate) })}</p>
          </section>
          {selected.startsAt && <section className="submission-detail-section"><h3>{t("promotions.schedule")}</h3><p>{formatDateTime(selected.startsAt)} — {formatDateTime(selected.endsAt ?? "")}</p></section>}
          {selected.reviewNotes && <section className="submission-detail-section"><h3>{t("promotions.reviewNotes")}</h3><p>{selected.reviewNotes}</p></section>}
        </div>
        {selected.status === "submitted" && <footer className="submission-review-actions promotion-review-actions">
          <div className="promotion-schedule-fields">
            <label><span>{t("promotions.starts")}</span><input aria-label={t("promotions.starts")} type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} /></label>
            <label><span>{t("promotions.ends")}</span><input aria-label={t("promotions.ends")} type="datetime-local" value={endsAt} onChange={(event) => setEndsAt(event.target.value)} /></label>
          </div>
          <label><span>{t("promotions.reason")}</span><textarea rows={3} maxLength={2000} value={reviewNotes} onChange={(event) => setReviewNotes(event.target.value)} /></label>
          <div>
            <button className="outlined-button" type="button" disabled={busy || !reviewNotes.trim()} onClick={() => void reject()}>{t("promotions.reject")}</button>
            <button className="black-button" type="button" disabled={busy || !startsAt || !endsAt} onClick={() => void approve()}>{t("promotions.approve")}</button>
          </div>
          <p>{t("promotions.approvalHelp")}</p>
        </footer>}
      </>}
    </aside>
  </>;
}
