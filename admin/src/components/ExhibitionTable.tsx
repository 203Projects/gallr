import type { AdminExhibition } from "../domain";
import { useI18n, type MessageKey } from "../i18n";

interface ExhibitionTableProps {
  exhibitions: AdminExhibition[];
  selectedId: string | null;
  onSelect: (exhibition: AdminExhibition) => void;
  loading: boolean;
}

const statusKeys: Record<AdminExhibition["status"], MessageKey> = {
  Draft: "status.draft",
  Published: "status.published",
  Archived: "status.archived",
};

export function ExhibitionTable({
  exhibitions,
  selectedId,
  onSelect,
  loading,
}: ExhibitionTableProps) {
  const { locale, t, formatDate, formatDateTime, localized } = useI18n();
  if (loading) {
    return (
      <div className="table-state" role="status">
        {t("table.loading")}
      </div>
    );
  }

  if (exhibitions.length === 0) {
    return (
      <div className="table-state">
        <p>{t("table.empty")}</p>
        <p className="muted">{t("table.emptyHint")}</p>
      </div>
    );
  }

  return (
    <div className="exhibition-table" role="table" aria-label={t("table.label")}>
      <div className="table-header table-grid" role="row">
        <span role="columnheader">{t("table.exhibition")}</span>
        <span role="columnheader">{t("table.venue")}</span>
        <span role="columnheader">{t("table.dates")}</span>
        <span role="columnheader">{t("table.status")}</span>
        <span role="columnheader">{t("table.lastEdited")}</span>
      </div>
      <div className="table-body" role="rowgroup">
        {exhibitions.map((exhibition) => {
          const selected = exhibition.id === selectedId;
          const displayName = localized(
            exhibition.nameKo,
            exhibition.nameEn,
            t("common.untitledExhibition"),
          );
          const alternateName = locale === "ko"
            ? exhibition.nameEn
            : exhibition.nameKo;
          return (
            <button
              type="button"
              className={`exhibition-row table-grid${selected ? " is-selected" : ""}`}
              role="row"
              aria-selected={selected}
              onClick={() => onSelect(exhibition)}
              key={exhibition.id}
            >
              <span className="exhibition-cell" role="cell">
                <span className="selection-box" aria-hidden="true">
                  {selected ? "✓" : ""}
                </span>
                <span>
                  <strong>{displayName}</strong>
                  <small>{alternateName && alternateName !== displayName ? alternateName : exhibition.id}</small>
                </span>
              </span>
              <span className="stacked-cell" role="cell">
                <span>{localized(exhibition.venueNameKo, exhibition.venueNameEn, "—")}</span>
                <small>{localized(exhibition.cityKo, exhibition.cityEn, "—")}</small>
              </span>
              <span role="cell">
                {formatDate(exhibition.openingDate)} –{" "}
                {formatDate(exhibition.closingDate)}
              </span>
              <span className="status-text" role="cell">
                {t(statusKeys[exhibition.status])}
              </span>
              <span className="stacked-cell" role="cell">
                <span>{formatDateTime(exhibition.updatedAt)}</span>
                <small>{t("table.by", { name: exhibition.updatedBy })}</small>
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
