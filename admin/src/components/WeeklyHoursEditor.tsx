import { useState } from "react";
import {
  HOURS_WEEKLY_DAYS,
  type ConfirmedHoursState,
  type HoursWeekly,
  type HoursWeeklyDay,
} from "../domain";
import { parseOpeningHours, toHoursWeekly, type OpeningHoursCompleteness } from "../hours/openingHours";
import { useI18n, type MessageKey } from "../i18n";

interface DayRow {
  closed: boolean;
  opens: string;
  closes: string;
}

type Rows = Record<HoursWeeklyDay, DayRow>;
type Source = "confirmed" | OpeningHoursCompleteness;

const HHMM = /^(?:[01]\d|2[0-3]):[0-5]\d$/u;

const sourceKeys: Record<Source, MessageKey> = {
  confirmed: "hours.fromConfirmed",
  COMPLETE: "hours.readComplete",
  PARTIAL: "hours.readPartial",
  UNKNOWN: "hours.readUnknown",
};

/** A refusal of `admin_confirm_exhibition_hours` (`hours_weekly_invalid`), naming the day when the server did. */
export interface HoursRefusal {
  day: HoursWeeklyDay | null;
}

interface Props {
  /** The saved free-text hours of the working version. */
  hoursText: string;
  confirmedHours: ConfirmedHoursState;
  disabled: boolean;
  confirming: boolean;
  /** Why Confirm hours is unavailable right now; null when it can run. */
  confirmDisabledReason: string | null;
  refusal: HoursRefusal | null;
  onConfirm: (hoursWeekly: HoursWeekly) => void;
}

function rowsFrom(weekly: HoursWeekly): Rows {
  const rows = {} as Rows;
  for (const day of HOURS_WEEKLY_DAYS) {
    const opening = weekly[day];
    rows[day] = opening
      ? { closed: false, opens: opening.opens, closes: opening.closes }
      : { closed: true, opens: "", closes: "" };
  }
  return rows;
}

/** Fresh confirmed hours win; otherwise the parser's reading of the hours text prefills the rows. */
function initialRows(hoursText: string, confirmedHours: ConfirmedHoursState): { rows: Rows; source: Source } {
  if (confirmedHours.weekly && !confirmedHours.stale) {
    return { rows: rowsFrom(confirmedHours.weekly), source: "confirmed" };
  }
  const reading = parseOpeningHours(hoursText);
  return { rows: rowsFrom(toHoursWeekly(reading)), source: reading.completeness };
}

function rowIsValid(row: DayRow): boolean {
  return row.closed || (HHMM.test(row.opens) && HHMM.test(row.closes) && row.opens < row.closes);
}

function toWeekly(rows: Rows): HoursWeekly {
  const weekly = {} as HoursWeekly;
  for (const day of HOURS_WEEKLY_DAYS) {
    const row = rows[day];
    weekly[day] = row.closed ? null : { opens: row.opens, closes: row.closes };
  }
  return weekly;
}

/**
 * Seven-row weekly hours editor under the free-text hours field (spec 101). Staff check the prefilled days and
 * confirm them; the server stores them with a hash of the hours text, so editing the text marks them stale.
 */
export function WeeklyHoursEditor({
  hoursText,
  confirmedHours,
  disabled,
  confirming,
  confirmDisabledReason,
  refusal,
  onConfirm,
}: Props) {
  const { formatDate, t } = useI18n();
  const signature = JSON.stringify([hoursText, confirmedHours.stale ? null : confirmedHours.weekly]);
  const [state, setState] = useState(() => ({ signature, ...initialRows(hoursText, confirmedHours) }));
  // A new hours text or a new confirmation re-fills the rows, so they always start from what was saved.
  const current = state.signature === signature ? state : { signature, ...initialRows(hoursText, confirmedHours) };
  if (current !== state) setState(current);
  const { rows, source } = current;
  const allValid = HOURS_WEEKLY_DAYS.every((day) => rowIsValid(rows[day]));

  const updateRow = (day: HoursWeeklyDay, patch: Partial<DayRow>) => {
    setState((previous) => ({
      ...previous,
      rows: { ...previous.rows, [day]: { ...previous.rows[day], ...patch } },
    }));
  };

  const confirmedLabel = confirmedHours.confirmedAt
    ? t("hours.confirmedOn", { date: formatDate(confirmedHours.confirmedAt) })
    : t("hours.notConfirmed");

  return (
    <section className="weekly-hours" aria-labelledby="weekly-hours-title">
      <div className="weekly-hours-heading">
        <h3 id="weekly-hours-title">{t("hours.weeklyTitle")}</h3>
        {confirmedHours.stale && <span className="unresolved-label">{t("hours.staleLabel")}</span>}
      </div>
      <p className="field-help">{t("hours.weeklyHelp")}</p>
      <p className="field-help" role="status">
        {confirmedHours.stale ? `${t("hours.stale")} ` : ""}
        {confirmedLabel} {t(sourceKeys[source])}
      </p>
      <div className="weekly-hours-rows">
        {HOURS_WEEKLY_DAYS.map((day) => {
          const row = rows[day];
          const dayName = t(`hours.day.${day}` as MessageKey);
          const refused = refusal !== null && refusal.day === day;
          const invalid = !rowIsValid(row) || refused;
          const errorId = `weekly-hours-error-${day}`;
          return (
            <div
              className={invalid ? "weekly-hours-row is-invalid" : "weekly-hours-row"}
              role="group"
              aria-label={dayName}
              aria-describedby={invalid ? errorId : undefined}
              key={day}
            >
              <span className="weekly-hours-day">{dayName}</span>
              <label className="weekly-hours-closed">
                <input
                  type="checkbox"
                  aria-label={t("hours.closedOn", { day: dayName })}
                  checked={row.closed}
                  disabled={disabled}
                  onChange={(event) => updateRow(day, { closed: event.target.checked })}
                />
                <span aria-hidden="true">{t("hours.closed")}</span>
              </label>
              <input
                type="time"
                aria-label={t("hours.opensOn", { day: dayName })}
                aria-invalid={invalid}
                value={row.opens}
                disabled={disabled || row.closed}
                onChange={(event) => updateRow(day, { opens: event.target.value })}
              />
              <input
                type="time"
                aria-label={t("hours.closesOn", { day: dayName })}
                aria-invalid={invalid}
                value={row.closes}
                disabled={disabled || row.closed}
                onChange={(event) => updateRow(day, { closes: event.target.value })}
              />
              {invalid && (
                <small id={errorId} className="field-error">
                  {refused ? t("hours.serverRefusedDay", { day: dayName }) : t("hours.rowInvalid")}
                </small>
              )}
            </div>
          );
        })}
      </div>
      {refusal !== null && refusal.day === null && (
        <p className="field-error" role="alert">{t("hours.serverRefused")}</p>
      )}
      <button
        className="outlined-button"
        type="button"
        disabled={disabled || confirming || !allValid || confirmDisabledReason !== null}
        title={confirmDisabledReason ?? undefined}
        onClick={() => onConfirm(toWeekly(rows))}
      >
        {t(confirming ? "hours.confirming" : "hours.confirm")}
      </button>
    </section>
  );
}
