import { useEffect, useMemo, useState } from "react";
import type {
  AdminExhibition,
  AdminExhibitionLookups,
  AdminExhibitionValidation,
  AdminGeocodeCandidate,
  AdminLocationLookup,
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaRole,
  AdminVenueLookup,
  InspectorSection,
  PublishReadiness,
} from "../domain";
import type { AdminGeocodingMode } from "../services/AdminGeocodingService";
import { CloseIcon, HistoryIcon, ImageIcon, MoreIcon } from "./Icons";
import { MediaEditor } from "./MediaEditor";
import { LanguageSwitch, useI18n, type MessageKey } from "../i18n";

interface ExhibitionInspectorProps {
  exhibition: AdminExhibition;
  section: InspectorSection;
  saveState:
    | "saved"
    | "dirty"
    | "invalid"
    | "saving"
    | "error"
    | "conflict";
  readiness: PublishReadiness;
  validation: AdminExhibitionValidation;
  lookups: AdminExhibitionLookups | null;
  lookupsLoading: boolean;
  lookupsError: string | null;
  publishAllowed: boolean;
  deleteAllowed: boolean;
  lifecycleBusy: boolean;
  media: AdminMediaAsset[];
  mediaLoading: boolean;
  mediaBusy: boolean;
  mediaError: string | null;
  mediaEditable: boolean;
  mediaReadOnlyReason: string | null;
  geocodeCandidates: AdminGeocodeCandidate[];
  geocodeLoading: boolean;
  geocodeError: string | null;
  geocodingMode: AdminGeocodingMode;
  onSectionChange: (section: InspectorSection) => void;
  onClose: () => void;
  onChange: (
    field: keyof AdminExhibition,
    value: string | boolean | null,
  ) => void;
  onPreview: () => void;
  onPublish: () => void;
  onArchive: () => void;
  onRestore: () => void;
  onDiscard: () => void;
  onDelete: () => void;
  onManageMedia: () => void;
  onMediaUpload: (file: File, role: AdminMediaRole) => void;
  onMediaMetadataSave: (
    assetId: string,
    patch: AdminMediaMetadataPatch,
  ) => void;
  onMediaReorder: (orderedAssetIds: string[]) => void;
  onMediaDetach: (assetId: string) => void;
  onMediaErrorClear: () => void;
  onFindCoordinates: () => void;
  onApplyGeocodeCandidate: (candidate: AdminGeocodeCandidate) => void;
  onApplyVenue: (venue: AdminVenueLookup) => void;
  onLocationChange: (location: AdminLocationLookup) => void;
}

const sections: InspectorSection[] = [
  "Basics",
  "Venue",
  "Schedule",
  "Media",
  "Curation",
];

const sectionKeys: Record<InspectorSection, MessageKey> = {
  Basics: "inspector.basics",
  Venue: "inspector.venue",
  Schedule: "inspector.schedule",
  Media: "inspector.media",
  Curation: "inspector.curation",
};

const statusKeys: Record<AdminExhibition["status"], MessageKey> = {
  Draft: "status.draft",
  Published: "status.published",
  Archived: "status.archived",
};

function Field({
  label,
  value,
  onChange,
  type = "text",
  inputMode,
  placeholder,
  required = false,
  disabled = false,
  invalid = false,
  describedBy,
  error = null,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  inputMode?: "decimal" | "email" | "numeric" | "search" | "tel" | "text" | "url";
  placeholder?: string;
  required?: boolean;
  disabled?: boolean;
  invalid?: boolean;
  describedBy?: string;
  error?: string | null;
}) {
  return (
    <label className="field">
      <span>
        {label}
        {required ? " *" : ""}
      </span>
      <input
        type={type}
        value={value}
        required={required}
        disabled={disabled}
        inputMode={inputMode}
        placeholder={placeholder}
        aria-invalid={invalid || Boolean(error)}
        aria-describedby={describedBy}
        onChange={(event) => onChange(event.target.value)}
      />
      {error && (
        <small className="field-error" role="alert">
          {error}
        </small>
      )}
    </label>
  );
}

function SelectField({
  label,
  value,
  placeholder,
  options,
  disabled,
  required = false,
  onChange,
}: {
  label: string;
  value: string;
  placeholder: string;
  options: Array<{ value: string; label: string }>;
  disabled: boolean;
  required?: boolean;
  onChange: (value: string) => void;
}) {
  const { t } = useI18n();
  const hasCurrentOption = value === "" || options.some((option) => option.value === value);
  return (
    <label className="field">
      <span>{label}</span>
      <select
        value={value}
        disabled={disabled}
        required={required}
        onChange={(event) => onChange(event.target.value)}
      >
        <option value="">{placeholder}</option>
        {!hasCurrentOption && <option value={value}>{value} ({t("common.unavailable")})</option>}
        {options.map((option) => (
          <option value={option.value} key={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}

function TextAreaField({
  label,
  value,
  onChange,
  disabled = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <textarea
        rows={3}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

function SaveState({ state }: { state: ExhibitionInspectorProps["saveState"] }) {
  const { t } = useI18n();
  const labels: Record<ExhibitionInspectorProps["saveState"], MessageKey> = {
    saved: "inspector.saved",
    dirty: "inspector.dirty",
    invalid: "inspector.invalid",
    saving: "inspector.saving",
    error: "inspector.saveFailed",
    conflict: "inspector.conflict",
  };
  return (
    <span
      className={
        state === "error" || state === "conflict" || state === "invalid"
          ? "save-error"
          : "muted"
      }
    >
      {t(labels[state])}
    </span>
  );
}

function normalizeVenueSearch(value: string): string {
  return value.trim().toLocaleLowerCase().replace(/\s+/g, " ");
}

function validationMessageKey(message: string | null): MessageKey | null {
  switch (message) {
    case "Add both latitude and longitude, or leave both blank.":
      return "validation.coordinatePair";
    case "Coordinates must be valid decimal numbers.":
      return "validation.coordinateNumber";
    case "Latitude must be between -90 and 90, and longitude between -180 and 180.":
      return "validation.coordinateRange";
    case "Enter a complete http:// or https:// URL.":
      return "validation.ticketUrl";
    default:
      return null;
  }
}

function VenueReuseSearch({
  venues,
  disabled,
  loading,
  error,
  exhibitionId,
  onApply,
}: {
  venues: AdminVenueLookup[];
  disabled: boolean;
  loading: boolean;
  error: string | null;
  exhibitionId: string;
  onApply: (venue: AdminVenueLookup) => void;
}) {
  const { locale, t, localized } = useI18n();
  const [query, setQuery] = useState("");
  const [appliedVenueId, setAppliedVenueId] = useState<string | null>(null);
  const normalizedQuery = normalizeVenueSearch(query);
  const matches = useMemo(() => {
    if (normalizedQuery.length === 0) return [];
    return venues
      .filter((venue) =>
        [
          venue.nameKo,
          venue.nameEn,
          venue.cityKo,
          venue.cityEn,
          venue.regionKo,
          venue.regionEn,
          venue.addressKo,
          venue.addressEn,
        ].some((value) =>
          normalizeVenueSearch(value).includes(normalizedQuery),
        ),
      )
      .slice(0, 6);
  }, [normalizedQuery, venues]);

  useEffect(() => {
    setQuery("");
    setAppliedVenueId(null);
  }, [exhibitionId]);

  const chooseVenue = (venue: AdminVenueLookup) => {
    onApply(venue);
    setQuery(venue.nameKo);
    setAppliedVenueId(venue.id);
  };

  return (
    <section className="venue-reuse" aria-labelledby="venue-reuse-title">
      <div className="venue-reuse-heading">
        <h3 id="venue-reuse-title">{t("inspector.reuseVenue")}</h3>
        <p>{t("inspector.reuseVenueHelp")}</p>
      </div>
      <label className="field venue-reuse-search">
        <span>{t("inspector.searchPastVenues")}</span>
        <input
          type="search"
          placeholder={t("inspector.venueSearchPlaceholder")}
          value={query}
          disabled={disabled}
          autoComplete="off"
          onChange={(event) => {
            setQuery(event.target.value);
            setAppliedVenueId(null);
          }}
        />
      </label>
      {loading && (
        <p className="venue-reuse-availability">{t("inspector.loadingVenues")}</p>
      )}
      {error && (
        <p className="venue-reuse-availability" role="alert">
          {t("inspector.venueSearchUnavailable")}
        </p>
      )}
      {!loading && error === null && venues.length === 0 && (
        <p className="venue-reuse-availability">
          {t("inspector.noPastVenues")}
        </p>
      )}
      {normalizedQuery.length > 0 && appliedVenueId === null && (
        matches.length > 0 ? (
          <ul className="venue-reuse-results" aria-label={t("inspector.matchingVenues")}>
            {matches.map((venue) => {
              const area = locale === "ko"
                ? [venue.cityKo, venue.regionKo].filter(Boolean).join(" ")
                : [venue.cityEn, venue.regionEn].filter(Boolean).join(" ");
              const accessibleLocation = [area, localized(venue.addressKo, venue.addressEn)]
                .filter(Boolean)
                .join(", ");
              return (
                <li key={venue.id}>
                  <button
                    type="button"
                    aria-label={t("inspector.useVenue", { name: localized(venue.nameKo, venue.nameEn), location: accessibleLocation })}
                    onClick={() => chooseVenue(venue)}
                  >
                    <span>
                      <strong>{localized(venue.nameKo, venue.nameEn)}</strong>
                      {(venue.nameKo && venue.nameEn) && <small>{localized(venue.nameEn, venue.nameKo)}</small>}
                    </span>
                    <span>
                      <strong>{area || t("inspector.areaNotSet")}</strong>
                      <small>{localized(venue.addressKo, venue.addressEn, t("inspector.addressNotSet"))}</small>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        ) : (
          <p className="venue-reuse-empty">
            {t("inspector.noVenueMatches")}
          </p>
        )
      )}
      {appliedVenueId !== null && (
        <p className="venue-reuse-applied" aria-live="polite">
          {t("inspector.venueApplied")}
        </p>
      )}
    </section>
  );
}

export function ExhibitionInspector({
  exhibition,
  section,
  saveState,
  readiness,
  validation,
  lookups,
  lookupsLoading,
  lookupsError,
  publishAllowed,
  deleteAllowed,
  lifecycleBusy,
  media,
  mediaLoading,
  mediaBusy,
  mediaError,
  mediaEditable,
  mediaReadOnlyReason,
  geocodeCandidates,
  geocodeLoading,
  geocodeError,
  geocodingMode,
  onSectionChange,
  onClose,
  onChange,
  onPreview,
  onPublish,
  onArchive,
  onRestore,
  onDiscard,
  onDelete,
  onManageMedia,
  onMediaUpload,
  onMediaMetadataSave,
  onMediaReorder,
  onMediaDetach,
  onMediaErrorClear,
  onFindCoordinates,
  onApplyGeocodeCandidate,
  onApplyVenue,
  onLocationChange,
}: ExhibitionInspectorProps) {
  const { locale, t, formatDate, formatNumber, localized } = useI18n();
  const contentReadOnly =
    exhibition.status === "Archived" || mediaBusy || saveState === "conflict";
  const approvedLocations = lookups?.locations ?? [];
  const locationApproved = [
    exhibition.cityKo,
    exhibition.cityEn,
    exhibition.regionKo,
    exhibition.regionEn,
  ].every((label) => label.trim().length > 0);
  const publishDisabled =
    !publishAllowed ||
    mediaBusy ||
    mediaLoading ||
    saveState !== "saved" ||
    (!Object.values(readiness).every(Boolean) || !locationApproved);
  const lifecycleDisabled =
    !publishAllowed || lifecycleBusy || mediaBusy || saveState !== "saved";
  const deleteEligible =
    deleteAllowed &&
    exhibition.status === "Draft" &&
    exhibition.publishedVersionId === null;
  const discardEligible =
    exhibition.status === "Draft" &&
    exhibition.publishedVersionId !== null &&
    exhibition.workingVersionId !== exhibition.publishedVersionId &&
    exhibition.hasUnpublishedChanges;
  const mediaProcessing =
    !readiness.mediaReady &&
    media.some(
      (asset) =>
        asset.status === "pending_upload" || asset.status === "ready",
    );
  const eventOptions = (lookups?.events ?? []).map((event) => ({
    value: event.id,
    label: `${event.shortLabel || localized(event.nameKo, event.nameEn, event.id)} · ${formatDate(event.startDate)}${event.isActive ? "" : ` · ${t("common.inactive")}`}`,
  }));
  const editorOptions = (lookups?.editors ?? []).map((editor) => ({
    value: editor.id,
    label: `${localized(editor.nameKo, editor.nameEn, editor.id)}${localized(editor.titleKo, editor.titleEn) ? ` · ${localized(editor.titleKo, editor.titleEn)}` : ""}${editor.isActive ? "" : ` · ${t("common.inactive")}`}`,
  }));
  const lookupsDisabled = contentReadOnly || lookupsLoading || lookupsError !== null;
  const cityOptions = useMemo(() => {
    const cities = new Map<string, { value: string; label: string }>();
    for (const location of lookups?.locations ?? []) {
      if (!cities.has(location.cityKo)) {
        cities.set(location.cityKo, {
          value: location.cityKo,
          label: locale === "ko"
            ? `${location.cityKo} / ${location.cityEn}`
            : `${location.cityEn} / ${location.cityKo}`,
        });
      }
    }
    return [...cities.values()];
  }, [locale, lookups?.locations]);
  const regionOptions = useMemo(
    () =>
      (lookups?.locations ?? [])
        .filter((location) => location.cityKo === exhibition.cityKo)
        .map((location) => ({
          value: location.regionKo,
          label: locale === "ko"
            ? `${location.regionKo} / ${location.regionEn}`
            : `${location.regionEn} / ${location.regionKo}`,
        })),
    [exhibition.cityKo, locale, lookups?.locations],
  );
  const geocodeStatus = geocodeLoading
    ? t("field.searchingMatches")
    : geocodeCandidates.length > 0
      ? t(geocodeCandidates.length === 1 ? "field.addressMatchFound" : "field.addressMatchesFound", {
          count: formatNumber(geocodeCandidates.length),
        })
      : "";

  return (
    <aside className="exhibition-inspector" aria-label={t("inspector.label")}>
      <header className="inspector-header">
        <div className="inspector-title-row">
          <div>
            <h2>{localized(exhibition.nameKo, exhibition.nameEn, t("common.untitledExhibition"))}</h2>
            <p>{t(statusKeys[exhibition.status])}</p>
          </div>
          <div className="inspector-lifecycle-actions">
            {discardEligible && (
              <button
                className="outlined-compact inspector-lifecycle-button"
                type="button"
                disabled={lifecycleDisabled || mediaLoading}
                onClick={onDiscard}
                title={
                  !publishAllowed
                    ? t("inspector.publisherRequired")
                    : mediaLoading
                      ? t("inspector.waitMedia")
                      : saveState !== "saved"
                        ? t("inspector.saveFirst")
                        : undefined
                }
              >
                {t("inspector.discardDraft")}
              </button>
            )}
            {deleteEligible && (
              <button
                className="outlined-compact inspector-delete-button"
                type="button"
                disabled={lifecycleDisabled || mediaLoading}
                onClick={onDelete}
                title={
                  mediaLoading
                    ? t("inspector.waitMedia")
                    : saveState !== "saved"
                      ? t("inspector.saveFirst")
                      : undefined
                }
              >
                {t("inspector.deletePermanently")}
              </button>
            )}
            <button
              className="outlined-compact inspector-lifecycle-button"
              type="button"
              disabled={lifecycleDisabled}
              onClick={exhibition.status === "Archived" ? onRestore : onArchive}
              title={
                !publishAllowed
                  ? t("inspector.publisherRequired")
                  : saveState !== "saved"
                    ? t("inspector.saveFirst")
                    : undefined
              }
            >
              <MoreIcon />
              {lifecycleBusy
                ? t("common.working")
                : exhibition.status === "Archived"
                  ? t("common.restore")
                  : t("common.archive")}
            </button>
          </div>
          <button
            className="icon-button inspector-mobile-close"
            type="button"
            aria-label={t("inspector.closeEditor")}
            onClick={onClose}
          >
            <CloseIcon />
          </button>
          <div className="inspector-language-switch">
            <LanguageSwitch />
          </div>
        </div>
        <div className="revision-row">
          <span>{t("inspector.version")}</span>
          <strong>
            {t("inspector.versionRevision", {
              version: formatNumber(exhibition.versionNumber),
              revision: formatNumber(exhibition.revision),
            })}
          </strong>
          <button className="outlined-compact" type="button" disabled>
            <HistoryIcon />
            {t("inspector.viewHistory")}
          </button>
        </div>
        <SaveState state={saveState} />
      </header>

      <div className="inspector-tabs" role="tablist" aria-label={t("inspector.label")}>
        {sections.map((item) => (
          <button
            type="button"
            role="tab"
            aria-selected={item === section}
            className={item === section ? "is-active" : ""}
            onClick={() => onSectionChange(item)}
            key={item}
          >
            {t(sectionKeys[item])}
          </button>
        ))}
      </div>

      <div className="inspector-content" key={section}>
        {section === "Basics" && (
          <>
            <Field
              label={t("field.exhibitionNameKo")}
              required
              disabled={contentReadOnly}
              value={exhibition.nameKo}
              onChange={(value) => onChange("nameKo", value)}
            />
            <Field
              label={t("field.exhibitionNameEn")}
              value={exhibition.nameEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("nameEn", value)}
            />
            <TextAreaField
              label={t("field.descriptionKo")}
              value={exhibition.descriptionKo}
              disabled={contentReadOnly}
              onChange={(value) => onChange("descriptionKo", value)}
            />
            <TextAreaField
              label={t("field.creditsKo")}
              value={exhibition.creditsKo}
              disabled={contentReadOnly}
              onChange={(value) => onChange("creditsKo", value)}
            />
            <TextAreaField
              label={t("field.descriptionEn")}
              value={exhibition.descriptionEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("descriptionEn", value)}
            />
            <TextAreaField
              label={t("field.creditsEn")}
              value={exhibition.creditsEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("creditsEn", value)}
            />
            <div className="media-label">{t("field.coverImage")}</div>
            <div className="basics-cover-row">
              <div className="media-field basics-cover-field">
                {exhibition.coverImageUrl ? (
                  <img src={exhibition.coverImageUrl} alt={localized(exhibition.coverAltKo, exhibition.coverAltEn)} />
                ) : (
                  <ImageIcon className="media-placeholder-icon" />
                )}
              </div>
              <div className="basics-cover-actions">
                <button
                  className="outlined-compact"
                  type="button"
                  disabled={mediaBusy || saveState !== "saved"}
                  onClick={onManageMedia}
                >
                  {t("field.manageImages")}
                </button>
              </div>
            </div>
            <p className="field-help">{t("field.imageRecommendation")}</p>
          </>
        )}

        {section === "Venue" && (
          <>
            <VenueReuseSearch
              venues={lookups?.venues ?? []}
              disabled={lookupsDisabled}
              loading={lookupsLoading}
              error={lookupsError}
              exhibitionId={exhibition.id}
              onApply={onApplyVenue}
            />
            <Field
              label={t("field.venueNameKo")}
              required
              disabled={contentReadOnly}
              value={exhibition.venueNameKo}
              onChange={(value) => onChange("venueNameKo", value)}
            />
            <Field
              label={t("field.venueNameEn")}
              value={exhibition.venueNameEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("venueNameEn", value)}
            />
            <div className="field-pair">
              <SelectField
                label={t("field.city")}
                value={exhibition.cityKo}
                placeholder={t("field.chooseCity")}
                options={cityOptions}
                required
                disabled={lookupsDisabled}
                onChange={(cityKo) => {
                  const city = approvedLocations.find(
                    (location) => location.cityKo === cityKo,
                  );
                  onLocationChange({
                    cityKo: city?.cityKo ?? "",
                    cityEn: city?.cityEn ?? "",
                    regionKo: "",
                    regionEn: "",
                  });
                }}
              />
              <SelectField
                label={t("field.region")}
                value={exhibition.regionKo}
                placeholder={t("field.chooseRegion")}
                options={regionOptions}
                required
                disabled={lookupsDisabled || exhibition.cityKo.length === 0}
                onChange={(regionKo) => {
                  const location = approvedLocations.find(
                    (candidate) =>
                      candidate.cityKo === exhibition.cityKo &&
                      candidate.regionKo === regionKo,
                  );
                  onLocationChange(
                    location ?? {
                      cityKo: exhibition.cityKo,
                      cityEn: exhibition.cityEn,
                      regionKo: "",
                      regionEn: "",
                    },
                  );
                }}
              />
            </div>
            {!lookupsLoading && !locationApproved && (
              <p className="field-error">
                {t("field.chooseLocation")}
              </p>
            )}
            <Field
              label={t("field.addressKo")}
              required
              value={exhibition.addressKo}
              disabled={contentReadOnly}
              onChange={(value) => onChange("addressKo", value)}
            />
            <div className="geocode-actions">
              <button
                className="outlined-compact"
                type="button"
                disabled={
                  contentReadOnly ||
                  geocodeLoading ||
                  exhibition.addressKo.trim().length === 0
                }
                onClick={onFindCoordinates}
              >
                {t(geocodeLoading ? "field.searching" : "field.findCoordinates")}
              </button>
              <span className="muted">
                {geocodingMode === "fixture"
                  ? t("field.fixtureLookup")
                  : t("field.naverLookup")}
              </span>
            </div>
            <div
              className="visually-hidden"
              role="status"
              aria-live="polite"
              aria-atomic="true"
            >
              {geocodeStatus}
            </div>
            {geocodeError && (
              <p className="field-error geocode-error" role="alert">
                {geocodeError}
              </p>
            )}
            {geocodeCandidates.length > 0 && (
              <ul className="geocode-results" aria-label={t("field.addressMatches")}>
                {geocodeCandidates.map((candidate) => {
                  const displayAddress =
                    candidate.roadAddress || candidate.jibunAddress;
                  const mapUrl = `https://map.naver.com/v5/search/${encodeURIComponent(displayAddress)}`;
                  return (
                    <li
                      key={`${candidate.latitude}:${candidate.longitude}:${displayAddress}`}
                    >
                      <div>
                        <strong>{displayAddress}</strong>
                        {candidate.jibunAddress &&
                          candidate.jibunAddress !== displayAddress && (
                            <small>{candidate.jibunAddress}</small>
                          )}
                        <small>
                          {candidate.latitude}, {candidate.longitude}
                        </small>
                      </div>
                      <div className="geocode-result-actions">
                        <a
                          className="text-button"
                          href={mapUrl}
                          target="_blank"
                          rel="noreferrer"
                          aria-label={t("field.reviewMapLabel", { address: displayAddress })}
                        >
                          {t("field.reviewMap")}
                        </a>
                        <button
                          className="outlined-compact"
                          type="button"
                          aria-label={t("field.useLocationLabel", { address: displayAddress })}
                          onClick={() => onApplyGeocodeCandidate(candidate)}
                        >
                          {t("field.useLocation")}
                        </button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
            <Field
              label={t("field.addressEn")}
              value={exhibition.addressEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("addressEn", value)}
            />
            <div className="field-pair coordinate-fields">
              <Field
                label={t("field.latitude")}
                required
                inputMode="decimal"
                placeholder="37.5665"
                value={exhibition.latitude}
                disabled={contentReadOnly}
                invalid={validation.coordinateError !== null}
                describedBy="coordinate-error"
                onChange={(value) => onChange("latitude", value)}
              />
              <Field
                label={t("field.longitude")}
                required
                inputMode="decimal"
                placeholder="126.9780"
                value={exhibition.longitude}
                disabled={contentReadOnly}
                invalid={validation.coordinateError !== null}
                describedBy="coordinate-error"
                onChange={(value) => onChange("longitude", value)}
              />
            </div>
            {validation.coordinateError && (
              <p className="field-error coordinate-error" id="coordinate-error" role="alert">
                {validationMessageKey(validation.coordinateError)
                  ? t(validationMessageKey(validation.coordinateError)!)
                  : validation.coordinateError}
              </p>
            )}
            <p className="field-help coordinate-help">
              {t("field.coordinateHelp")}
            </p>
          </>
        )}

        {section === "Schedule" && (
          <>
            <div className="field-pair">
              <Field
                label={t("field.openingDate")}
                type="date"
                required
                disabled={contentReadOnly}
                value={exhibition.openingDate}
                onChange={(value) => onChange("openingDate", value)}
              />
              <Field
                label={t("field.closingDate")}
                type="date"
                required
                disabled={contentReadOnly}
                value={exhibition.closingDate}
                onChange={(value) => onChange("closingDate", value)}
              />
            </div>
            <TextAreaField
              label={t("field.hours")}
              value={exhibition.hours}
              disabled={contentReadOnly}
              onChange={(value) => onChange("hours", value)}
            />
            <Field
              label={t("field.publicContact")}
              value={exhibition.contact}
              disabled={contentReadOnly}
              onChange={(value) => onChange("contact", value)}
            />
            <Field
              label={t("field.ticketUrl")}
              type="url"
              inputMode="url"
              placeholder="https://tickets.example.com/exhibition"
              value={exhibition.ticketUrl}
              disabled={contentReadOnly}
              error={validationMessageKey(validation.ticketUrlError)
                ? t(validationMessageKey(validation.ticketUrlError)!)
                : validation.ticketUrlError}
              onChange={(value) => onChange("ticketUrl", value)}
            />
            <div className="field-pair">
              <Field
                label={t("field.receptionDate")}
                type="date"
                value={exhibition.receptionDate}
                disabled={contentReadOnly}
                onChange={(value) => onChange("receptionDate", value)}
              />
              <Field
                label={t("field.receptionTime")}
                type="time"
                value={exhibition.receptionStartTime}
                disabled={contentReadOnly}
                onChange={(value) => onChange("receptionStartTime", value)}
              />
            </div>
            <Field
              label={t("field.receptionEnd")}
              type="time"
              value={exhibition.receptionEndTime}
              disabled={contentReadOnly}
              onChange={(value) => onChange("receptionEndTime", value)}
            />
          </>
        )}

        {section === "Media" && (
          <MediaEditor
            media={media}
            loading={mediaLoading}
            busy={mediaBusy}
            error={mediaError}
            editable={mediaEditable}
            readOnlyReason={mediaReadOnlyReason}
            onUpload={onMediaUpload}
            onUpdateMetadata={onMediaMetadataSave}
            onReorder={onMediaReorder}
            onDetach={onMediaDetach}
            onClearError={onMediaErrorClear}
          />
        )}

        {section === "Curation" && (
          <>
            <SelectField
              label={t("field.linkedEvent")}
              value={exhibition.eventId}
              placeholder={t("field.noEvent")}
              options={eventOptions}
              disabled={lookupsDisabled}
              onChange={(value) => onChange("eventId", value)}
            />
            <SelectField
              label={t("field.editorAttribution")}
              value={exhibition.editorId}
              placeholder={t("field.noEditor")}
              options={editorOptions}
              disabled={lookupsDisabled}
              onChange={(value) => onChange("editorId", value)}
            />
            <SelectField
              label={t("field.featuredStatus")}
              value={exhibition.isFeatured ? "featured" : "standard"}
              placeholder={t("field.chooseFeatured")}
              options={[
                { value: "standard", label: t("field.standardListing") },
                { value: "featured", label: t("field.featuredApp") },
              ]}
              disabled={contentReadOnly}
              onChange={(value) => onChange("isFeatured", value === "featured")}
            />
            {lookupsLoading && <p className="field-help">{t("field.loadingAssociations")}</p>}
            {lookupsError && (
              <p className="field-error" role="alert">
                {t("field.associationsUnavailable")}
              </p>
            )}
            <fieldset className="curation-fields">
              <legend>{t("field.placement")}</legend>
              <label>
                <input
                  type="checkbox"
                  disabled={contentReadOnly}
                  checked={exhibition.isHomepageFeatured}
                  onChange={(event) =>
                    onChange("isHomepageFeatured", event.target.checked)
                  }
                />
                {t("field.homepageFeatured")}
              </label>
            </fieldset>
          </>
        )}
      </div>

      <footer className="inspector-footer">
        <button className="outlined-button" type="button" onClick={onPreview}>
          {t("common.preview")}
        </button>
        <div className="publish-action">
          {mediaProcessing && (
            <p className="publish-processing-note" role="status">
              {t("inspector.processing")}
            </p>
          )}
          <button
            className="accent-button"
            type="button"
            disabled={publishDisabled}
            onClick={onPublish}
            title={
              publishDisabled
                ? !publishAllowed
                  ? t("inspector.publisherRequired")
                  : mediaProcessing
                    ? t("inspector.processingTitle")
                    : t("inspector.completeFirst")
                : undefined
            }
          >
            {t("common.publish")}
          </button>
        </div>
      </footer>
    </aside>
  );
}
