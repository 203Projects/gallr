import { useEffect, useMemo, useState } from "react";
import { DialogFrame } from "./Dialogs";
import { downloadBytes } from "./CarouselExportDialog";
import { useI18n } from "../i18n";
import { CarouselExportTimeoutError, exportCarousel } from "../export/carouselCanvas";
import { carouselSourceOf } from "../export/carouselSource";
import { carouselArchiveName, exhibitionCarouselArchiveName } from "../export/carouselZip";
import { PosterUnavailableError } from "../export/posterPalette";
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
  CarouselExhibition,
  CuratorNoteLanguage,
} from "../repositories/AdminCuratorNoteRepository";

type Recorded = "yes" | "no" | "failed";

type Phase =
  | { kind: "idle" }
  | { kind: "working" }
  | { kind: "done"; omitted: number; recorded: Recorded; roundup: boolean }
  | { kind: "failed"; reason: "cover" | "timeout" | "other"; name?: string };

/** Today's date in Seoul as YYYY-MM-DD, the calendar the catalogue's closing dates are in. */
export function seoulToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}

/**
 * Builds an Instagram carousel from any published exhibitions still on (spec 090 FR-015c), in the shape of the
 * owner's reference posts. One exhibition gives a carousel of its own: its approved note's when it has one,
 * otherwise its name over the cover, its pictures and its facts. Two to ten give a roundup under a cover line the
 * editor writes, each exhibition on a slide with its picture, name, place, dates and, when it has an approved note,
 * the note's hook. Exports are recorded against the notes they draw on; a roundup is recorded only when every
 * exhibition in it has an approved note, because the record belongs to the notes.
 */
export function ExhibitionCarouselDialog({
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
  const [exhibitions, setExhibitions] = useState<CarouselExhibition[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [picked, setPicked] = useState<ReadonlySet<string>>(new Set());
  const [title, setTitle] = useState("");
  const [language, setLanguage] = useState<CuratorNoteLanguage>("ko");
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });

  useEffect(() => {
    let active = true;
    repository.listCarouselExhibitions(today).then(
      (loaded) => {
        if (active) setExhibitions(loaded);
      },
      () => {
        if (active) setLoadFailed(true);
      },
    );
    return () => {
      active = false;
    };
  }, [repository, today]);

  const entries = useMemo(
    () => (exhibitions ?? []).filter((exhibition) => picked.has(exhibition.exhibitionId)),
    [exhibitions, picked],
  );
  const roundup = entries.length >= MIN_ROUNDUP_ENTRIES;
  const full = entries.length >= MAX_ROUNDUP_ENTRIES;
  const ready = entries.length >= 1 && (!roundup || title.trim() !== "") && phase.kind !== "working";

  const toggle = (exhibition: CarouselExhibition) => {
    setPicked((current) => {
      const next = new Set(current);
      if (next.has(exhibition.exhibitionId)) next.delete(exhibition.exhibitionId);
      else if (next.size < MAX_ROUNDUP_ENTRIES) next.add(exhibition.exhibitionId);
      return next;
    });
    setPhase({ kind: "idle" });
  };

  const pickClosing = () => {
    const closing = closingInMonth(exhibitions ?? [], today);
    setPicked(new Set(closing.map((exhibition) => exhibition.exhibitionId)));
    if (closing.length >= MIN_ROUNDUP_ENTRIES) setTitle(closingTitle(language, today, closing.length));
    setPhase({ kind: "idle" });
  };

  /** One exhibition: the approved note's carousel when it has one, otherwise the exhibition's own. */
  const exportSingle = async (exhibition: CarouselExhibition): Promise<{ omitted: number; recorded: Recorded }> => {
    const facts = await repository.get(exhibition.exhibitionId);
    const note = facts.versions.find((version) => version.id === exhibition.versionId && version.status === "approved") ?? null;
    const result = await exportCarousel({ note, language, ...carouselSourceOf(facts) });
    save(
      result.archive,
      note
        ? carouselArchiveName(exhibition.exhibitionId, note.versionNumber, language)
        : exhibitionCarouselArchiveName(exhibition.exhibitionId, language),
    );
    if (!note) return { omitted: result.layout.omitted.length, recorded: "no" };
    try {
      await repository.recordExport(note.id, language, result.slideCount);
      return { omitted: result.layout.omitted.length, recorded: "yes" };
    } catch {
      return { omitted: result.layout.omitted.length, recorded: "failed" };
    }
  };

  const exportSet = async (): Promise<{ omitted: number; recorded: Recorded }> => {
    const result = await exportRoundup({ title, entries, language });
    save(result.archive, roundupArchiveName(language, entries.length, today));
    const versionIds = entries.flatMap((entry) => (entry.versionId ? [entry.versionId] : []));
    if (versionIds.length !== entries.length) return { omitted: result.layout.omitted.length, recorded: "no" };
    try {
      await repository.recordRoundupExport(versionIds, language, result.slideCount);
      return { omitted: result.layout.omitted.length, recorded: "yes" };
    } catch {
      return { omitted: result.layout.omitted.length, recorded: "failed" };
    }
  };

  const run = async () => {
    if (!ready) return;
    setPhase({ kind: "working" });
    try {
      const outcome = roundup ? await exportSet() : await exportSingle(entries[0]);
      setPhase({ kind: "done", ...outcome, roundup });
    } catch (error) {
      if (error instanceof RoundupPosterUnavailableError) {
        const missing = entries.find((entry) => entry.exhibitionId === error.exhibitionId);
        setPhase({ kind: "failed", reason: "cover", name: missing ? localized(missing.nameKo, missing.nameEn) : undefined });
      } else if (error instanceof PosterUnavailableError) {
        setPhase({ kind: "failed", reason: "cover", name: localized(entries[0].nameKo, entries[0].nameEn) });
      } else if (error instanceof CarouselExportTimeoutError) setPhase({ kind: "failed", reason: "timeout" });
      else setPhase({ kind: "failed", reason: "other" });
    }
  };

  const doneMessage = (done: Extract<Phase, { kind: "done" }>) => {
    if (done.recorded === "failed") return t("notes.carousel.doneUnrecorded");
    if (done.recorded === "no") return t(done.roundup ? "notes.carousel.doneMixed" : "notes.carousel.doneExhibition");
    return t(done.roundup ? "notes.roundup.done" : "notes.carousel.doneNote");
  };

  return (
    <DialogFrame
      title={t("notes.carousel.title")}
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
      <p>{t("notes.carousel.body")}</p>
      <div className="field">
        <label htmlFor="curator-roundup-title">{t("notes.roundup.coverLine")}</label>
        <input
          id="curator-roundup-title"
          type="text"
          maxLength={60}
          value={title}
          disabled={!roundup}
          placeholder={t(roundup ? "notes.roundup.coverLinePlaceholder" : "notes.carousel.coverLineSingle")}
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
        {exhibitions !== null && exhibitions.length > 0 && (
          <button type="button" className="outlined-button" onClick={pickClosing}>
            {t("notes.roundup.closingThisMonth")}
          </button>
        )}
        {loadFailed && <p role="alert" className="inline-notice">{t("notes.carousel.loadFailed")}</p>}
        {exhibitions === null && !loadFailed && <p className="muted">{t("notes.carousel.loading")}</p>}
        {exhibitions !== null && exhibitions.length === 0 && <p className="muted">{t("notes.carousel.none")}</p>}
        <div className="curator-roundup-list">
          {(exhibitions ?? []).map((exhibition) => {
            const checked = picked.has(exhibition.exhibitionId);
            const noCover = exhibition.coverImageUrl === null;
            return (
              <label key={exhibition.exhibitionId} className="radio-row">
                <input
                  type="checkbox"
                  checked={checked}
                  disabled={noCover || (!checked && full)}
                  onChange={() => toggle(exhibition)}
                />
                <span>
                  <strong>{localized(exhibition.nameKo, exhibition.nameEn)}</strong>
                  <span className="muted">
                    {" "}· {localized(exhibition.venueNameKo, exhibition.venueNameEn)}
                    {exhibition.closingDate && <> · {t("notes.roundup.until", { date: exhibition.closingDate.replace(/-/g, ".") })}</>}
                    {exhibition.versionId && <> · {t("notes.carousel.hasNote")}</>}
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
          {doneMessage(phase)}
          {phase.omitted > 0 && <> {t("notes.roundup.omitted", { count: phase.omitted })}</>}
        </p>
      )}
      {phase.kind === "failed" && (
        <p role="alert" className="inline-notice">
          {phase.reason === "cover"
            ? t("notes.carousel.coverFailed", { name: phase.name ?? "" })
            : t(phase.reason === "timeout" ? "notes.roundup.timeout" : "notes.export.failed")}
        </p>
      )}
    </DialogFrame>
  );
}
