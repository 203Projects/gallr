import { useEffect, useMemo, useState } from "react";
import { DialogFrame } from "./Dialogs";
import { downloadBytes } from "./CarouselExportDialog";
import { useI18n } from "../i18n";
import { CarouselExportTimeoutError } from "../export/carouselCanvas";
import { exportRoundup, RoundupPosterUnavailableError } from "../export/roundupCanvas";
import {
  MAX_ROUNDUP_ENTRIES,
  MIN_ROUNDUP_ENTRIES,
  closingInMonth,
  closingTitle,
  roundupArchiveName,
} from "../export/roundup";
import type {
  AdminCuratorNoteRepository,
  ApprovedCuratorNote,
  CuratorNoteLanguage,
} from "../repositories/AdminCuratorNoteRepository";

type Phase =
  | { kind: "idle" }
  | { kind: "working" }
  | { kind: "done"; omitted: number; recorded: boolean }
  | { kind: "failed"; reason: "cover" | "timeout" | "other"; name?: string };

/** Today's date in Seoul as YYYY-MM-DD, the calendar the catalogue's closing dates are in. */
export function seoulToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}

/**
 * Builds one Instagram carousel from several approved notes (spec 090, roundups): the editor picks two to ten
 * exhibitions and writes the cover line; each exhibition gets a slide with its picture, name, place, dates and
 * the note's hook, and a list slide and a caption close the set. The export is recorded against every note in it.
 */
export function RoundupExportDialog({
  repository,
  onClose,
  save = downloadBytes,
  today = seoulToday(),
}: {
  repository: AdminCuratorNoteRepository;
  onClose: () => void;
  save?: (bytes: Uint8Array, filename: string) => void;
  today?: string;
}) {
  const { t, localized } = useI18n();
  const [notes, setNotes] = useState<ApprovedCuratorNote[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [picked, setPicked] = useState<ReadonlySet<string>>(new Set());
  const [title, setTitle] = useState("");
  const [language, setLanguage] = useState<CuratorNoteLanguage>("ko");
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });

  useEffect(() => {
    let active = true;
    repository.listApproved().then(
      (loaded) => {
        if (active) setNotes(loaded);
      },
      () => {
        if (active) setLoadFailed(true);
      },
    );
    return () => {
      active = false;
    };
  }, [repository]);

  const entries = useMemo(() => (notes ?? []).filter((note) => picked.has(note.versionId)), [notes, picked]);
  const full = entries.length >= MAX_ROUNDUP_ENTRIES;
  const ready = entries.length >= MIN_ROUNDUP_ENTRIES && title.trim() !== "" && phase.kind !== "working";

  const toggle = (note: ApprovedCuratorNote) => {
    setPicked((current) => {
      const next = new Set(current);
      if (next.has(note.versionId)) next.delete(note.versionId);
      else if (next.size < MAX_ROUNDUP_ENTRIES) next.add(note.versionId);
      return next;
    });
    setPhase({ kind: "idle" });
  };

  const pickClosing = () => {
    const closing = closingInMonth(notes ?? [], today);
    setPicked(new Set(closing.map((note) => note.versionId)));
    if (closing.length >= MIN_ROUNDUP_ENTRIES) setTitle(closingTitle(language, today, closing.length));
    setPhase({ kind: "idle" });
  };

  const run = async () => {
    if (!ready) return;
    setPhase({ kind: "working" });
    try {
      const result = await exportRoundup({ title, entries, language });
      save(result.archive, roundupArchiveName(language, entries.length, today));
      let recorded = true;
      try {
        await repository.recordRoundupExport(entries.map((entry) => entry.versionId), language, result.slideCount);
      } catch {
        recorded = false;
      }
      setPhase({ kind: "done", omitted: result.layout.omitted.length, recorded });
    } catch (error) {
      if (error instanceof RoundupPosterUnavailableError) {
        const missing = entries.find((entry) => entry.exhibitionId === error.exhibitionId);
        setPhase({ kind: "failed", reason: "cover", name: missing ? localized(missing.nameKo, missing.nameEn) : undefined });
      } else if (error instanceof CarouselExportTimeoutError) setPhase({ kind: "failed", reason: "timeout" });
      else setPhase({ kind: "failed", reason: "other" });
    }
  };

  return (
    <DialogFrame
      title={t("notes.roundup.title")}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="outlined-button" onClick={onClose}>
            {t("common.close")}
          </button>
          <button type="button" className="black-button" disabled={!ready} onClick={() => void run()}>
            {t(phase.kind === "working" ? "notes.export.working" : "notes.export.download")}
          </button>
        </>
      }
    >
      <p>{t("notes.roundup.body")}</p>
      <div className="field">
        <label htmlFor="curator-roundup-title">{t("notes.roundup.coverLine")}</label>
        <input
          id="curator-roundup-title"
          type="text"
          maxLength={60}
          value={title}
          placeholder={t("notes.roundup.coverLinePlaceholder")}
          onChange={(event) => setTitle(event.target.value)}
        />
      </div>
      <fieldset className="route-decline-reasons">
        <legend>{t("notes.export.language")}</legend>
        <label className="radio-row">
          <input type="radio" name="roundup-language" value="ko" checked={language === "ko"} onChange={() => setLanguage("ko")} />
          {t("notes.draft.ko")}
        </label>
        <label className="radio-row">
          <input type="radio" name="roundup-language" value="en" checked={language === "en"} onChange={() => setLanguage("en")} />
          {t("notes.draft.en")}
        </label>
      </fieldset>
      <fieldset className="route-decline-reasons curator-roundup-picker">
        <legend>{t("notes.roundup.pick", { count: entries.length, max: MAX_ROUNDUP_ENTRIES })}</legend>
        {notes !== null && notes.length > 0 && (
          <button type="button" className="outlined-button" onClick={pickClosing}>
            {t("notes.roundup.closingThisMonth")}
          </button>
        )}
        {loadFailed && <p role="alert" className="inline-notice">{t("notes.roundup.loadFailed")}</p>}
        {notes === null && !loadFailed && <p className="muted">{t("notes.roundup.loading")}</p>}
        {notes !== null && notes.length < MIN_ROUNDUP_ENTRIES && <p className="muted">{t("notes.roundup.tooFew")}</p>}
        <div className="curator-roundup-list">
          {(notes ?? []).map((note) => {
            const checked = picked.has(note.versionId);
            const noCover = note.coverImageUrl === null;
            return (
              <label key={note.versionId} className="radio-row">
                <input
                  type="checkbox"
                  checked={checked}
                  disabled={noCover || (!checked && full)}
                  onChange={() => toggle(note)}
                />
                <span>
                  <strong>{localized(note.nameKo, note.nameEn)}</strong>
                  <span className="muted">
                    {" "}· {localized(note.venueNameKo, note.venueNameEn)}
                    {note.closingDate && <> · {t("notes.roundup.until", { date: note.closingDate.replace(/-/g, ".") })}</>}
                    {noCover && <> · {t("notes.roundup.noCover")}</>}
                  </span>
                </span>
              </label>
            );
          })}
        </div>
      </fieldset>
      {phase.kind === "done" && (
        <p role="status">
          {t(phase.recorded ? "notes.roundup.done" : "notes.roundup.doneUnrecorded")}
          {phase.omitted > 0 && <> {t("notes.roundup.omitted", { count: phase.omitted })}</>}
        </p>
      )}
      {phase.kind === "failed" && (
        <p role="alert" className="inline-notice">
          {phase.reason === "cover"
            ? t("notes.roundup.coverFailed", { name: phase.name ?? "" })
            : t(phase.reason === "timeout" ? "notes.roundup.timeout" : "notes.export.failed")}
        </p>
      )}
    </DialogFrame>
  );
}
