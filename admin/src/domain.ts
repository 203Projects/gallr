export type ExhibitionStatus = "Draft" | "Published" | "Archived";

export type ArtTermCategory = "medium" | "style" | "theme" | "mood";

export interface ArtistLookup {
  id: string;
  nameKo: string;
  nameEn: string;
}

export interface ExhibitionArtist {
  /** Null identifies a private owner suggestion that staff must resolve. */
  id: string | null;
  nameKo: string;
  nameEn: string;
}

export interface ArtTerm {
  id: string;
  category: ArtTermCategory;
  nameKo: string;
  nameEn: string;
}

export interface ExhibitionArtMetadata {
  /** Array order is the public artist-credit order. */
  artists: ExhibitionArtist[];
  terms: ArtTerm[];
}

export type AdminSection = "Exhibitions" | "Submissions" | "Gallery claims" | "Promotions" | "Editors";

export type SubmissionStatus =
  | "submitted"
  | "in_review"
  | "accepted"
  | "rejected"
  | "withdrawn";

export interface AdminSubmissionMedia {
  assetId: string;
  bucketId: string;
  objectPath: string;
  publicUrl: string | null;
  mimeType: string;
  byteSize: number;
  originalFilename: string;
  previewUrl: string | null;
}

export interface AdminExhibitionSubmission {
  id: string;
  status: SubmissionStatus;
  source: "public_form" | "owner_workspace" | "editor_workspace";
  ownerExhibitionId: string | null;
  galleryNameKo: string;
  galleryNameEn: string;
  submitterEmail: string;
  nameKo: string;
  nameEn: string;
  venueNameKo: string;
  venueNameEn: string;
  openingDate: string;
  closingDate: string;
  addressKo: string;
  addressEn: string;
  hours: string;
  descriptionKo: string;
  descriptionEn: string;
  receptionDate: string;
  receptionEnd: string;
  acceptedExhibitionId: string | null;
  reviewNotes: string;
  submittedAt: string;
  reviewedAt: string | null;
  createdAt: string;
  media: AdminSubmissionMedia[];
}

export interface SubmissionFilters {
  search: string;
  status: "all" | SubmissionStatus;
}

export type GalleryClaimStatus =
  | "pending"
  | "active"
  | "rejected"
  | "suspended"
  | "revoked";

export interface AdminGalleryClaim {
  galleryId: string;
  galleryNameKo: string;
  galleryNameEn: string;
  galleryStatus: "pending" | "active" | "merged" | "disabled";
  userId: string;
  ownerEmail: string;
  membershipStatus: GalleryClaimStatus;
  websiteUrl: string;
  socialUrl: string;
  claimNote: string;
  reviewNotes: string;
  createdAt: string;
  reviewedAt: string | null;
}

export interface GalleryClaimFilters {
  search: string;
  status: "all" | GalleryClaimStatus;
}

export type LocalPromotionStatus = "submitted" | "approved" | "active" | "rejected" | "ended";
export interface AdminLocalPromotion {
  id: string;
  launchKitId: string;
  exhibitionId: string;
  galleryId: string;
  status: LocalPromotionStatus;
  revision: number;
  cityKo: string;
  cityEn: string;
  regionKo: string;
  regionEn: string;
  startsAt: string | null;
  endsAt: string | null;
  reviewNotes: string;
  requestedAt: string;
  reviewedAt: string | null;
  nameKo: string;
  nameEn: string;
  venueNameKo: string;
  venueNameEn: string;
  closingDate: string;
  galleryNameKo: string;
  galleryNameEn: string;
}
export interface LocalPromotionFilters {
  search: string;
  status: "all" | LocalPromotionStatus;
}

export interface AdminSubmissionAcceptance {
  submission: AdminExhibitionSubmission;
  exhibition: AdminExhibition;
}

export type AdminMediaRole = "cover" | "gallery";

export type AdminMediaStatus =
  | "pending_upload"
  | "ready"
  | "published"
  | "orphaned"
  | "rejected";

export interface AdminMediaAsset {
  assetId: string;
  versionId: string;
  role: AdminMediaRole;
  sortOrder: number;
  status: AdminMediaStatus;
  bucketId: string;
  objectPath: string;
  mimeType: string;
  byteSize: number;
  width: number | null;
  height: number | null;
  checksumSha256: string | null;
  publicUrl: string | null;
  altKo: string;
  altEn: string;
  credit: string;
  rightsUrl: string;
  originalFilename: string;
  createdAt: string;
  updatedAt: string;
  previewUrl: string | null;
}

export interface AdminMediaMetadataPatch {
  altKo: string;
  altEn: string;
  credit: string;
  rightsUrl: string;
}

export interface AdminMediaUploadTarget {
  assetId: string;
  bucketId: string;
  objectPath: string;
  mimeType: string;
  byteSize: number;
  originalFilename: string;
  status: AdminMediaStatus;
}

export interface AdminMediaMutationResult {
  exhibition: AdminExhibition;
  media: AdminMediaAsset[];
}

export type InspectorSection =
  | "Basics"
  | "Art"
  | "Venue"
  | "Schedule"
  | "Media"
  | "Curation";

export interface AdminExhibition {
  id: string;
  workingVersionId: string;
  versionNumber: number;
  publishedVersionId: string | null;
  hasUnpublishedChanges: boolean;
  nameKo: string;
  nameEn: string;
  venueNameKo: string;
  venueNameEn: string;
  cityKo: string;
  cityEn: string;
  regionKo: string;
  regionEn: string;
  addressKo: string;
  addressEn: string;
  latitude: string;
  longitude: string;
  openingDate: string;
  closingDate: string;
  descriptionKo: string;
  descriptionEn: string;
  creditsKo: string;
  creditsEn: string;
  /** Null means the connected server predates the additive metadata contract. */
  artMetadata: ExhibitionArtMetadata | null;
  hours: string;
  contact: string;
  receptionDate: string;
  receptionStartTime: string;
  receptionEndTime: string;
  eventId: string;
  editorId: string;
  ticketUrl: string;
  coverImageUrl: string | null;
  coverAltKo: string;
  coverAltEn: string;
  imageCredit: string;
  isFeatured: boolean;
  isHomepageFeatured: boolean;
  /**
   * A gallery-owner submission round for this draft is still awaiting a staff
   * decision. Permanent deletion withdraws it, so the confirmation dialog has
   * to say so before the item disappears from the review queue.
   */
  hasOpenOwnerSubmission: boolean;
  status: ExhibitionStatus;
  revision: number;
  createdAt: string;
  publishedAt: string | null;
  updatedAt: string;
  updatedBy: string;
}

export interface AdminGeocodeCandidate {
  roadAddress: string;
  jibunAddress: string;
  englishAddress: string;
  cityKo: string;
  cityEn: string;
  regionKo: string;
  regionEn: string;
  latitude: string;
  longitude: string;
}

export interface AdminEventLookup {
  id: string;
  nameKo: string;
  nameEn: string;
  locationLabelKo: string;
  locationLabelEn: string;
  startDate: string;
  endDate: string;
  shortLabel: string | null;
  isActive: boolean;
}

export interface AdminEditorLookup {
  id: string;
  nameKo: string;
  nameEn: string;
  titleKo: string;
  titleEn: string;
  isActive: boolean;
  activeFrom: string | null;
  activeTo: string | null;
}

export interface AdminVenueLookup {
  id: string;
  nameKo: string;
  nameEn: string;
  cityKo: string;
  cityEn: string;
  regionKo: string;
  regionEn: string;
  addressKo: string;
  addressEn: string;
  latitude: string;
  longitude: string;
}

export interface AdminLocationLookup {
  cityKo: string;
  cityEn: string;
  regionKo: string;
  regionEn: string;
}

export interface AdminExhibitionLookups {
  events: AdminEventLookup[];
  editors: AdminEditorLookup[];
  venues: AdminVenueLookup[];
  locations: AdminLocationLookup[];
  /** Null means the connected server does not advertise metadata support. */
  artTerms: ArtTerm[] | null;
}

/** Minimal exhibition projection exposed to an editor managing their collection. */
export interface EditorPickCandidate {
  id: string;
  workingVersionId: string;
  publishedVersionId: string;
  revision: number;
  nameKo: string;
  nameEn: string;
  venueNameKo: string;
  venueNameEn: string;
  openingDate: string;
  closingDate: string;
  selected: boolean;
  live: boolean;
  available: boolean;
  assignedEditorName: string;
}

export interface EditorProfile {
  editorId: string;
  nameKo: string;
  nameEn: string;
  bioKo: string;
  bioEn: string;
  curationDescriptionKo: string;
  curationDescriptionEn: string;
  pendingProfile: boolean;
  pendingCuration: boolean;
}

export interface EditorCurationChange {
  exhibitionId: string;
  expectedVersionId: string;
  expectedRevision: number;
  selected: boolean;
}

export interface EditorCurationSubmission {
  requestId: string;
  status: "submitted";
  candidates: EditorPickCandidate[];
}

export type EditorCurationRequestStatus = "submitted" | "accepted" | "rejected";

export interface EditorCurationHistoryChange {
  exhibitionId: string;
  nameKo: string;
  nameEn: string;
  venueNameKo: string;
  venueNameEn: string;
  openingDate: string;
  closingDate: string;
  selected: boolean;
}

export interface EditorCurationHistoryItem {
  id: string;
  status: EditorCurationRequestStatus;
  submittedAt: string;
  reviewedAt: string | null;
  reviewNotes: string;
  curationDescriptionKo: string;
  curationDescriptionEn: string;
  changes: EditorCurationHistoryChange[];
}

export interface EditorExhibitionSuggestion {
  nameKo: string;
  nameEn: string;
  venueNameKo: string;
  venueNameEn: string;
  openingDate: string;
  closingDate: string;
  addressKo: string;
  addressEn: string;
  hours: string;
  descriptionKo: string;
  descriptionEn: string;
}

export type ExhibitionPatch = Omit<
  AdminExhibition,
  | "id"
  | "workingVersionId"
  | "versionNumber"
  | "publishedVersionId"
  | "hasUnpublishedChanges"
  | "coverImageUrl"
  | "coverAltKo"
  | "coverAltEn"
  | "imageCredit"
  | "hasOpenOwnerSubmission"
  | "status"
  | "revision"
  | "createdAt"
  | "publishedAt"
  | "updatedAt"
  | "updatedBy"
  | "artMetadata"
> & {
  /** Omission preserves server metadata; supported empty arrays clear it. */
  artMetadata?: ExhibitionArtMetadata;
};

export interface ExhibitionFilters {
  search: string;
  status: "All" | ExhibitionStatus;
  temporalStatus?: "all" | "running" | "upcoming" | "ended";
  featuredOnly?: boolean;
  missingCoverOnly?: boolean;
  sort?:
    | "updated_desc"
    | "published_desc"
    | "opening_asc"
    | "closing_asc"
    | "created_desc";
}

export type ExhibitionTemporalStatus = Exclude<
  NonNullable<ExhibitionFilters["temporalStatus"]>,
  "all"
>;

export type ExhibitionSort = NonNullable<ExhibitionFilters["sort"]>;

/**
 * A cover counts as missing when the delivery URL is null or blank, matching the
 * `cover_image_url` contract of the staff list RPC.
 */
export function hasCoverImage(
  exhibition: Pick<AdminExhibition, "coverImageUrl">,
): boolean {
  return (exhibition.coverImageUrl ?? "").trim().length > 0;
}

const seoulDateFormatter = new Intl.DateTimeFormat("en", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

export function seoulCalendarDate(at: Date = new Date()): string {
  const parts = seoulDateFormatter.formatToParts(at);
  const year = parts.find((part) => part.type === "year")?.value;
  const month = parts.find((part) => part.type === "month")?.value;
  const day = parts.find((part) => part.type === "day")?.value;
  if (!year || !month || !day) {
    throw new Error("Could not derive the Seoul calendar date.");
  }
  return `${year}-${month}-${day}`;
}

function compareNullableAscending(left: string | null, right: string | null): number {
  if (!left) return right ? 1 : 0;
  if (!right) return -1;
  return left.localeCompare(right);
}

function compareNullableDescending(left: string | null, right: string | null): number {
  if (!left) return right ? 1 : 0;
  if (!right) return -1;
  return right.localeCompare(left);
}

export function sortAdminExhibitions(
  records: readonly AdminExhibition[],
  sort: ExhibitionSort = "updated_desc",
): AdminExhibition[] {
  return [...records].sort((left, right) => {
    let comparison: number;
    if (sort === "opening_asc") {
      comparison = compareNullableAscending(left.openingDate, right.openingDate);
    } else if (sort === "closing_asc") {
      comparison = compareNullableAscending(left.closingDate, right.closingDate);
    } else if (sort === "created_desc") {
      comparison = compareNullableDescending(left.createdAt, right.createdAt);
    } else if (sort === "published_desc") {
      comparison = compareNullableDescending(left.publishedAt, right.publishedAt);
    } else {
      comparison = compareNullableDescending(left.updatedAt, right.updatedAt);
    }
    return comparison || left.id.localeCompare(right.id);
  });
}

function normalizedAddress(value: string): string {
  return value.trim().replace(/\s+/gu, " ");
}

function searchableKoreanAddress(value: string): string | null {
  const normalized = normalizedAddress(value);
  const road = normalized.match(/^(.+(?:로|길)\s+\d+(?:-\d+)?)(?:\s+.*)?$/u);
  if (road) return road[1];
  const parcel = normalized.match(/^(.+(?:동|가)\s+\d+(?:-\d+)?)(?:\s+.*)?$/u);
  return parcel?.[1] ?? null;
}

export function shouldPreserveCoordinatesForAddressChange(
  previousAddress: string,
  nextAddress: string,
): boolean {
  const previous = normalizedAddress(previousAddress);
  const next = normalizedAddress(nextAddress);
  if (previous === next) return true;
  const previousSearchable = searchableKoreanAddress(previous);
  const nextSearchable = searchableKoreanAddress(next);
  return previousSearchable !== null && previousSearchable === nextSearchable;
}

/**
 * Client-side counterpart of the staff list RPC filters, used wherever a record
 * is merged into an already-loaded list without a round trip. Publish state,
 * date state, placement, and cover presence match the RPC exactly; search is an
 * approximation (per-field substring match), so the next list load is
 * authoritative for search results.
 */
export function matchesExhibitionFilters(
  exhibition: AdminExhibition,
  filters: ExhibitionFilters,
  today: string,
): boolean {
  const query = filters.search.trim().toLocaleLowerCase();
  const matchesStatus =
    filters.status === "All" || exhibition.status === filters.status;
  const temporalStatus = filters.temporalStatus ?? "all";
  const matchesTemporalStatus =
    temporalStatus === "all" ||
    exhibitionTemporalStatus(
      exhibition.openingDate,
      exhibition.closingDate,
      today,
    ) === temporalStatus;
  const matchesHomepagePlacement =
    !filters.featuredOnly || exhibition.isHomepageFeatured;
  const matchesCover =
    !filters.missingCoverOnly || !hasCoverImage(exhibition);
  const matchesSearch =
    query.length === 0 ||
    [
      exhibition.id,
      exhibition.nameKo,
      exhibition.nameEn,
      exhibition.venueNameKo,
      exhibition.venueNameEn,
    ].some((value) => value.toLocaleLowerCase().includes(query));
  return (
    matchesStatus &&
    matchesTemporalStatus &&
    matchesHomepagePlacement &&
    matchesCover &&
    matchesSearch
  );
}

export function exhibitionTemporalStatus(
  openingDate: string,
  closingDate: string,
  today: string,
): ExhibitionTemporalStatus | null {
  if (
    !/^\d{4}-\d{2}-\d{2}$/u.test(openingDate) ||
    !/^\d{4}-\d{2}-\d{2}$/u.test(closingDate)
  ) {
    return null;
  }
  if (openingDate > closingDate) return "ended";
  if (openingDate > today) return "upcoming";
  if (closingDate < today) return "ended";
  return "running";
}

export interface PublishReadiness {
  identityComplete: boolean;
  venueComplete: boolean;
  locationComplete: boolean;
  datesValid: boolean;
  mediaReady: boolean;
}

export interface AdminExhibitionValidation {
  coordinateError: string | null;
  ticketUrlError: string | null;
  isValid: boolean;
}

function getCoordinateError(exhibition: AdminExhibition): string | null {
  const latitude = exhibition.latitude.trim();
  const longitude = exhibition.longitude.trim();

  if (latitude.length === 0 && longitude.length === 0) return null;
  if (latitude.length === 0 || longitude.length === 0) {
    return "Add both latitude and longitude, or leave both blank.";
  }

  const decimalCoordinate = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;
  if (
    !decimalCoordinate.test(latitude) ||
    !decimalCoordinate.test(longitude)
  ) {
    return "Coordinates must be valid decimal numbers.";
  }
  const latitudeNumber = Number(latitude);
  const longitudeNumber = Number(longitude);
  if (!Number.isFinite(latitudeNumber) || !Number.isFinite(longitudeNumber)) {
    return "Coordinates must be valid decimal numbers.";
  }
  if (
    latitudeNumber < -90 ||
    latitudeNumber > 90 ||
    longitudeNumber < -180 ||
    longitudeNumber > 180
  ) {
    return "Latitude must be between -90 and 90, and longitude between -180 and 180.";
  }
  return null;
}

function getTicketUrlError(ticketUrl: string): string | null {
  const value = ticketUrl.trim();
  if (value.length === 0) return null;

  try {
    const parsed = new URL(value);
    if (
      (parsed.protocol === "http:" || parsed.protocol === "https:") &&
      parsed.hostname.length > 0
    ) {
      return null;
    }
  } catch {
    // Return the same actionable message for malformed and unsupported URLs.
  }
  return "Enter a complete http:// or https:// URL.";
}

export function getAdminExhibitionValidation(
  exhibition: AdminExhibition,
): AdminExhibitionValidation {
  const coordinateError = getCoordinateError(exhibition);
  const ticketUrlError = getTicketUrlError(exhibition.ticketUrl);
  return {
    coordinateError,
    ticketUrlError,
    isValid: coordinateError === null && ticketUrlError === null,
  };
}

export function getPublishReadiness(
  exhibition: AdminExhibition,
  media: AdminMediaAsset[] = [],
  mediaLoaded = true,
): PublishReadiness {
  return {
    identityComplete: exhibition.nameKo.trim().length > 0,
    venueComplete:
      exhibition.venueNameKo.trim().length > 0 &&
      exhibition.cityKo.trim().length > 0 &&
      exhibition.cityEn.trim().length > 0 &&
      exhibition.regionKo.trim().length > 0 &&
      exhibition.regionEn.trim().length > 0,
    locationComplete:
      exhibition.addressKo.trim().length > 0 &&
      exhibition.latitude.trim().length > 0 &&
      exhibition.longitude.trim().length > 0 &&
      getCoordinateError(exhibition) === null,
    datesValid:
      exhibition.openingDate.length > 0 &&
      exhibition.closingDate.length > 0 &&
      exhibition.closingDate >= exhibition.openingDate,
    mediaReady:
      mediaLoaded &&
      (media.length === 0 || media.every((asset) => asset.status === "published")),
  };
}

export function isPublishReady(exhibition: AdminExhibition): boolean {
  return Object.values(getPublishReadiness(exhibition)).every(Boolean);
}
