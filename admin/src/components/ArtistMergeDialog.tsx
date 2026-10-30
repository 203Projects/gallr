import { useCallback, useEffect, useRef, useState } from "react";
import type { ArtistMergeResult, DuplicateArtist, DuplicateArtistGroup } from "../domain";
import { useI18n, type MessageKey } from "../i18n";
import { ArtistMergeInvalidError } from "../repositories/AdminExhibitionRepository";
import { DialogFrame } from "./Dialogs";

const MAX_REASON_LENGTH = 400;
/** The database names which artist a refusal is about; the dialog says it the same way for both. */
const refusalMessages: Readonly<Record<string, string>> = {
  same_artist: "same_artist",
  survivor_archived: "archived",
  merged_archived: "archived",
  survivor_not_found: "not_found",
  merged_not_found: "not_found",
  reason_invalid: "reason",
};

export interface ArtistMergeActions {
  onFindDuplicates: () => Promise<DuplicateArtistGroup[]>;
  onMerge: (survivorId: string, mergedId: string, reason: string) => Promise<ArtistMergeResult>;
}

interface Props extends ArtistMergeActions {
  onClose: () => void;
}

type Outcome =
  | { kind: "merged"; result: ArtistMergeResult }
  | { kind: "refused"; reason: string | null }
  | { kind: "failed" };

/** The artist with the most credits is kept by default, so a merge moves as few credits as possible. */
function defaultPair(artists: DuplicateArtist[]): { survivorId: string; mergedId: string } {
  const ranked = [...artists].sort((left, right) => right.creditCount - left.creditCount);
  return { survivorId: ranked[0].id, mergedId: ranked[1].id };
}

function MergeGroupForm({
  group,
  busy,
  onMerge,
}: {
  group: DuplicateArtistGroup;
  busy: boolean;
  onMerge: (survivorId: string, mergedId: string, reason: string) => void;
}) {
  const { formatNumber, localized, t } = useI18n();
  const [{ survivorId, mergedId }, setPair] = useState(() => defaultPair(group.artists));
  const [reason, setReason] = useState("");
  const sameArtist = survivorId === mergedId;
  const reasonLength = Array.from(reason.trim()).length;
  const canMerge = !busy && !sameArtist && reasonLength >= 1 && reasonLength <= MAX_REASON_LENGTH;
  const option = (artist: DuplicateArtist) => (
    <option value={artist.id} key={artist.id}>
      {`${localized(artist.nameKo, artist.nameEn, artist.id)} · ${localized(artist.nameEn, artist.nameKo)} · ${t("merge.credits", { count: formatNumber(artist.creditCount) })}`}
    </option>
  );
  const groupLabel = t("merge.group", { key: group.key });

  return (
    <fieldset className="artist-merge-group">
      <legend>{groupLabel}</legend>
      <div className="field-pair">
        <label className="field">
          <span>{t("merge.survivor")}</span>
          <select
            value={survivorId}
            disabled={busy}
            onChange={(event) => setPair({ survivorId: event.target.value, mergedId })}
          >
            {group.artists.map(option)}
          </select>
        </label>
        <label className="field">
          <span>{t("merge.merged")}</span>
          <select
            value={mergedId}
            disabled={busy}
            onChange={(event) => setPair({ survivorId, mergedId: event.target.value })}
          >
            {group.artists.map(option)}
          </select>
        </label>
      </div>
      {sameArtist && <p className="field-error" role="alert">{t("merge.sameArtist")}</p>}
      <label className="field">
        <span>{t("merge.reason")}</span>
        <textarea
          rows={2}
          value={reason}
          maxLength={MAX_REASON_LENGTH}
          disabled={busy}
          onChange={(event) => setReason(event.target.value)}
        />
      </label>
      <p className="field-help">{t("merge.reasonHelp")}</p>
      <button
        className="outlined-button"
        type="button"
        disabled={!canMerge}
        onClick={() => onMerge(survivorId, mergedId, reason.trim())}
      >
        {t(busy ? "merge.merging" : "merge.merge")}
      </button>
    </fieldset>
  );
}

/**
 * The duplicate artist report and its merge form (spec 101), for the admin role only. Each group lists active
 * artists sharing a normalised name; merging keeps one and archives the other through `admin_merge_artists`.
 */
export function ArtistMergeDialog({ onClose, onFindDuplicates, onMerge }: Props) {
  const { formatNumber, t } = useI18n();
  const [groups, setGroups] = useState<DuplicateArtistGroup[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const mounted = useRef(true);
  const loadGeneration = useRef(0);

  const load = useCallback(async () => {
    const generation = ++loadGeneration.current;
    setLoadFailed(false);
    try {
      const next = await onFindDuplicates();
      if (mounted.current && loadGeneration.current === generation) setGroups(next);
    } catch {
      if (mounted.current && loadGeneration.current === generation) {
        setGroups(null);
        setLoadFailed(true);
      }
    }
  }, [onFindDuplicates]);

  useEffect(() => {
    mounted.current = true;
    void load();
    return () => {
      mounted.current = false;
    };
  }, [load]);

  const merge = async (key: string, survivorId: string, mergedId: string, reason: string) => {
    if (busyKey !== null) return;
    setBusyKey(key);
    setOutcome(null);
    try {
      const result = await onMerge(survivorId, mergedId, reason);
      if (!mounted.current) return;
      setOutcome({ kind: "merged", result });
      await load();
    } catch (error) {
      if (!mounted.current) return;
      setOutcome(
        error instanceof ArtistMergeInvalidError
          ? { kind: "refused", reason: error.reason }
          : { kind: "failed" },
      );
    } finally {
      if (mounted.current) setBusyKey(null);
    }
  };

  const outcomeText = (() => {
    if (outcome === null) return null;
    if (outcome.kind === "merged") {
      return t("merge.done", {
        moved: formatNumber(outcome.result.movedCredits),
        removed: formatNumber(outcome.result.removedDuplicateCredits),
      });
    }
    if (outcome.kind === "failed") return t("merge.failed");
    const known = outcome.reason === null ? undefined : refusalMessages[outcome.reason];
    return known !== undefined
      ? t(`merge.invalid.${known}` as MessageKey)
      : t("merge.invalid", { reason: outcome.reason ?? t("common.unavailable") });
  })();

  return (
    <DialogFrame
      title={t("merge.title")}
      onClose={onClose}
      footer={
        <button className="outlined-button" type="button" onClick={onClose}>
          {t("common.close")}
        </button>
      }
    >
      <p className="field-help">{t("merge.help")}</p>
      {outcomeText && (
        <p
          className={outcome?.kind === "merged" ? "field-help" : "field-error"}
          role={outcome?.kind === "merged" ? "status" : "alert"}
        >
          {outcomeText}
        </p>
      )}
      {loadFailed ? (
        <p className="field-error" role="alert">{t("merge.loadFailed")}</p>
      ) : groups === null ? (
        <p className="field-help" role="status">{t("merge.loading")}</p>
      ) : groups.length === 0 ? (
        <p className="field-help" role="status">{t("merge.none")}</p>
      ) : (
        <div className="artist-merge-groups">
          {groups.map((group) => (
            <MergeGroupForm
              key={`${group.key}:${group.artists.map(({ id }) => id).join(",")}`}
              group={group}
              busy={busyKey !== null}
              onMerge={(survivorId, mergedId, reason) => void merge(group.key, survivorId, mergedId, reason)}
            />
          ))}
        </div>
      )}
    </DialogFrame>
  );
}
