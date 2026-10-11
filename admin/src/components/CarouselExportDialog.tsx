import { useState } from "react";
import { DialogFrame } from "./Dialogs";
import { useI18n } from "../i18n";
import { exportCarousel, CarouselExportTimeoutError } from "../export/carouselCanvas";
import { carouselArchiveName } from "../export/carouselZip";
import { PosterUnavailableError } from "../export/posterPalette";
import { publicExhibitionUrl } from "../export/publicExhibitionUrl";
import type { CuratorNoteFacts, CuratorNoteLanguage, CuratorNoteVersion } from "../repositories/AdminCuratorNoteRepository";

type Phase = { kind: "idle" } | { kind: "working" } | { kind: "done"; omitted: string[] } | { kind: "failed"; reason: "cover" | "timeout" | "other" };

/** Saves bytes as a download; separated so tests can stub it. */
export function downloadBytes(bytes: Uint8Array, filename: string) {
  const blob = new Blob([bytes as BlobPart], { type: "application/zip" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.rel = "noopener";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
}

/**
 * Exports an approved note as an Instagram carousel (spec 090 US4): language choice, a 1080×1350 cover,
 * two to four insight slides, a closing slide with dates, venue, the disclosure line and a QR to the public
 * page, plus a caption file, downloaded as one archive. The export is recorded once the download starts.
 */
export function CarouselExportDialog({
  version,
  facts,
  onClose,
  onExported,
  save = downloadBytes,
}: {
  version: CuratorNoteVersion;
  facts: CuratorNoteFacts;
  onClose: () => void;
  onExported: (language: CuratorNoteLanguage, slideCount: number) => Promise<void> | void;
  save?: (bytes: Uint8Array, filename: string) => void;
}) {
  const { t } = useI18n();
  const hasEnglish = version.hookEn.trim() !== "" && version.insightsEn.length > 0;
  const [language, setLanguage] = useState<CuratorNoteLanguage>("ko");
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });
  const packet = (facts.packet ?? {}) as Record<string, unknown>;
  const coverUrl = typeof packet.cover_image_url === "string" ? packet.cover_image_url : null;

  const run = async () => {
    if (phase.kind === "working") return;
    setPhase({ kind: "working" });
    try {
      const result = await exportCarousel({
        note: version,
        facts: packet,
        language,
        coverUrl,
        exhibitionUrl: publicExhibitionUrl({
          id: version.exhibitionId,
          nameEn: typeof packet.name_en === "string" ? packet.name_en : undefined,
          nameKo: typeof packet.name_ko === "string" ? packet.name_ko : undefined,
        }),
      });
      save(result.archive, carouselArchiveName(version.exhibitionId, version.versionNumber, language));
      setPhase({ kind: "done", omitted: result.layout.omitted });
      await onExported(language, result.slideCount);
    } catch (error) {
      if (error instanceof PosterUnavailableError) setPhase({ kind: "failed", reason: "cover" });
      else if (error instanceof CarouselExportTimeoutError) setPhase({ kind: "failed", reason: "timeout" });
      else setPhase({ kind: "failed", reason: "other" });
    }
  };

  return (
    <DialogFrame
      title={t("notes.export.title")}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="outlined-button" onClick={onClose}>
            {t("common.close")}
          </button>
          <button type="button" className="black-button" disabled={phase.kind === "working"} onClick={() => void run()}>
            {t(phase.kind === "working" ? "notes.export.working" : "notes.export.download")}
          </button>
        </>
      }
    >
      <p>{t("notes.export.body")}</p>
      <fieldset className="route-decline-reasons">
        <legend>{t("notes.export.language")}</legend>
        <label className="radio-row">
          <input type="radio" name="carousel-language" value="ko" checked={language === "ko"} onChange={() => setLanguage("ko")} />
          {t("notes.draft.ko")}
        </label>
        <label className="radio-row">
          <input
            type="radio"
            name="carousel-language"
            value="en"
            checked={language === "en"}
            disabled={!hasEnglish}
            onChange={() => setLanguage("en")}
          />
          {t("notes.draft.en")}
        </label>
      </fieldset>
      {!coverUrl && <p className="inline-notice">{t("notes.export.noCover")}</p>}
      {phase.kind === "done" && (
        <p role="status">
          {t("notes.export.done")}
          {phase.omitted.length > 0 && <> {t("notes.export.omitted", { count: phase.omitted.length })}</>}
        </p>
      )}
      {phase.kind === "failed" && (
        <p role="alert" className="inline-notice">
          {t(
            phase.reason === "cover"
              ? "notes.export.noCover"
              : phase.reason === "timeout"
              ? "notes.export.timeout"
              : "notes.export.failed",
          )}
        </p>
      )}
    </DialogFrame>
  );
}
