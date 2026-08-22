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
        {!hasCurrentOption && <option value={value}>{value} (unavailable)</option>}
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
  const labels = {
    saved: "All changes saved",
    dirty: "Unsaved changes",
    invalid: "Fix highlighted fields to save",
    saving: "Saving…",
    error: "! Save failed",
    conflict: "! A newer revision exists",
  };
  return (
    <span
      className={
        state === "error" || state === "conflict" || state === "invalid"
          ? "save-error"
          : "muted"
      }
    >
      {labels[state]}
    </span>
  );
}

function normalizeVenueSearch(value: string): string {
  return value.trim().toLocaleLowerCase().replace(/\s+/g, " ");
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
        <h3 id="venue-reuse-title">Reuse a past venue</h3>
        <p>Search previous exhibitions and fill only venue and map fields.</p>
      </div>
      <label className="field venue-reuse-search">
        <span>Search past venues</span>
        <input
          type="search"
          placeholder="Name, district, or address"
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
        <p className="venue-reuse-availability">Loading past venues…</p>
      )}
      {error && (
        <p className="venue-reuse-availability" role="alert">
          Past venue search is unavailable. Continue with the fields below.
        </p>
      )}
      {!loading && error === null && venues.length === 0 && (
        <p className="venue-reuse-availability">
          No past venues are available yet. Continue with the fields below.
        </p>
      )}
      {normalizedQuery.length > 0 && appliedVenueId === null && (
        matches.length > 0 ? (
          <ul className="venue-reuse-results" aria-label="Matching past venues">
            {matches.map((venue) => {
              const area = [venue.cityKo, venue.regionKo].filter(Boolean).join(" ");
              const accessibleLocation = [area, venue.addressKo]
                .filter(Boolean)
                .join(", ");
              return (
                <li key={venue.id}>
                  <button
                    type="button"
                    aria-label={`Use venue ${venue.nameKo}, ${accessibleLocation}`}
                    onClick={() => chooseVenue(venue)}
                  >
                    <span>
                      <strong>{venue.nameKo}</strong>
                      {venue.nameEn && <small>{venue.nameEn}</small>}
                    </span>
                    <span>
                      <strong>{area || "Area not set"}</strong>
                      <small>{venue.addressKo || "Address not set"}</small>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        ) : (
          <p className="venue-reuse-empty">
            No matching past venues. Continue with the fields below.
          </p>
        )
      )}
      {appliedVenueId !== null && (
        <p className="venue-reuse-applied" aria-live="polite">
          Venue details applied. Review the fields below.
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
    label: `${event.shortLabel || event.nameKo || event.nameEn || event.id} · ${event.startDate}${event.isActive ? "" : " · inactive"}`,
  }));
  const editorOptions = (lookups?.editors ?? []).map((editor) => ({
    value: editor.id,
    label: `${editor.nameKo || editor.nameEn || editor.id}${editor.titleKo ? ` · ${editor.titleKo}` : ""}${editor.isActive ? "" : " · inactive"}`,
  }));
  const lookupsDisabled = contentReadOnly || lookupsLoading || lookupsError !== null;
  const cityOptions = useMemo(() => {
    const cities = new Map<string, { value: string; label: string }>();
    for (const location of lookups?.locations ?? []) {
      if (!cities.has(location.cityKo)) {
        cities.set(location.cityKo, {
          value: location.cityKo,
          label: `${location.cityKo} / ${location.cityEn}`,
        });
      }
    }
    return [...cities.values()];
  }, [lookups?.locations]);
  const regionOptions = useMemo(
    () =>
      (lookups?.locations ?? [])
        .filter((location) => location.cityKo === exhibition.cityKo)
        .map((location) => ({
          value: location.regionKo,
          label: `${location.regionKo} / ${location.regionEn}`,
        })),
    [exhibition.cityKo, lookups?.locations],
  );
  const geocodeStatus = geocodeLoading
    ? "Searching for address matches…"
    : geocodeCandidates.length > 0
      ? `${geocodeCandidates.length} address ${geocodeCandidates.length === 1 ? "match" : "matches"} found.`
      : "";

  return (
    <aside className="exhibition-inspector" aria-label="Exhibition editor">
      <header className="inspector-header">
        <div className="inspector-title-row">
          <div>
            <h2>{exhibition.nameKo || "Untitled exhibition"}</h2>
            <p>{exhibition.status}</p>
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
                    ? "Publisher access is required."
                    : mediaLoading
                      ? "Wait for media details to finish loading."
                      : saveState !== "saved"
                        ? "Save the current changes first."
                        : undefined
                }
              >
                Discard draft
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
                    ? "Wait for media details to finish loading."
                    : saveState !== "saved"
                      ? "Save the current changes first."
                      : undefined
                }
              >
                Delete permanently
              </button>
            )}
            <button
              className="outlined-compact inspector-lifecycle-button"
              type="button"
              disabled={lifecycleDisabled}
              onClick={exhibition.status === "Archived" ? onRestore : onArchive}
              title={
                !publishAllowed
                  ? "Publisher access is required."
                  : saveState !== "saved"
                    ? "Save the current changes first."
                    : undefined
              }
            >
              <MoreIcon />
              {lifecycleBusy
                ? "Working…"
                : exhibition.status === "Archived"
                  ? "Restore"
                  : "Archive"}
            </button>
          </div>
          <button
            className="icon-button inspector-mobile-close"
            type="button"
            aria-label="Close editor"
            onClick={onClose}
          >
            <CloseIcon />
          </button>
        </div>
        <div className="revision-row">
          <span>Version</span>
          <strong>
            v{exhibition.versionNumber} · revision {exhibition.revision}
          </strong>
          <button className="outlined-compact" type="button" disabled>
            <HistoryIcon />
            View history
          </button>
        </div>
        <SaveState state={saveState} />
      </header>

      <div className="inspector-tabs" role="tablist" aria-label="Editor sections">
        {sections.map((item) => (
          <button
            type="button"
            role="tab"
            aria-selected={item === section}
            className={item === section ? "is-active" : ""}
            onClick={() => onSectionChange(item)}
            key={item}
          >
            {item}
          </button>
        ))}
      </div>

      <div className="inspector-content" key={section}>
        {section === "Basics" && (
          <>
            <Field
              label="전시명 (Korean)"
              required
              disabled={contentReadOnly}
              value={exhibition.nameKo}
              onChange={(value) => onChange("nameKo", value)}
            />
            <Field
              label="전시명 (English)"
              value={exhibition.nameEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("nameEn", value)}
            />
            <TextAreaField
              label="소개 (Korean)"
              value={exhibition.descriptionKo}
              disabled={contentReadOnly}
              onChange={(value) => onChange("descriptionKo", value)}
            />
            <TextAreaField
              label="크레딧 (Korean)"
              value={exhibition.creditsKo}
              disabled={contentReadOnly}
              onChange={(value) => onChange("creditsKo", value)}
            />
            <TextAreaField
              label="소개 (English)"
              value={exhibition.descriptionEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("descriptionEn", value)}
            />
            <TextAreaField
              label="Credits (English)"
              value={exhibition.creditsEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("creditsEn", value)}
            />
            <div className="media-label">Cover image</div>
            <div className="basics-cover-row">
              <div className="media-field basics-cover-field">
                {exhibition.coverImageUrl ? (
                  <img src={exhibition.coverImageUrl} alt={exhibition.coverAltEn} />
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
                  Manage images
                </button>
              </div>
            </div>
            <p className="field-help">Recommended: 1600 × 1067px · JPEG, PNG</p>
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
              label="Venue name (Korean)"
              required
              disabled={contentReadOnly}
              value={exhibition.venueNameKo}
              onChange={(value) => onChange("venueNameKo", value)}
            />
            <Field
              label="Venue name (English)"
              value={exhibition.venueNameEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("venueNameEn", value)}
            />
            <div className="field-pair">
              <SelectField
                label="City / province"
                value={exhibition.cityKo}
                placeholder="Choose an approved city"
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
                label="Region"
                value={exhibition.regionKo}
                placeholder="Choose an approved region"
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
                Choose an approved city and region, or select a NAVER geocoding result to fill them automatically.
              </p>
            )}
            <Field
              label="Address (Korean)"
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
                {geocodeLoading ? "Searching…" : "Find coordinates"}
              </button>
              <span className="muted">
                {geocodingMode === "fixture"
                  ? "Fixture-only lookup. Sample: 서울 용산구 한남대로 28. No NAVER request is made."
                  : "Searches NAVER Maps; no draft changes until you choose a result."}
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
              <ul className="geocode-results" aria-label="Address matches">
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
                          aria-label={`Review ${displayAddress} on NAVER Maps`}
                        >
                          Review map
                        </a>
                        <button
                          className="outlined-compact"
                          type="button"
                          aria-label={`Use location: ${displayAddress}`}
                          onClick={() => onApplyGeocodeCandidate(candidate)}
                        >
                          Use location
                        </button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
            <Field
              label="Address (English)"
              value={exhibition.addressEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("addressEn", value)}
            />
            <div className="field-pair coordinate-fields">
              <Field
                label="Latitude"
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
                label="Longitude"
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
                {validation.coordinateError}
              </p>
            )}
            <p className="field-help coordinate-help">
              Required to publish. Changing the searchable street address clears its coordinates; floor and unit details keep the confirmed pin.
            </p>
          </>
        )}

        {section === "Schedule" && (
          <>
            <div className="field-pair">
              <Field
                label="Opening date"
                type="date"
                required
                disabled={contentReadOnly}
                value={exhibition.openingDate}
                onChange={(value) => onChange("openingDate", value)}
              />
              <Field
                label="Closing date"
                type="date"
                required
                disabled={contentReadOnly}
                value={exhibition.closingDate}
                onChange={(value) => onChange("closingDate", value)}
              />
            </div>
            <TextAreaField
              label="Hours"
              value={exhibition.hours}
              disabled={contentReadOnly}
              onChange={(value) => onChange("hours", value)}
            />
            <Field
              label="Public contact"
              value={exhibition.contact}
              disabled={contentReadOnly}
              onChange={(value) => onChange("contact", value)}
            />
            <Field
              label="Exhibition ticket URL"
              type="url"
              inputMode="url"
              placeholder="https://tickets.example.com/exhibition"
              value={exhibition.ticketUrl}
              disabled={contentReadOnly}
              error={validation.ticketUrlError}
              onChange={(value) => onChange("ticketUrl", value)}
            />
            <div className="field-pair">
              <Field
                label="Reception date"
                type="date"
                value={exhibition.receptionDate}
                disabled={contentReadOnly}
                onChange={(value) => onChange("receptionDate", value)}
              />
              <Field
                label="Reception time"
                type="time"
                value={exhibition.receptionStartTime}
                disabled={contentReadOnly}
                onChange={(value) => onChange("receptionStartTime", value)}
              />
            </div>
            <Field
              label="Reception end time"
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
              label="Linked event"
              value={exhibition.eventId}
              placeholder="No linked event"
              options={eventOptions}
              disabled={lookupsDisabled}
              onChange={(value) => onChange("eventId", value)}
            />
            <SelectField
              label="Editorial attribution"
              value={exhibition.editorId}
              placeholder="No editor attribution"
              options={editorOptions}
              disabled={lookupsDisabled}
              onChange={(value) => onChange("editorId", value)}
            />
            <SelectField
              label="Featured status"
              value={exhibition.isFeatured ? "featured" : "standard"}
              placeholder="Choose featured status"
              options={[
                { value: "standard", label: "Standard listing" },
                { value: "featured", label: "Featured in app" },
              ]}
              disabled={contentReadOnly}
              onChange={(value) => onChange("isFeatured", value === "featured")}
            />
            {lookupsLoading && <p className="field-help">Loading event and editor choices…</p>}
            {lookupsError && (
              <p className="field-error" role="alert">
                Event and editor choices are unavailable. Existing assignments are preserved.
              </p>
            )}
            <fieldset className="curation-fields">
              <legend>Placement</legend>
              <label>
                <input
                  type="checkbox"
                  disabled={contentReadOnly}
                  checked={exhibition.isHomepageFeatured}
                  onChange={(event) =>
                    onChange("isHomepageFeatured", event.target.checked)
                  }
                />
                Featured on homepage
              </label>
            </fieldset>
          </>
        )}
      </div>

      <footer className="inspector-footer">
        <button className="outlined-button" type="button" onClick={onPreview}>
          Preview
        </button>
        <div className="publish-action">
          {mediaProcessing && (
            <p className="publish-processing-note" role="status">
              Image processing is automatic. Publish will unlock when it
              finishes, usually within one minute.
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
                  ? "Publisher access is required."
                  : mediaProcessing
                    ? "Image processing is automatic. Publish will unlock when it finishes."
                    : "Save the draft and complete required fields first."
                : undefined
            }
          >
            Publish
          </button>
        </div>
      </footer>
    </aside>
  );
}
