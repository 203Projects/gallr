export type ExhibitionStatus = "Draft" | "Published" | "Archived";

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
  hours: string;
  contact: string;
  receptionDate: string;
  receptionStartTime: string;
  eventId: string;
  editorId: string;
  ticketUrl: string;
  coverImageUrl: string | null;
  coverAltKo: string;
  coverAltEn: string;
  imageCredit: string;
  isFeatured: boolean;
  isHomepageFeatured: boolean;
  status: ExhibitionStatus;
  revision: number;
  updatedAt: string;
  updatedBy: string;
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

export interface AdminExhibitionLookups {
  events: AdminEventLookup[];
  editors: AdminEditorLookup[];
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
  | "status"
  | "revision"
  | "updatedAt"
  | "updatedBy"
>;

export interface ExhibitionFilters {
  search: string;
  status: "All" | ExhibitionStatus;
}

export interface PublishReadiness {
  identityComplete: boolean;
  venueComplete: boolean;
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
      exhibition.regionKo.trim().length > 0,
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
