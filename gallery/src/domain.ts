export type GalleryStatus = "pending" | "active" | "merged" | "disabled";
export type MembershipStatus =
  | "pending"
  | "active"
  | "rejected"
  | "suspended"
  | "revoked";

export interface OwnerAccess {
  membership: {
    role: "owner";
    status: MembershipStatus;
  };
  gallery: {
    id: string;
    nameKo: string;
    nameEn: string;
    status: GalleryStatus;
    addressKo: string;
    addressEn: string;
  };
}

export interface GallerySearchResult {
  galleryId: string;
  nameKo: string;
  nameEn: string;
  addressKo: string;
  addressEn: string;
  isClaimed: boolean;
}

export interface GalleryInfo {
  galleryId: string;
  revision: number;
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
  latitude: number | null;
  longitude: number | null;
  hours: string;
  contact: string;
  updatedAt: string;
}

export type GalleryInfoPatch = Omit<
  GalleryInfo,
  "galleryId" | "revision" | "updatedAt"
>;

export interface GalleryGeocodeCandidate {
  roadAddress: string;
  jibunAddress: string;
  englishAddress: string;
  cityKo: string;
  cityEn: string;
  regionKo: string;
  regionEn: string;
  latitude: number;
  longitude: number;
}

export interface ExistingGalleryClaimInput {
  galleryId: string;
  websiteUrl: string;
  socialUrl: string;
  claimNote: string;
}

export interface NewGalleryClaimInput {
  nameKo: string;
  nameEn: string;
  websiteUrl: string;
  socialUrl: string;
  claimNote: string;
}

export type OwnerExhibitionStatus =
  | "draft"
  | "submitted"
  | "needs_changes"
  | "published"
  | "archived";

export interface OwnerCover {
  assetId: string;
  status: "pending_upload" | "ready" | "published" | "orphaned" | "rejected";
  bucketId: string;
  objectPath: string;
  publicUrl: string | null;
  mimeType: string;
  byteSize: number;
  originalFilename: string;
  previewUrl: string | null;
}

export interface OwnerExhibition {
  id: string;
  workingVersionId: string;
  versionNumber: number;
  revision: number;
  ownerStatus: OwnerExhibitionStatus;
  reviewNotes: string;
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
  latitude: number | null;
  longitude: number | null;
  openingDate: string;
  closingDate: string;
  descriptionKo: string;
  descriptionEn: string;
  hours: string;
  contact: string;
  receptionDate: string;
  receptionStartTime: string;
  ticketUrl: string;
  updatedAt: string;
  pageLoads30d: number;
  pageLoadsAllTime: number;
  cover: OwnerCover | null;
}

export type LaunchKitStatus = "pending" | "active" | "cancelled" | "refunded";
export type LaunchKitEntitlementSource = "free_beta" | "paid";
export interface LaunchKit {
  id: string;
  exhibitionId: string;
  status: LaunchKitStatus;
  entitlementSource: LaunchKitEntitlementSource | null;
  revision: number;
  publicToken: string;
  nameKo: string;
  nameEn: string;
  receptionDate: string;
  receptionStartTime: string;
  rsvpCount: number;
  guestCount: number;
  checkedInCount: number;
  updatedAt: string;
}
export type LaunchGuestStatus = "going" | "checked_in";
export interface LaunchGuest {
  id: string;
  launchKitId: string;
  name: string;
  email: string;
  partySize: number;
  status: LaunchGuestStatus;
  checkedInAt: string | null;
  createdAt: string;
}
export interface LaunchGuestCursor {
  createdAt: string;
  id: string;
}
export interface LaunchGuestPage {
  records: LaunchGuest[];
  nextCursor: LaunchGuestCursor | null;
}

export type LocalPromotionStatus =
  | "submitted"
  | "approved"
  | "active"
  | "rejected"
  | "ended";
export interface LocalPromotion {
  id: string;
  launchKitId: string;
  exhibitionId: string;
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
}

export type OwnerExhibitionPatch = Pick<
  OwnerExhibition,
  | "nameKo"
  | "nameEn"
  | "venueNameKo"
  | "venueNameEn"
  | "cityKo"
  | "cityEn"
  | "regionKo"
  | "regionEn"
  | "addressKo"
  | "addressEn"
  | "latitude"
  | "longitude"
  | "openingDate"
  | "closingDate"
  | "descriptionKo"
  | "descriptionEn"
  | "hours"
  | "contact"
  | "receptionDate"
  | "receptionStartTime"
  | "ticketUrl"
>;

export interface OwnerSession {
  userId: string;
  email: string;
}

export type OwnerOAuthCallbackError = "signup-disabled" | "oauth-failed";

export interface OwnerAuth {
  getSession(): Promise<OwnerSession | null>;
  subscribe(listener: (session: OwnerSession | null) => void): () => void;
  getOAuthCallbackError(): OwnerOAuthCallbackError | null;
  sendOtp(email: string): Promise<void>;
  signInWithGoogle(): Promise<void>;
  signOut(): Promise<void>;
}

export interface OwnerRepository {
  currentAccess(): Promise<OwnerAccess | null>;
  searchGalleries(query: string): Promise<GallerySearchResult[]>;
  claimExistingGallery(input: ExistingGalleryClaimInput): Promise<OwnerAccess>;
  createGalleryClaim(input: NewGalleryClaimInput): Promise<OwnerAccess>;
  getGalleryInfo(): Promise<GalleryInfo>;
  saveGalleryInfo(revision: number, patch: GalleryInfoPatch): Promise<GalleryInfo>;
  searchGalleryAddress(address: string): Promise<GalleryGeocodeCandidate[]>;
  listExhibitions(): Promise<OwnerExhibition[]>;
  hideExhibition(id: string, versionId: string, revision: number): Promise<void>;
  createExhibitionDraft(requestId: string): Promise<OwnerExhibition>;
  saveExhibitionDraft(
    id: string,
    versionId: string,
    revision: number,
    patch: OwnerExhibitionPatch,
  ): Promise<OwnerExhibition>;
  uploadCover(
    id: string,
    versionId: string,
    revision: number,
    file: File,
  ): Promise<OwnerExhibition>;
  submitExhibition(
    id: string,
    versionId: string,
    revision: number,
    requestId: string,
  ): Promise<OwnerExhibition>;
  listLaunchKits(): Promise<LaunchKit[]>;
  activateLaunchKit(exhibitionId: string): Promise<LaunchKit>;
  listLaunchGuests(
    launchKitId: string,
    query?: string,
    status?: "all" | LaunchGuestStatus,
    cursor?: LaunchGuestCursor | null,
  ): Promise<LaunchGuestPage>;
  addLaunchGuest(
    launchKitId: string,
    name: string,
    email: string,
    partySize: number,
  ): Promise<LaunchGuest>;
  checkInLaunchGuest(launchKitId: string, guestId: string): Promise<LaunchGuest>;
  rotateLaunchRsvpToken(launchKitId: string): Promise<LaunchKit>;
  listLocalPromotions(): Promise<LocalPromotion[]>;
  requestLocalPromotion(launchKitId: string): Promise<LocalPromotion>;
}
