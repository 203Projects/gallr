import type { AdminExhibition } from "../domain";

interface ExhibitionTableProps {
  exhibitions: AdminExhibition[];
  selectedId: string | null;
  onSelect: (exhibition: AdminExhibition) => void;
  loading: boolean;
}

const dateFormatter = new Intl.DateTimeFormat("en-CA", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

const timestampFormatter = new Intl.DateTimeFormat("en-CA", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

function compactDate(value: string) {
  if (!value) return "—";
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime()) ? value : dateFormatter.format(date);
}

export function ExhibitionTable({
  exhibitions,
  selectedId,
  onSelect,
  loading,
}: ExhibitionTableProps) {
  if (loading) {
    return (
      <div className="table-state" role="status">
        Loading exhibitions…
      </div>
    );
  }

  if (exhibitions.length === 0) {
    return (
      <div className="table-state">
        <p>No exhibitions match this view.</p>
        <p className="muted">Try a different search or status.</p>
      </div>
    );
  }

  return (
    <div className="exhibition-table" role="table" aria-label="Exhibitions">
      <div className="table-header table-grid" role="row">
        <span role="columnheader">Exhibition</span>
        <span role="columnheader">Venue</span>
        <span role="columnheader">Dates</span>
        <span role="columnheader">Status</span>
        <span role="columnheader">Last edited</span>
      </div>
      <div className="table-body" role="rowgroup">
        {exhibitions.map((exhibition) => {
          const selected = exhibition.id === selectedId;
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
                  <strong>{exhibition.nameKo || "Untitled exhibition"}</strong>
                  <small>{exhibition.nameEn || exhibition.id}</small>
                </span>
              </span>
              <span className="stacked-cell" role="cell">
                <span>{exhibition.venueNameKo || "—"}</span>
                <small>{exhibition.cityKo || "—"}</small>
              </span>
              <span role="cell">
                {compactDate(exhibition.openingDate)} –{" "}
                {compactDate(exhibition.closingDate)}
              </span>
              <span className="status-text" role="cell">
                {exhibition.status}
              </span>
              <span className="stacked-cell" role="cell">
                <span>{timestampFormatter.format(new Date(exhibition.updatedAt))}</span>
                <small>by {exhibition.updatedBy}</small>
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
