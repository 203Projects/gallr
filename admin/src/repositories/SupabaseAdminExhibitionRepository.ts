import type { SupabaseClient } from "@supabase/supabase-js";
import type {
  AdminExhibition,
  AdminExhibitionLookups,
  AdminExhibitionSubmission,
  AdminGalleryClaim,
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaMutationResult,
  AdminMediaRole,
  AdminMediaStatus,
  AdminMediaUploadTarget,
  AdminLocalPromotion,
  AdminSubmissionAcceptance,
  ArtistLookup,
  ArtistMergeResult,
  ArtTerm,
  ArtTermCategory,
  ConfirmedHoursState,
  DuplicateArtistGroup,
  ExhibitionArtist,
  ExhibitionArtMetadata,
  ExhibitionFilters,
  ExhibitionPatch,
  ExhibitionStatus,
  GalleryClaimFilters,
  GalleryClaimStatus,
  HoursWeekly,
  HoursWeeklyDay,
  LocalPromotionFilters,
  LocalPromotionStatus,
  MetadataReviewState,
  StaleExhibitionSummary,
  SubmissionFilters,
  SubmissionStatus,
} from "../domain";
import { HOURS_WEEKLY_DAYS, MAX_GROUNDING_LENGTH } from "../domain";
import {
  type AdminExhibitionRepository,
  ArtistDuplicateError,
  ArtistMergeInvalidError,
  ArtTermGroundingInvalidError,
  DraftDeleteBlockedError,
  ExhibitionChangedError,
  HoursWeeklyInvalidError,
  isDraftDeleteBlockedReason,
  RevisionConflictError,
} from "./AdminExhibitionRepository";
import {
  ADMIN_MEDIA_MIME_TYPES,
  assertValidAdminMediaFile,
  readImageDimensions,
  sha256File,
} from "./MediaFile";

type JsonRecord = Record<string, unknown>;

const MAX_ARTISTS = 32;
const MAX_ART_TERMS = 16;
const MAX_TERMS_PER_CATEGORY = 6;
const MAX_ART_TERM_LOOKUPS = 128;
const MAX_ARTIST_RESULTS = 20;
const MAX_ART_LABEL_LENGTH = 200;
const MAX_ARTIST_QUERY_LENGTH = 100;
const artTermCategories = new Set<ArtTermCategory>([
  "medium",
  "style",
  "theme",
  "mood",
  "visit",
]);
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const hhmmPattern = /^(?:[01]\d|2[0-3]):[0-5]\d$/u;
const MAX_MERGE_REASON_LENGTH = 400;

interface RpcErrorLike {
  code?: unknown;
  details?: unknown;
  hint?: unknown;
  message?: unknown;
}

export class MalformedAdminExhibitionPayloadError extends Error {
  constructor(
    readonly rpcName: string,
    readonly path: string,
    expected: string,
    actual: unknown,
  ) {
    super(
      `${rpcName} returned malformed data at ${path}: expected ${expected}, received ${describeType(actual)}.`,
    );
    this.name = "MalformedAdminExhibitionPayloadError";
  }
}

function describeType(value: unknown): string {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  return typeof value;
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readRecord(value: unknown, rpcName: string, path: string): JsonRecord {
  if (!isRecord(value)) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      path,
      "an object",
      value,
    );
  }
  return value;
}

function readString(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string {
  const value = record[key];
  if (typeof value !== "string") {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a string",
      value,
    );
  }
  return value;
}

function readNonEmptyString(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string {
  const value = readString(record, key, rpcName, path);
  if (value.trim().length === 0) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a non-empty string",
      value,
    );
  }
  return value;
}

function readBoolean(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): boolean {
  const value = record[key];
  if (typeof value !== "boolean") {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a boolean",
      value,
    );
  }
  return value;
}

function readPositiveInteger(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): number {
  const value = record[key];
  if (!Number.isSafeInteger(value) || (value as number) < 1) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a positive integer",
      value,
    );
  }
  return value as number;
}

function readNonNegativeInteger(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): number {
  const value = record[key];
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a non-negative integer",
      value,
    );
  }
  return value as number;
}

function readNullablePositiveInteger(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): number | null {
  const value = record[key];
  if (value === null) return null;
  if (!Number.isSafeInteger(value) || (value as number) < 1) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a positive integer or null",
      value,
    );
  }
  return value as number;
}

function readNullableString(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string | null {
  const value = record[key];
  if (value !== null && typeof value !== "string") {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a string or null",
      value,
    );
  }
  return value;
}

function readNullableNonEmptyString(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string | null {
  const value = readNullableString(record, key, rpcName, path);
  if (value !== null && value.trim().length === 0) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a non-empty string or null",
      value,
    );
  }
  return value;
}

function malformed(
  rpcName: string,
  path: string,
  expected: string,
  actual: unknown,
): never {
  throw new MalformedAdminExhibitionPayloadError(
    rpcName,
    path,
    expected,
    actual,
  );
}

function readArtLabel(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string {
  const value = readString(record, key, rpcName, path).trim();
  if (value.length > MAX_ART_LABEL_LENGTH) {
    malformed(rpcName, `${path}.${key}`, `at most ${MAX_ART_LABEL_LENGTH} characters`, value);
  }
  return value;
}

function mapArtistLookup(
  value: unknown,
  rpcName: string,
  path: string,
): ArtistLookup {
  const record = readRecord(value, rpcName, path);
  const id = readNonEmptyString(record, "id", rpcName, path);
  if (!uuidPattern.test(id)) {
    malformed(rpcName, `${path}.id`, "a UUID", id);
  }
  const nameKo = readArtLabel(record, "name_ko", rpcName, path);
  const nameEn = readArtLabel(record, "name_en", rpcName, path);
  if (!nameKo && !nameEn) {
    malformed(rpcName, path, "an artist with at least one localized name", value);
  }
  return { id, nameKo, nameEn };
}

function mapArtistList(value: unknown, rpcName: string): ArtistLookup[] {
  if (!Array.isArray(value) || value.length > MAX_ARTIST_RESULTS) {
    malformed(rpcName, "$", `an array of at most ${MAX_ARTIST_RESULTS} artists`, value);
  }
  const artists = value.map((item, index) =>
    mapArtistLookup(item, rpcName, `$[${index}]`),
  );
  if (new Set(artists.map(({ id }) => id)).size !== artists.length) {
    malformed(rpcName, "$", "artists with unique IDs", value);
  }
  return artists;
}

function mapArtTerm(
  value: unknown,
  rpcName: string,
  path: string,
): ArtTerm {
  const record = readRecord(value, rpcName, path);
  const category = readString(record, "category", rpcName, path) as ArtTermCategory;
  if (!artTermCategories.has(category)) {
    malformed(rpcName, `${path}.category`, "a supported art-term category", category);
  }
  const nameKo = readArtLabel(record, "name_ko", rpcName, path);
  const nameEn = readArtLabel(record, "name_en", rpcName, path);
  if (!nameKo || !nameEn) {
    malformed(rpcName, path, "a bilingual controlled art term", value);
  }
  return {
    id: readNonEmptyString(record, "id", rpcName, path),
    category,
    nameKo,
    nameEn,
  };
}

function mapArtTermList(
  value: unknown,
  rpcName: string,
  path: string,
  maximum = Number.POSITIVE_INFINITY,
): ArtTerm[] {
  if (!Array.isArray(value) || value.length > maximum) {
    malformed(rpcName, path, `an array of at most ${maximum} art terms`, value);
  }
  const terms = value.map((item, index) =>
    mapArtTerm(item, rpcName, `${path}[${index}]`),
  );
  if (new Set(terms.map(({ id }) => id)).size !== terms.length) {
    malformed(rpcName, path, "art terms with unique IDs", value);
  }
  const categoryCounts = new Map<ArtTermCategory, number>();
  for (const term of terms) {
    const count = (categoryCounts.get(term.category) ?? 0) + 1;
    if (count > MAX_TERMS_PER_CATEGORY && maximum === MAX_ART_TERMS) {
      malformed(rpcName, path, `at most ${MAX_TERMS_PER_CATEGORY} terms per category`, value);
    }
    categoryCounts.set(term.category, count);
  }
  return terms;
}

function mapArtMetadata(
  record: JsonRecord,
  rpcName: string,
  path: string,
): ExhibitionArtMetadata | null {
  const hasArtists = Object.prototype.hasOwnProperty.call(record, "artists");
  const hasTerms = Object.prototype.hasOwnProperty.call(record, "art_terms");
  if (!hasArtists && !hasTerms) return null;
  if (!hasArtists || !hasTerms) {
    malformed(rpcName, path, "both artists and art_terms arrays", record);
  }
  const rawArtists = record.artists;
  if (!Array.isArray(rawArtists) || rawArtists.length > MAX_ARTISTS) {
    malformed(rpcName, `${path}.artists`, `an array of at most ${MAX_ARTISTS} artists`, rawArtists);
  }
  const artists = rawArtists.map((item, index) => {
    const artistPath = `${path}.artists[${index}]`;
    const artist = readRecord(item, rpcName, artistPath);
    const rawId = artist.id;
    if (
      rawId !== null &&
      (typeof rawId !== "string" || !uuidPattern.test(rawId))
    ) {
      malformed(rpcName, `${artistPath}.id`, "a non-empty string or null", rawId);
    }
    const nameKo = readArtLabel(artist, "name_ko", rpcName, artistPath);
    const nameEn = readArtLabel(artist, "name_en", rpcName, artistPath);
    if (!nameKo && !nameEn) {
      malformed(rpcName, artistPath, "an artist with at least one localized name", item);
    }
    const credit: ExhibitionArtist = { id: rawId as string | null, nameKo, nameEn };
    // `grounding` arrives with the provenance migration; an older server omits it.
    if (Object.prototype.hasOwnProperty.call(artist, "grounding")) {
      credit.grounding = readGrounding(artist.grounding, rpcName, `${artistPath}.grounding`);
    }
    return credit;
  });
  const canonicalIds = artists.flatMap(({ id }) => id === null ? [] : [id]);
  const suggestions = artists
    .filter(({ id }) => id === null)
    .map(({ nameKo, nameEn }) => `${nameKo.toLocaleLowerCase()}\u0000${nameEn.toLocaleLowerCase()}`);
  if (
    new Set(canonicalIds).size !== canonicalIds.length ||
    new Set(suggestions).size !== suggestions.length
  ) {
    malformed(rpcName, `${path}.artists`, "unique artist credits", rawArtists);
  }
  const terms = mapArtTermList(record.art_terms, rpcName, `${path}.art_terms`, MAX_ART_TERMS);
  const metadata: ExhibitionArtMetadata = { artists, terms };
  if (Object.prototype.hasOwnProperty.call(record, "art_term_grounding")) {
    metadata.termGrounding = mapTermGrounding(
      record.art_term_grounding,
      new Set(terms.map(({ id }) => id)),
      rpcName,
      `${path}.art_term_grounding`,
    );
  }
  return metadata;
}

function readGrounding(value: unknown, rpcName: string, path: string): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value !== "string" || Array.from(value).length > MAX_GROUNDING_LENGTH) {
    malformed(rpcName, path, `a string of at most ${MAX_GROUNDING_LENGTH} characters or null`, value);
  }
  return value;
}

/** Grounding for a term that is no longer ticked is dropped: the server keeps it only on ticked terms. */
function mapTermGrounding(
  value: unknown,
  tickedTermIds: ReadonlySet<string>,
  rpcName: string,
  path: string,
): Record<string, string | null> {
  if (value === null) return {};
  const record = readRecord(value, rpcName, path);
  const grounding: Record<string, string | null> = {};
  for (const [termId, text] of Object.entries(record)) {
    const groundingText = readGrounding(text, rpcName, `${path}.${termId}`);
    if (tickedTermIds.has(termId)) grounding[termId] = groundingText;
  }
  return grounding;
}

function hasAnyKey(record: JsonRecord, keys: readonly string[]): boolean {
  return keys.some((key) => Object.prototype.hasOwnProperty.call(record, key));
}

function readOptionalTimestamp(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string | null {
  if (record[key] === undefined) return null;
  return readNullableNonEmptyString(record, key, rpcName, path);
}

/** `metadata_stale` and `metadata_reviewed_at` arrive with the provenance migration. */
function mapMetadataReview(
  record: JsonRecord,
  rpcName: string,
  path: string,
): MetadataReviewState | undefined {
  if (!hasAnyKey(record, ["metadata_stale", "metadata_reviewed_at"])) return undefined;
  return {
    stale: readBoolean(record, "metadata_stale", rpcName, path),
    reviewedAt: readOptionalTimestamp(record, "metadata_reviewed_at", rpcName, path),
  };
}

/** `hours_weekly`, `hours_confirmed_at` and `hours_stale` arrive with the confirmed-hours migration. */
function mapConfirmedHours(
  record: JsonRecord,
  rpcName: string,
  path: string,
): ConfirmedHoursState | undefined {
  if (!hasAnyKey(record, ["hours_weekly", "hours_confirmed_at", "hours_stale"])) return undefined;
  const weekly = record.hours_weekly ?? null;
  return {
    weekly: weekly === null ? null : mapHoursWeekly(weekly, rpcName, `${path}.hours_weekly`),
    confirmedAt: readOptionalTimestamp(record, "hours_confirmed_at", rpcName, path),
    stale: readBoolean(record, "hours_stale", rpcName, path),
  };
}

function mapHoursWeekly(value: unknown, rpcName: string, path: string): HoursWeekly {
  const record = readRecord(value, rpcName, path);
  const keys = Object.keys(record);
  if (
    keys.length !== HOURS_WEEKLY_DAYS.length ||
    !HOURS_WEEKLY_DAYS.every((day) => Object.prototype.hasOwnProperty.call(record, day))
  ) {
    malformed(rpcName, path, "exactly the seven weekday keys MONDAY…SUNDAY", value);
  }
  const weekly = {} as HoursWeekly;
  for (const day of HOURS_WEEKLY_DAYS) {
    const dayValue = record[day];
    if (dayValue === null) {
      weekly[day] = null;
      continue;
    }
    const dayPath = `${path}.${day}`;
    const opening = readRecord(dayValue, rpcName, dayPath);
    const opens = readString(opening, "opens", rpcName, dayPath);
    const closes = readString(opening, "closes", rpcName, dayPath);
    if (!hhmmPattern.test(opens) || !hhmmPattern.test(closes) || opens >= closes) {
      malformed(rpcName, dayPath, "null or HH:MM opens before closes", dayValue);
    }
    weekly[day] = { opens, closes };
  }
  return weekly;
}

function mapStaleExhibitionList(value: unknown, rpcName: string): StaleExhibitionSummary[] {
  if (!Array.isArray(value)) malformed(rpcName, "$", "an array", value);
  return value.map((item, index) => {
    const path = `$[${index}]`;
    const record = readRecord(item, rpcName, path);
    return {
      exhibitionId: readNonEmptyString(record, "exhibition_id", rpcName, path),
      nameKo: readString(record, "name_ko", rpcName, path),
      nameEn: readString(record, "name_en", rpcName, path),
      metadataStale: readBoolean(record, "metadata_stale", rpcName, path),
      hoursStale: readBoolean(record, "hours_stale", rpcName, path),
    };
  });
}

function mapDuplicateArtistGroups(value: unknown, rpcName: string): DuplicateArtistGroup[] {
  if (!Array.isArray(value)) malformed(rpcName, "$", "an array", value);
  return value.map((item, index) => {
    const path = `$[${index}]`;
    const record = readRecord(item, rpcName, path);
    const rawArtists = readArray(record, "artists", rpcName, path);
    if (rawArtists.length < 2) {
      malformed(rpcName, `${path}.artists`, "at least two artists", rawArtists);
    }
    const artists = rawArtists.map((rawArtist, artistIndex) => {
      const artistPath = `${path}.artists[${artistIndex}]`;
      const { id, nameKo, nameEn } = mapArtistLookup(rawArtist, rpcName, artistPath);
      const artistRecord = readRecord(rawArtist, rpcName, artistPath);
      return {
        id,
        nameKo,
        nameEn,
        creditCount: readNonNegativeInteger(artistRecord, "credit_count", rpcName, artistPath),
      };
    });
    if (new Set(artists.map(({ id }) => id)).size !== artists.length) {
      malformed(rpcName, `${path}.artists`, "artists with unique IDs", rawArtists);
    }
    return { key: readString(record, "key", rpcName, path), artists };
  });
}

function mapArtistMergeResult(value: unknown, rpcName: string): ArtistMergeResult {
  const record = readRecord(value, rpcName, "$");
  return {
    survivorId: readNonEmptyString(record, "survivor_id", rpcName, "$"),
    mergedId: readNonEmptyString(record, "merged_id", rpcName, "$"),
    movedCredits: readNonNegativeInteger(record, "moved_credits", rpcName, "$"),
    removedDuplicateCredits: readNonNegativeInteger(record, "removed_duplicate_credits", rpcName, "$"),
  };
}

/** Leaves the key out when the server did not send it, so records from an older database keep their shape. */
function optionalField<Key extends string, Value>(
  key: Key,
  value: Value | undefined,
): Partial<Record<Key, Value>> {
  return value === undefined ? {} : ({ [key]: value } as Record<Key, Value>);
}

function readStatus(
  record: JsonRecord,
  rpcName: string,
  path: string,
): ExhibitionStatus {
  const value = readString(record, "status", rpcName, path).toLowerCase();
  switch (value) {
    case "draft":
      return "Draft";
    case "published":
      return "Published";
    case "archived":
      return "Archived";
    default:
      throw new MalformedAdminExhibitionPayloadError(
        rpcName,
        `${path}.status`,
        'one of "draft", "published", or "archived"',
        record.status,
      );
  }
}

function mapExhibition(
  value: unknown,
  rpcName: string,
  path: string,
): AdminExhibition {
  const record = readRecord(value, rpcName, path);
  return {
    id: readNonEmptyString(record, "id", rpcName, path),
    workingVersionId: readNonEmptyString(
      record,
      "working_version_id",
      rpcName,
      path,
    ),
    versionNumber: readPositiveInteger(
      record,
      "version_number",
      rpcName,
      path,
    ),
    publishedVersionId: readNullableNonEmptyString(
      record,
      "published_version_id",
      rpcName,
      path,
    ),
    hasUnpublishedChanges: readBoolean(
      record,
      "has_unpublished_changes",
      rpcName,
      path,
    ),
    nameKo: readString(record, "name_ko", rpcName, path),
    nameEn: readString(record, "name_en", rpcName, path),
    venueNameKo: readString(record, "venue_name_ko", rpcName, path),
    venueNameEn: readString(record, "venue_name_en", rpcName, path),
    cityKo: readString(record, "city_ko", rpcName, path),
    cityEn: readString(record, "city_en", rpcName, path),
    regionKo: readString(record, "region_ko", rpcName, path),
    regionEn: readString(record, "region_en", rpcName, path),
    addressKo: readString(record, "address_ko", rpcName, path),
    addressEn: readString(record, "address_en", rpcName, path),
    latitude: readString(record, "latitude", rpcName, path),
    longitude: readString(record, "longitude", rpcName, path),
    openingDate: readString(record, "opening_date", rpcName, path),
    closingDate: readString(record, "closing_date", rpcName, path),
    descriptionKo: readString(record, "description_ko", rpcName, path),
    descriptionEn: readString(record, "description_en", rpcName, path),
    creditsKo: readString(record, "credits_ko", rpcName, path),
    creditsEn: readString(record, "credits_en", rpcName, path),
    artMetadata: mapArtMetadata(record, rpcName, path),
    ...optionalField("metadataReview", mapMetadataReview(record, rpcName, path)),
    ...optionalField("confirmedHours", mapConfirmedHours(record, rpcName, path)),
    hours: readString(record, "hours", rpcName, path),
    contact: readString(record, "contact", rpcName, path),
    receptionDate: readString(record, "reception_date", rpcName, path),
    receptionStartTime: readString(
      record,
      "reception_start_time",
      rpcName,
      path,
    ),
    receptionEndTime: readString(
      record,
      "reception_end_time",
      rpcName,
      path,
    ),
    eventId: readString(record, "event_id", rpcName, path),
    editorId: readString(record, "editor_id", rpcName, path),
    ticketUrl: readString(record, "ticket_url", rpcName, path),
    coverImageUrl: readNullableString(
      record,
      "cover_image_url",
      rpcName,
      path,
    ),
    coverAltKo: readString(record, "cover_alt_ko", rpcName, path),
    coverAltEn: readString(record, "cover_alt_en", rpcName, path),
    imageCredit: readString(record, "image_credit", rpcName, path),
    isFeatured: readBoolean(record, "is_featured", rpcName, path),
    isHomepageFeatured: readBoolean(
      record,
      "is_homepage_featured",
      rpcName,
      path,
    ),
    hasOpenOwnerSubmission: readBoolean(
      record,
      "has_open_owner_submission",
      rpcName,
      path,
    ),
    status: readStatus(record, rpcName, path),
    revision: readPositiveInteger(record, "revision", rpcName, path),
    createdAt: readNonEmptyString(record, "created_at", rpcName, path),
    publishedAt: readNullableNonEmptyString(
      record,
      "published_at",
      rpcName,
      path,
    ),
    updatedAt: readNonEmptyString(record, "updated_at", rpcName, path),
    updatedBy: readNonEmptyString(record, "updated_by", rpcName, path),
  };
}

function mapList(value: unknown, rpcName: string): AdminExhibition[] {
  if (!Array.isArray(value)) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      "$",
      "an array",
      value,
    );
  }
  return value.map((record, index) =>
    mapExhibition(record, rpcName, `$[${index}]`),
  );
}

function readArray(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): unknown[] {
  const value = record[key];
  if (!Array.isArray(value)) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "an array",
      value,
    );
  }
  return value;
}

function mapExhibitionLookups(
  value: unknown,
  rpcName: string,
): AdminExhibitionLookups {
  const record = readRecord(value, rpcName, "$");
  const rawVenues = record.venues === undefined
    ? []
    : readArray(record, "venues", rpcName, "$");
  const rawLocations = record.locations === undefined
    ? []
    : readArray(record, "locations", rpcName, "$");
  const artTerms = record.art_terms === undefined
    ? null
    : mapArtTermList(
        record.art_terms,
        rpcName,
        "$.art_terms",
        MAX_ART_TERM_LOOKUPS,
      );
  const locations = rawLocations.map((item, index) => {
    const path = `$.locations[${index}]`;
    const location = readRecord(item, rpcName, path);
    return {
      cityKo: readNonEmptyString(location, "city_ko", rpcName, path),
      cityEn: readNonEmptyString(location, "city_en", rpcName, path),
      regionKo: readNonEmptyString(location, "region_ko", rpcName, path),
      regionEn: readNonEmptyString(location, "region_en", rpcName, path),
    };
  });
  const venues = rawVenues.map(
    (item, index) => {
      const path = `$.venues[${index}]`;
      const venue = readRecord(item, rpcName, path);
      return {
        id: readNonEmptyString(venue, "id", rpcName, path),
        nameKo: readNonEmptyString(venue, "name_ko", rpcName, path),
        nameEn: readString(venue, "name_en", rpcName, path),
        cityKo: readString(venue, "city_ko", rpcName, path),
        cityEn: readString(venue, "city_en", rpcName, path),
        regionKo: readString(venue, "region_ko", rpcName, path),
        regionEn: readString(venue, "region_en", rpcName, path),
        addressKo: readString(venue, "address_ko", rpcName, path),
        addressEn: readString(venue, "address_en", rpcName, path),
        latitude: readString(venue, "latitude", rpcName, path),
        longitude: readString(venue, "longitude", rpcName, path),
      };
    },
  );
  const events = readArray(record, "events", rpcName, "$").map(
    (item, index) => {
      const path = `$.events[${index}]`;
      const event = readRecord(item, rpcName, path);
      return {
        id: readNonEmptyString(event, "id", rpcName, path),
        nameKo: readString(event, "name_ko", rpcName, path),
        nameEn: readString(event, "name_en", rpcName, path),
        locationLabelKo: readString(
          event,
          "location_label_ko",
          rpcName,
          path,
        ),
        locationLabelEn: readString(
          event,
          "location_label_en",
          rpcName,
          path,
        ),
        startDate: readString(event, "start_date", rpcName, path),
        endDate: readString(event, "end_date", rpcName, path),
        shortLabel: readNullableString(event, "short_label", rpcName, path),
        isActive: readBoolean(event, "is_active", rpcName, path),
      };
    },
  );
  const editors = readArray(record, "editors", rpcName, "$").map(
    (item, index) => {
      const path = `$.editors[${index}]`;
      const editor = readRecord(item, rpcName, path);
      return {
        id: readNonEmptyString(editor, "id", rpcName, path),
        nameKo: readString(editor, "name_ko", rpcName, path),
        nameEn: readString(editor, "name_en", rpcName, path),
        titleKo: readString(editor, "title_ko", rpcName, path),
        titleEn: readString(editor, "title_en", rpcName, path),
        isActive: readBoolean(editor, "is_active", rpcName, path),
        activeFrom: readNullableString(
          editor,
          "active_from",
          rpcName,
          path,
        ),
        activeTo: readNullableString(editor, "active_to", rpcName, path),
      };
    },
  );
  return { events, editors, venues, locations, artTerms };
}

function readMediaRole(
  record: JsonRecord,
  rpcName: string,
  path: string,
): AdminMediaRole {
  const value = readString(record, "role", rpcName, path);
  if (value === "cover" || value === "gallery") return value;
  throw new MalformedAdminExhibitionPayloadError(
    rpcName,
    `${path}.role`,
    '"cover" or "gallery"',
    value,
  );
}

function readMediaStatus(
  record: JsonRecord,
  rpcName: string,
  path: string,
): AdminMediaStatus {
  const value = readString(record, "status", rpcName, path);
  if (
    value === "pending_upload" ||
    value === "ready" ||
    value === "published" ||
    value === "orphaned" ||
    value === "rejected"
  ) {
    return value;
  }
  throw new MalformedAdminExhibitionPayloadError(
    rpcName,
    `${path}.status`,
    "a supported media status",
    value,
  );
}

function readMediaMimeType(
  record: JsonRecord,
  rpcName: string,
  path: string,
): string {
  const value = readNonEmptyString(record, "mime_type", rpcName, path);
  if ((ADMIN_MEDIA_MIME_TYPES as readonly string[]).includes(value)) return value;
  throw new MalformedAdminExhibitionPayloadError(
    rpcName,
    `${path}.mime_type`,
    "a supported image MIME type",
    value,
  );
}

function readNullableChecksum(
  record: JsonRecord,
  rpcName: string,
  path: string,
): string | null {
  const value = readNullableString(
    record,
    "checksum_sha256",
    rpcName,
    path,
  );
  if (value !== null && !/^[0-9a-f]{64}$/.test(value)) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.checksum_sha256`,
      "a lowercase SHA-256 digest or null",
      value,
    );
  }
  return value;
}

function mapMediaAsset(
  value: unknown,
  rpcName: string,
  path: string,
): AdminMediaAsset {
  const record = readRecord(value, rpcName, path);
  const role = readMediaRole(record, rpcName, path);
  const sortOrder = readNonNegativeInteger(record, "sort_order", rpcName, path);
  const status = readMediaStatus(record, rpcName, path);
  const width = readNullablePositiveInteger(record, "width", rpcName, path);
  const height = readNullablePositiveInteger(record, "height", rpcName, path);
  const publicUrl = readNullableString(record, "public_url", rpcName, path);
  if ((width === null) !== (height === null)) {
    throw new Error(`${rpcName} returned incomplete dimensions at ${path}.`);
  }
  if ((role === "cover" && sortOrder !== 0) || (role === "gallery" && sortOrder < 1)) {
    throw new Error(`${rpcName} returned an invalid media order at ${path}.`);
  }
  if (status === "published" && publicUrl === null) {
    throw new Error(`${rpcName} returned published media without a public URL at ${path}.`);
  }
  return {
    assetId: readNonEmptyString(record, "asset_id", rpcName, path),
    versionId: readNonEmptyString(record, "version_id", rpcName, path),
    role,
    sortOrder,
    status,
    bucketId: readNonEmptyString(record, "bucket_id", rpcName, path),
    objectPath: readNonEmptyString(record, "object_path", rpcName, path),
    mimeType: readMediaMimeType(record, rpcName, path),
    byteSize: readPositiveInteger(record, "byte_size", rpcName, path),
    width,
    height,
    checksumSha256: readNullableChecksum(record, rpcName, path),
    publicUrl,
    altKo: readString(record, "alt_ko", rpcName, path),
    altEn: readString(record, "alt_en", rpcName, path),
    credit: readString(record, "credit", rpcName, path),
    rightsUrl: readString(record, "rights_url", rpcName, path),
    originalFilename: readString(
      record,
      "original_filename",
      rpcName,
      path,
    ),
    createdAt: readNonEmptyString(record, "created_at", rpcName, path),
    updatedAt: readNonEmptyString(record, "updated_at", rpcName, path),
    previewUrl: null,
  };
}

function mapMediaList(
  value: unknown,
  rpcName: string,
  path = "$",
): AdminMediaAsset[] {
  if (!Array.isArray(value)) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      path,
      "an array",
      value,
    );
  }
  return value.map((record, index) =>
    mapMediaAsset(record, rpcName, `${path}[${index}]`),
  );
}

function mapUploadTarget(
  value: unknown,
  rpcName: string,
): AdminMediaUploadTarget {
  const record = readRecord(value, rpcName, "$");
  return {
    assetId: readNonEmptyString(record, "asset_id", rpcName, "$"),
    bucketId: readNonEmptyString(record, "bucket_id", rpcName, "$"),
    objectPath: readNonEmptyString(record, "object_path", rpcName, "$"),
    mimeType: readMediaMimeType(record, rpcName, "$"),
    byteSize: readPositiveInteger(record, "byte_size", rpcName, "$"),
    originalFilename: readString(record, "original_filename", rpcName, "$"),
    status: readMediaStatus(record, rpcName, "$"),
  };
}

function validateFinalizedAsset(
  value: unknown,
  rpcName: string,
  target: AdminMediaUploadTarget,
): void {
  const record = readRecord(value, rpcName, "$");
  const assetId = readNonEmptyString(record, "asset_id", rpcName, "$");
  const bucketId = readNonEmptyString(record, "bucket_id", rpcName, "$");
  const objectPath = readNonEmptyString(record, "object_path", rpcName, "$");
  readMediaStatus(record, rpcName, "$");
  readMediaMimeType(record, rpcName, "$");
  readPositiveInteger(record, "byte_size", rpcName, "$");
  readNullablePositiveInteger(record, "width", rpcName, "$");
  readNullablePositiveInteger(record, "height", rpcName, "$");
  readNullableChecksum(record, rpcName, "$");
  if (
    assetId !== target.assetId ||
    bucketId !== target.bucketId ||
    objectPath !== target.objectPath
  ) {
    throw new Error(`${rpcName} returned an asset that does not match the upload reservation.`);
  }
}

function mapMediaMutation(
  value: unknown,
  rpcName: string,
): AdminMediaMutationResult {
  const record = readRecord(value, rpcName, "$");
  return {
    exhibition: mapExhibition(record.exhibition, rpcName, "$.exhibition"),
    media: mapMediaList(record.media, rpcName, "$.media"),
  };
}

function throwStorageError(operation: string, error: unknown): never {
  const record = isRecord(error) ? error : {};
  const message =
    readErrorText(record.message) ?? "Unknown Supabase Storage error.";
  const statusCode =
    typeof record.statusCode === "string" || typeof record.statusCode === "number"
      ? ` [${String(record.statusCode)}]`
      : "";
  throw new Error(`${operation} failed${statusCode}: ${message}`);
}

function parseServerRevision(value: unknown): number | null {
  if (typeof value === "number") {
    return Number.isInteger(value) && value >= 1 ? value : null;
  }
  if (typeof value !== "string" || !/^\d+$/.test(value.trim())) return null;
  const revision = Number(value.trim());
  return Number.isSafeInteger(revision) && revision >= 1 ? revision : null;
}

function readErrorText(value: unknown): string | null {
  return typeof value === "string" && value.trim().length > 0
    ? value.trim()
    : null;
}

/** The weekday a refused `hours_weekly` names in its detail, when it names exactly one. */
function readHoursWeeklyDay(details: string | null): HoursWeeklyDay | null {
  const day = details?.toUpperCase();
  return (HOURS_WEEKLY_DAYS as readonly string[]).includes(day ?? "") ? (day as HoursWeeklyDay) : null;
}

function throwRpcError(rpcName: string, error: RpcErrorLike): never {
  const code = readErrorText(error.code);
  const message = readErrorText(error.message);

  if (
    (code === "40001" || code === "PT409") &&
    message?.toLowerCase() === "revision_conflict"
  ) {
    const serverRevision = parseServerRevision(error.details);
    if (serverRevision !== null) {
      throw new RevisionConflictError(serverRevision);
    }
    throw new Error(
      `${rpcName} reported revision_conflict without a valid positive integer server revision in error.details.`,
    );
  }

  const details = readErrorText(error.details);
  switch (message) {
    case "exhibition_metadata_stale_revision": {
      const serverRevision = parseServerRevision(error.details);
      if (serverRevision !== null) throw new RevisionConflictError(serverRevision);
      throw new ExhibitionChangedError(null);
    }
    case "artist_duplicate":
      // The existing artist's id rides in `detail`; without a valid one there is nothing to link to.
      if (details !== null && uuidPattern.test(details)) throw new ArtistDuplicateError(details);
      break;
    case "artist_merge_invalid":
      throw new ArtistMergeInvalidError(details);
    case "hours_weekly_invalid":
      throw new HoursWeeklyInvalidError(readHoursWeeklyDay(details));
    case "art_metadata_term_grounding_invalid":
      throw new ArtTermGroundingInvalidError(details);
    default:
      break;
  }
  const hint = readErrorText(error.hint);
  const codeSuffix = code === null ? "" : ` [${code}]`;
  const detailSuffix = details === null ? "" : ` Details: ${details}`;
  const hintSuffix = hint === null ? "" : ` Hint: ${hint}`;
  throw new Error(
    `${rpcName} failed${codeSuffix}: ${message ?? "Unknown Supabase error."}${detailSuffix}${hintSuffix}`,
  );
}

const patchFieldNames: ReadonlyArray<[keyof ExhibitionPatch, string]> = [
  ["nameKo", "name_ko"],
  ["nameEn", "name_en"],
  ["venueNameKo", "venue_name_ko"],
  ["venueNameEn", "venue_name_en"],
  ["cityKo", "city_ko"],
  ["cityEn", "city_en"],
  ["regionKo", "region_ko"],
  ["regionEn", "region_en"],
  ["addressKo", "address_ko"],
  ["addressEn", "address_en"],
  ["latitude", "latitude"],
  ["longitude", "longitude"],
  ["openingDate", "opening_date"],
  ["closingDate", "closing_date"],
  ["descriptionKo", "description_ko"],
  ["descriptionEn", "description_en"],
  ["creditsKo", "credits_ko"],
  ["creditsEn", "credits_en"],
  ["hours", "hours"],
  ["contact", "contact"],
  ["receptionDate", "reception_date"],
  ["receptionStartTime", "reception_start_time"],
  ["receptionEndTime", "reception_end_time"],
  ["eventId", "event_id"],
  ["editorId", "editor_id"],
  ["ticketUrl", "ticket_url"],
  ["isFeatured", "is_featured"],
  ["isHomepageFeatured", "is_homepage_featured"],
];

function serializePatch(patch: Partial<ExhibitionPatch>): JsonRecord {
  const serialized: JsonRecord = {};
  for (const [source, target] of patchFieldNames) {
    if (Object.prototype.hasOwnProperty.call(patch, source)) {
      serialized[target] = patch[source] ?? null;
    }
  }
  if (
    Object.prototype.hasOwnProperty.call(patch, "artMetadata") &&
    patch.artMetadata !== null &&
    patch.artMetadata !== undefined
  ) {
    // Grounding is sent only to a server that returned it, so an older database never sees the new keys.
    const { termGrounding } = patch.artMetadata;
    const groundingSupported = termGrounding !== undefined;
    serialized.artists = patch.artMetadata.artists.map((artist) => ({
      id: artist.id,
      name_ko: artist.nameKo,
      name_en: artist.nameEn,
      ...(groundingSupported ? { grounding: normalizedGrounding(artist.grounding) } : {}),
    }));
    serialized.art_term_ids = patch.artMetadata.terms.map(({ id }) => id);
    if (groundingSupported) {
      // Every ticked term is sent, blank as null so a cleared grounding is cleared on the server; only ticked
      // terms may carry grounding (`art_metadata_term_grounding_invalid` otherwise).
      serialized.art_term_grounding = Object.fromEntries(
        patch.artMetadata.terms.map(({ id }) => [id, normalizedGrounding(termGrounding[id])]),
      );
    }
  }
  return serialized;
}

function normalizedGrounding(value: string | null | undefined): string | null {
  const text = value?.trim() ?? "";
  return text.length === 0 ? null : text;
}

function readSubmissionStatus(
  record: JsonRecord,
  rpcName: string,
  path: string,
): SubmissionStatus {
  const value = readString(record, "status", rpcName, path);
  if (
    value === "submitted" ||
    value === "in_review" ||
    value === "accepted" ||
    value === "rejected" ||
    value === "withdrawn"
  ) {
    return value;
  }
  throw new MalformedAdminExhibitionPayloadError(
    rpcName,
    `${path}.status`,
    "a reviewable submission status",
    value,
  );
}

function readSubmissionSource(
  record: JsonRecord,
  rpcName: string,
  path: string,
): AdminExhibitionSubmission["source"] {
  const value = readString(record, "source", rpcName, path);
  if (
    value === "public_form" || value === "owner_workspace" ||
    value === "editor_workspace"
  ) return value;
  throw new MalformedAdminExhibitionPayloadError(
    rpcName,
    `${path}.source`,
    'one of "public_form", "owner_workspace", or "editor_workspace"',
    value,
  );
}

function mapSubmission(
  value: unknown,
  rpcName: string,
  path: string,
): AdminExhibitionSubmission {
  const record = readRecord(value, rpcName, path);
  const payload = readRecord(record.payload, rpcName, `${path}.payload`);
  const media = readArray(record, "media", rpcName, path).map(
    (value, index) => {
      const mediaPath = `${path}.media[${index}]`;
      const asset = readRecord(value, rpcName, mediaPath);
      return {
        assetId: readNonEmptyString(asset, "asset_id", rpcName, mediaPath),
        bucketId: readNonEmptyString(asset, "bucket_id", rpcName, mediaPath),
        objectPath: readNonEmptyString(asset, "object_path", rpcName, mediaPath),
        publicUrl: readNullableString(asset, "public_url", rpcName, mediaPath),
        mimeType: readNonEmptyString(asset, "mime_type", rpcName, mediaPath),
        byteSize: readPositiveInteger(asset, "byte_size", rpcName, mediaPath),
        originalFilename: readString(
          asset,
          "original_filename",
          rpcName,
          mediaPath,
        ),
        previewUrl: null,
      };
    },
  );
  return {
    id: readNonEmptyString(record, "id", rpcName, path),
    status: readSubmissionStatus(record, rpcName, path),
    source: readSubmissionSource(record, rpcName, path),
    ownerExhibitionId: readNullableNonEmptyString(
      record,
      "owner_exhibition_id",
      rpcName,
      path,
    ),
    galleryNameKo: readString(record, "gallery_name_ko", rpcName, path),
    galleryNameEn: readString(record, "gallery_name_en", rpcName, path),
    submitterEmail: readNonEmptyString(
      record,
      "submitter_email",
      rpcName,
      path,
    ),
    nameKo: readString(payload, "name_ko", rpcName, `${path}.payload`),
    nameEn: readString(payload, "name_en", rpcName, `${path}.payload`),
    venueNameKo: readString(
      payload,
      "venue_name_ko",
      rpcName,
      `${path}.payload`,
    ),
    venueNameEn: readString(
      payload,
      "venue_name_en",
      rpcName,
      `${path}.payload`,
    ),
    openingDate: readString(
      payload,
      "opening_date",
      rpcName,
      `${path}.payload`,
    ),
    closingDate: readString(
      payload,
      "closing_date",
      rpcName,
      `${path}.payload`,
    ),
    addressKo: readString(payload, "address_ko", rpcName, `${path}.payload`),
    addressEn: readString(payload, "address_en", rpcName, `${path}.payload`),
    hours: readString(payload, "hours", rpcName, `${path}.payload`),
    descriptionKo: readString(
      payload,
      "description_ko",
      rpcName,
      `${path}.payload`,
    ),
    descriptionEn: readString(
      payload,
      "description_en",
      rpcName,
      `${path}.payload`,
    ),
    receptionDate: readString(
      payload,
      "reception_date",
      rpcName,
      `${path}.payload`,
    ),
    receptionEnd: readString(
      payload,
      "reception_end",
      rpcName,
      `${path}.payload`,
    ),
    acceptedExhibitionId: readNullableNonEmptyString(
      record,
      "accepted_exhibition_id",
      rpcName,
      path,
    ),
    reviewNotes: readString(record, "review_notes", rpcName, path),
    submittedAt: readNonEmptyString(record, "submitted_at", rpcName, path),
    reviewedAt: readNullableNonEmptyString(
      record,
      "reviewed_at",
      rpcName,
      path,
    ),
    createdAt: readNonEmptyString(record, "created_at", rpcName, path),
    media,
  };
}

function readGalleryClaimStatus(
  record: JsonRecord,
  rpcName: string,
  path: string,
): GalleryClaimStatus {
  const value = readString(record, "membership_status", rpcName, path);
  if (
    value === "pending" || value === "active" || value === "rejected" ||
    value === "suspended" || value === "revoked"
  ) return value;
  throw new MalformedAdminExhibitionPayloadError(
    rpcName,
    `${path}.membership_status`,
    "a gallery membership status",
    value,
  );
}

function mapGalleryClaim(value: unknown, rpcName: string, path: string): AdminGalleryClaim {
  const record = readRecord(value, rpcName, path);
  const galleryStatus = readString(record, "gallery_status", rpcName, path);
  if (
    galleryStatus !== "pending" && galleryStatus !== "active" &&
    galleryStatus !== "merged" && galleryStatus !== "disabled"
  ) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.gallery_status`,
      "a gallery status",
      galleryStatus,
    );
  }
  return {
    galleryId: readNonEmptyString(record, "gallery_id", rpcName, path),
    galleryNameKo: readString(record, "gallery_name_ko", rpcName, path),
    galleryNameEn: readString(record, "gallery_name_en", rpcName, path),
    galleryStatus,
    userId: readNonEmptyString(record, "user_id", rpcName, path),
    ownerEmail: readNonEmptyString(record, "owner_email", rpcName, path),
    membershipStatus: readGalleryClaimStatus(record, rpcName, path),
    websiteUrl: readString(record, "website_url", rpcName, path),
    socialUrl: readString(record, "social_url", rpcName, path),
    claimNote: readString(record, "claim_note", rpcName, path),
    reviewNotes: readString(record, "review_notes", rpcName, path),
    createdAt: readNonEmptyString(record, "created_at", rpcName, path),
    reviewedAt: readNullableNonEmptyString(record, "reviewed_at", rpcName, path),
  };
}

function mapGalleryClaimList(value: unknown, rpcName: string): AdminGalleryClaim[] {
  if (!Array.isArray(value)) {
    throw new MalformedAdminExhibitionPayloadError(rpcName, "$", "an array", value);
  }
  return value.map((item, index) => mapGalleryClaim(item, rpcName, `$[${index}]`));
}

function readLocalPromotionStatus(
  record: JsonRecord,
  rpcName: string,
  path: string,
): LocalPromotionStatus {
  const value = readString(record, "status", rpcName, path);
  if (value === "submitted" || value === "approved" || value === "active" ||
      value === "rejected" || value === "ended") return value;
  throw new MalformedAdminExhibitionPayloadError(
    rpcName, `${path}.status`, "a local promotion status", value,
  );
}

function mapLocalPromotion(
  value: unknown,
  rpcName: string,
  path: string,
): AdminLocalPromotion {
  const record = readRecord(value, rpcName, path);
  return {
    id: readNonEmptyString(record, "id", rpcName, path),
    launchKitId: readNonEmptyString(record, "launch_kit_id", rpcName, path),
    exhibitionId: readNonEmptyString(record, "exhibition_id", rpcName, path),
    galleryId: readNonEmptyString(record, "gallery_id", rpcName, path),
    status: readLocalPromotionStatus(record, rpcName, path),
    revision: readPositiveInteger(record, "revision", rpcName, path),
    cityKo: readString(record, "city_ko", rpcName, path),
    cityEn: readString(record, "city_en", rpcName, path),
    regionKo: readString(record, "region_ko", rpcName, path),
    regionEn: readString(record, "region_en", rpcName, path),
    startsAt: readNullableString(record, "starts_at", rpcName, path),
    endsAt: readNullableString(record, "ends_at", rpcName, path),
    reviewNotes: readString(record, "review_notes", rpcName, path),
    requestedAt: readNonEmptyString(record, "requested_at", rpcName, path),
    reviewedAt: readNullableString(record, "reviewed_at", rpcName, path),
    nameKo: readString(record, "name_ko", rpcName, path),
    nameEn: readString(record, "name_en", rpcName, path),
    venueNameKo: readString(record, "venue_name_ko", rpcName, path),
    venueNameEn: readString(record, "venue_name_en", rpcName, path),
    closingDate: readNonEmptyString(record, "closing_date", rpcName, path),
    galleryNameKo: readString(record, "gallery_name_ko", rpcName, path),
    galleryNameEn: readString(record, "gallery_name_en", rpcName, path),
  };
}

function mapLocalPromotionList(value: unknown, rpcName: string): AdminLocalPromotion[] {
  if (!Array.isArray(value)) {
    throw new MalformedAdminExhibitionPayloadError(rpcName, "$", "an array", value);
  }
  return value.map((item, index) => mapLocalPromotion(item, rpcName, `$[${index}]`));
}

function mapSubmissionList(
  value: unknown,
  rpcName: string,
): AdminExhibitionSubmission[] {
  if (!Array.isArray(value)) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      "$",
      "an array",
      value,
    );
  }
  return value.map((item, index) =>
    mapSubmission(item, rpcName, `$[${index}]`),
  );
}

export class SupabaseAdminExhibitionRepository
  implements AdminExhibitionRepository
{
  constructor(private readonly client: SupabaseClient) {}

  async list(filters: ExhibitionFilters): Promise<AdminExhibition[]> {
    if (!filters.staleOnly) return this.listExhibitions(filters);
    // `admin_list_exhibitions` has no stale parameter; the stale report narrows its result instead.
    const [records, stale] = await Promise.all([
      this.listExhibitions(filters),
      this.listStaleExhibitions(),
    ]);
    const staleIds = new Set(stale.map(({ exhibitionId }) => exhibitionId));
    return records.filter(({ id }) => staleIds.has(id));
  }

  private async listExhibitions(filters: ExhibitionFilters): Promise<AdminExhibition[]> {
    const rpcName = "admin_list_exhibitions";
    const { data, error } = await this.client.rpc(rpcName, {
      p_search: filters.search.trim(),
      p_status:
        filters.status === "All" ? null : filters.status.toLowerCase(),
      p_temporal_status:
        !filters.temporalStatus || filters.temporalStatus === "all"
          ? null
          : filters.temporalStatus,
      p_featured_only: filters.featuredOnly ?? false,
      p_missing_cover_only: filters.missingCoverOnly ?? false,
      p_sort: filters.sort ?? "updated_desc",
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapList(data, rpcName);
  }

  async getExhibitionLookups(): Promise<AdminExhibitionLookups> {
    const rpcName = "admin_get_exhibition_lookups";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibitionLookups(data, rpcName);
  }

  async searchArtists(query: string): Promise<ArtistLookup[]> {
    const normalized = query.trim();
    const queryLength = Array.from(normalized).length;
    if (queryLength < 1 || queryLength > MAX_ARTIST_QUERY_LENGTH) return [];
    const rpcName = "admin_search_artists";
    const { data, error } = await this.client.rpc(rpcName, {
      p_query: normalized,
      p_limit: MAX_ARTIST_RESULTS,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapArtistList(data, rpcName);
  }

  async createArtist(
    nameKo: string,
    nameEn: string,
    requestId: string,
  ): Promise<ArtistLookup> {
    const rpcName = "admin_create_artist";
    const { data, error } = await this.client.rpc(rpcName, {
      p_name_ko: nameKo.trim(),
      p_name_en: nameEn.trim(),
      p_request_id: requestId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapArtistLookup(data, rpcName, "$");
  }

  async confirmExhibitionMetadata(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
  ): Promise<AdminExhibition> {
    const rpcName = "admin_confirm_exhibition_metadata";
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: id,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibition(data, rpcName, "$");
  }

  async confirmExhibitionHours(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    hoursWeekly: HoursWeekly,
  ): Promise<AdminExhibition> {
    const rpcName = "admin_confirm_exhibition_hours";
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: id,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_hours_weekly: hoursWeekly,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibition(data, rpcName, "$");
  }

  async listStaleExhibitions(): Promise<StaleExhibitionSummary[]> {
    const rpcName = "admin_list_stale_exhibition_metadata";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throwRpcError(rpcName, error);
    return mapStaleExhibitionList(data, rpcName);
  }

  async findDuplicateArtists(): Promise<DuplicateArtistGroup[]> {
    const rpcName = "admin_find_duplicate_artists";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throwRpcError(rpcName, error);
    return mapDuplicateArtistGroups(data, rpcName);
  }

  async mergeArtists(
    survivorId: string,
    mergedId: string,
    reason: string,
  ): Promise<ArtistMergeResult> {
    const normalizedReason = reason.trim();
    const reasonLength = Array.from(normalizedReason).length;
    if (reasonLength < 1 || reasonLength > MAX_MERGE_REASON_LENGTH) {
      throw new ArtistMergeInvalidError("reason");
    }
    const rpcName = "admin_merge_artists";
    const { data, error } = await this.client.rpc(rpcName, {
      p_survivor_id: survivorId,
      p_merged_id: mergedId,
      p_reason: normalizedReason,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapArtistMergeResult(data, rpcName);
  }

  async createDraft(): Promise<AdminExhibition> {
    const rpcName = "admin_create_exhibition_draft";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibition(data, rpcName, "$");
  }

  async saveDraft(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    patch: Partial<ExhibitionPatch>,
  ): Promise<AdminExhibition> {
    const rpcName = "admin_save_exhibition_draft";
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: id,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_patch: serializePatch(patch),
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibition(data, rpcName, "$");
  }

  async publish(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    const rpcName = "admin_publish_exhibition";
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: id,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_request_id: requestId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibition(data, rpcName, "$");
  }

  async archive(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    return this.runVersionCommand(
      "admin_archive_exhibition",
      id,
      expectedVersionId,
      expectedRevision,
      requestId,
    );
  }

  async restore(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    return this.runVersionCommand(
      "admin_restore_exhibition",
      id,
      expectedVersionId,
      expectedRevision,
      requestId,
    );
  }

  async discardDraft(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    return this.runVersionCommand(
      "admin_discard_exhibition_draft",
      id,
      expectedVersionId,
      expectedRevision,
      requestId,
    );
  }

  async deleteDraft(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<void> {
    const rpcName = "admin_delete_exhibition_draft";
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: id,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_request_id: requestId,
    });
    if (error !== null) {
      const reason = readErrorText(error.message);
      if (reason !== null && isDraftDeleteBlockedReason(reason)) {
        throw new DraftDeleteBlockedError(reason);
      }
      throwRpcError(rpcName, error);
    }

    const record = readRecord(data, rpcName, "$");
    if (
      readNonEmptyString(record, "id", rpcName, "$") !== id ||
      readNonEmptyString(record, "status", rpcName, "$") !== "deleted"
    ) {
      throw new MalformedAdminExhibitionPayloadError(
        rpcName,
        "$",
        "the deleted exhibition identity",
        data,
      );
    }
  }

  async listSubmissions(
    filters: SubmissionFilters,
  ): Promise<AdminExhibitionSubmission[]> {
    const rpcName = "admin_list_exhibition_submissions";
    const { data, error } = await this.client.rpc(rpcName, {
      p_search: filters.search.trim(),
      p_status: filters.status === "all" ? null : filters.status,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return this.hydrateSubmissionPreviewUrls(mapSubmissionList(data, rpcName));
  }

  async startSubmissionReview(
    id: string,
  ): Promise<AdminExhibitionSubmission> {
    const rpcName = "admin_start_exhibition_submission_review";
    const { data, error } = await this.client.rpc(rpcName, {
      p_submission_id: id,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return this.hydrateSubmissionPreviewUrls([
      mapSubmission(data, rpcName, "$"),
    ]).then(([submission]) => submission);
  }

  async acceptSubmission(
    id: string,
    requestId: string,
  ): Promise<AdminSubmissionAcceptance> {
    const rpcName = "admin_accept_exhibition_submission";
    const { data, error } = await this.client.rpc(rpcName, {
      p_submission_id: id,
      p_request_id: requestId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    const record = readRecord(data, rpcName, "$");
    const [submission] = await this.hydrateSubmissionPreviewUrls([
      mapSubmission(record.submission, rpcName, "$.submission"),
    ]);
    return {
      submission,
      exhibition: mapExhibition(record.exhibition, rpcName, "$.exhibition"),
    };
  }

  async rejectSubmission(
    id: string,
    reviewNotes: string,
    requestId: string,
  ): Promise<AdminExhibitionSubmission> {
    const rpcName = "admin_reject_exhibition_submission";
    const { data, error } = await this.client.rpc(rpcName, {
      p_submission_id: id,
      p_review_notes: reviewNotes,
      p_request_id: requestId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    const [submission] = await this.hydrateSubmissionPreviewUrls([
      mapSubmission(data, rpcName, "$"),
    ]);
    return submission;
  }

  async listGalleryClaims(filters: GalleryClaimFilters): Promise<AdminGalleryClaim[]> {
    const rpcName = "admin_list_gallery_claims";
    const { data, error } = await this.client.rpc(rpcName, {
      p_search: filters.search.trim(),
      p_status: filters.status === "all" ? null : filters.status,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapGalleryClaimList(data, rpcName);
  }

  async approveGalleryClaim(
    galleryId: string,
    userId: string,
    requestId: string,
  ): Promise<AdminGalleryClaim> {
    const rpcName = "admin_approve_gallery_claim";
    const { data, error } = await this.client.rpc(rpcName, {
      p_gallery_id: galleryId,
      p_user_id: userId,
      p_request_id: requestId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapGalleryClaim(data, rpcName, "$");
  }

  async rejectGalleryClaim(
    galleryId: string,
    userId: string,
    reviewNotes: string,
    requestId: string,
  ): Promise<AdminGalleryClaim> {
    const rpcName = "admin_reject_gallery_claim";
    const { data, error } = await this.client.rpc(rpcName, {
      p_gallery_id: galleryId,
      p_user_id: userId,
      p_review_notes: reviewNotes,
      p_request_id: requestId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapGalleryClaim(data, rpcName, "$");
  }

  async listLocalPromotions(filters: LocalPromotionFilters): Promise<AdminLocalPromotion[]> {
    const rpcName = "admin_list_local_promotions";
    const { data, error } = await this.client.rpc(rpcName, {
      p_search: filters.search.trim(),
      p_status: filters.status === "all" ? null : filters.status,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapLocalPromotionList(data, rpcName);
  }

  async approveLocalPromotion(
    id: string, startsAt: string, endsAt: string, requestId: string,
  ): Promise<AdminLocalPromotion> {
    const rpcName = "admin_approve_local_promotion";
    const { data, error } = await this.client.rpc(rpcName, {
      p_promotion_id: id, p_starts_at: startsAt, p_ends_at: endsAt,
      p_request_id: requestId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapLocalPromotion(data, rpcName, "$");
  }

  async rejectLocalPromotion(
    id: string, reviewNotes: string, requestId: string,
  ): Promise<AdminLocalPromotion> {
    const rpcName = "admin_reject_local_promotion";
    const { data, error } = await this.client.rpc(rpcName, {
      p_promotion_id: id, p_review_notes: reviewNotes, p_request_id: requestId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapLocalPromotion(data, rpcName, "$");
  }

  private async runVersionCommand(
    rpcName:
      | "admin_archive_exhibition"
      | "admin_restore_exhibition"
      | "admin_discard_exhibition_draft",
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: id,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_request_id: requestId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibition(data, rpcName, "$");
  }

  async listMedia(
    exhibitionId: string,
    versionId: string,
  ): Promise<AdminMediaAsset[]> {
    const rpcName = "admin_list_exhibition_media";
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: exhibitionId,
      p_version_id: versionId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return this.hydratePreviewUrls(mapMediaList(data, rpcName));
  }

  async uploadAndAttachMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    file: File,
    role: AdminMediaRole,
  ): Promise<AdminMediaMutationResult> {
    assertValidAdminMediaFile(file);

    let dimensions: { width: number; height: number } | null;
    try {
      dimensions = await readImageDimensions(file);
    } catch {
      throw new Error("The selected image could not be decoded.");
    }
    const checksum = await sha256File(file);

    const requestRpc = "admin_request_media_upload";
    const requestResponse = await this.client.rpc(requestRpc, {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_filename: file.name,
      p_mime_type: file.type,
      p_byte_size: file.size,
    });
    if (requestResponse.error !== null) {
      throwRpcError(requestRpc, requestResponse.error);
    }
    const target = mapUploadTarget(requestResponse.data, requestRpc);
    if (
      target.mimeType !== file.type ||
      target.byteSize !== file.size ||
      target.originalFilename !== file.name ||
      target.status !== "pending_upload"
    ) {
      throw new Error(`${requestRpc} returned a reservation that does not match the selected file.`);
    }

    const storage = this.client.storage.from(target.bucketId);
    const signedUpload = await storage.createSignedUploadUrl(target.objectPath, {
      upsert: false,
    });
    if (signedUpload.error !== null) {
      throwStorageError("Creating the signed image upload", signedUpload.error);
    }
    const uploaded = await storage.uploadToSignedUrl(
      target.objectPath,
      signedUpload.data.token,
      file,
      { contentType: file.type, upsert: false },
    );
    if (uploaded.error !== null) {
      throwStorageError("Uploading the image", uploaded.error);
    }

    const finalizeRpc = "admin_finalize_media_upload";
    const finalizeResponse = await this.client.rpc(finalizeRpc, {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_asset_id: target.assetId,
      p_width: dimensions?.width ?? null,
      p_height: dimensions?.height ?? null,
      p_checksum_sha256: checksum,
    });
    if (finalizeResponse.error !== null) {
      throwRpcError(finalizeRpc, finalizeResponse.error);
    }
    validateFinalizedAsset(finalizeResponse.data, finalizeRpc, target);

    return this.runMediaMutation("admin_attach_exhibition_media", {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_asset_id: target.assetId,
      p_role: role,
    });
  }

  async updateMediaMetadata(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    assetId: string,
    patch: AdminMediaMetadataPatch,
  ): Promise<AdminMediaMutationResult> {
    return this.runMediaMutation("admin_update_exhibition_media_metadata", {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_asset_id: assetId,
      p_alt_ko: patch.altKo,
      p_alt_en: patch.altEn,
      p_credit: patch.credit,
      p_rights_url: patch.rightsUrl,
    });
  }

  async reorderMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    orderedAssetIds: string[],
  ): Promise<AdminMediaMutationResult> {
    if (new Set(orderedAssetIds).size !== orderedAssetIds.length) {
      throw new Error("Gallery order contains a duplicate image.");
    }
    return this.runMediaMutation("admin_reorder_exhibition_media", {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_ordered_asset_ids: orderedAssetIds,
    });
  }

  async detachMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    assetId: string,
  ): Promise<AdminMediaMutationResult> {
    return this.runMediaMutation("admin_detach_exhibition_media", {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_asset_id: assetId,
    });
  }

  private async runMediaMutation(
    rpcName:
      | "admin_attach_exhibition_media"
      | "admin_update_exhibition_media_metadata"
      | "admin_reorder_exhibition_media"
      | "admin_detach_exhibition_media",
    args: JsonRecord,
  ): Promise<AdminMediaMutationResult> {
    const { data, error } = await this.client.rpc(rpcName, args);
    if (error !== null) throwRpcError(rpcName, error);
    const result = mapMediaMutation(data, rpcName);
    try {
      return {
        exhibition: result.exhibition,
        media: await this.hydratePreviewUrls(result.media),
      };
    } catch {
      // The mutation has committed; preview signing is transient presentation work.
      console.warn(
        JSON.stringify({
          event: "admin_media_preview_hydration_failed",
          rpc_name: rpcName,
          exhibition_id: result.exhibition.id,
          working_version_id: result.exhibition.workingVersionId,
        }),
      );
      return result;
    }
  }

  private async hydratePreviewUrls(
    media: AdminMediaAsset[],
  ): Promise<AdminMediaAsset[]> {
    return Promise.all(
      media.map(async (asset) => {
        if (asset.publicUrl !== null) {
          return { ...asset, previewUrl: asset.publicUrl };
        }
        if (
          asset.status === "pending_upload" ||
          asset.status === "orphaned" ||
          asset.status === "rejected"
        ) {
          return asset;
        }
        const preview = await this.client.storage
          .from(asset.bucketId)
          .createSignedUrl(asset.objectPath, 15 * 60);
        if (preview.error !== null) {
          throwStorageError("Creating the image preview", preview.error);
        }
        return { ...asset, previewUrl: preview.data.signedUrl };
      }),
    );
  }

  private async hydrateSubmissionPreviewUrls(
    submissions: AdminExhibitionSubmission[],
  ): Promise<AdminExhibitionSubmission[]> {
    return Promise.all(
      submissions.map(async (submission) => ({
        ...submission,
        media: await Promise.all(
          submission.media.map(async (asset) => {
            if (asset.publicUrl !== null) {
              return { ...asset, previewUrl: asset.publicUrl };
            }
            const preview = await this.client.storage
              .from(asset.bucketId)
              .createSignedUrl(asset.objectPath, 15 * 60);
            if (preview.error !== null) {
              console.warn(
                JSON.stringify({
                  event: "admin_submission_preview_hydration_failed",
                  submission_id: submission.id,
                  asset_id: asset.assetId,
                }),
              );
              return asset;
            }
            return { ...asset, previewUrl: preview.data.signedUrl };
          }),
        ),
      })),
    );
  }
}
