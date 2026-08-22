import { createRoot } from "react-dom/client";
import { OwnerApp } from "../src/components/OwnerApp";
import { LocaleProvider } from "../src/i18n";
import type {
  GalleryInfo,
  LaunchGuest,
  LaunchKit,
  OwnerAccess,
  OwnerAuth,
  OwnerExhibition,
  OwnerRepository,
  OwnerSession,
} from "../src/domain";
import "../src/styles.css";

const session: OwnerSession = {
  userId: "00000000-0000-4000-8000-000000000101",
  email: "owner@gallery.example",
};

const access: OwnerAccess = {
  membership: { role: "owner", status: "active" },
  gallery: {
    id: "00000000-0000-4000-8000-000000000201",
    nameKo: "갤러리 한강",
    nameEn: "Gallery Hangang",
    status: "active",
    addressKo: "서울특별시 용산구 한남대로 28",
    addressEn: "28 Hannam-daero, Yongsan-gu, Seoul",
  },
};

const exhibition: OwnerExhibition = {
  id: "launch-beta-exhibition",
  workingVersionId: "00000000-0000-4000-8000-000000000301",
  versionNumber: 1,
  revision: 4,
  ownerStatus: "published",
  reviewNotes: "",
  nameKo: "한강의 빛",
  nameEn: "Light on the Han River",
  venueNameKo: "갤러리 한강",
  venueNameEn: "Gallery Hangang",
  cityKo: "서울",
  cityEn: "Seoul",
  regionKo: "용산구",
  regionEn: "Yongsan-gu",
  addressKo: "서울특별시 용산구 한남대로 28",
  addressEn: "28 Hannam-daero, Yongsan-gu, Seoul",
  latitude: 37.5344,
  longitude: 127.0005,
  openingDate: "2026-08-10",
  closingDate: "2026-09-20",
  descriptionKo: "한강의 빛을 기록한 전시입니다.",
  descriptionEn: "An exhibition recording light on the Han River.",
  hours: "화–일 11:00–18:00",
  contact: "hello@gallery.example",
  receptionDate: "2026-08-29",
  receptionStartTime: "18:00",
  ticketUrl: "",
  updatedAt: "2026-08-22T00:00:00Z",
  pageLoads30d: 42,
  pageLoadsAllTime: 210,
  cover: null,
};

const galleryInfo: GalleryInfo = {
  galleryId: access.gallery.id,
  revision: 1,
  nameKo: access.gallery.nameKo,
  nameEn: access.gallery.nameEn,
  venueNameKo: exhibition.venueNameKo,
  venueNameEn: exhibition.venueNameEn,
  cityKo: exhibition.cityKo,
  cityEn: exhibition.cityEn,
  regionKo: exhibition.regionKo,
  regionEn: exhibition.regionEn,
  addressKo: exhibition.addressKo,
  addressEn: exhibition.addressEn,
  latitude: exhibition.latitude,
  longitude: exhibition.longitude,
  hours: exhibition.hours,
  contact: exhibition.contact,
  updatedAt: exhibition.updatedAt,
};

let launchKit: LaunchKit | null = null;
let guests: LaunchGuest[] = [];

function activeKit(): LaunchKit {
  if (!launchKit) throw new Error("Activate the Launch Kit first.");
  return launchKit;
}

const auth: OwnerAuth = {
  getSession: async () => session,
  subscribe: () => () => undefined,
  sendOtp: async () => undefined,
  signInWithGoogle: async () => undefined,
  signOut: async () => undefined,
};

const repository: OwnerRepository = {
  currentAccess: async () => access,
  searchGalleries: async () => [],
  claimExistingGallery: async () => access,
  createGalleryClaim: async () => access,
  getGalleryInfo: async () => galleryInfo,
  saveGalleryInfo: async () => galleryInfo,
  searchGalleryAddress: async () => [],
  listExhibitions: async () => [exhibition],
  hideExhibition: async () => undefined,
  createExhibitionDraft: async () => exhibition,
  saveExhibitionDraft: async () => exhibition,
  uploadCover: async () => exhibition,
  submitExhibition: async () => exhibition,
  listLaunchKits: async () => launchKit ? [launchKit] : [],
  activateLaunchKit: async () => {
    launchKit ??= {
      id: "00000000-0000-4000-8000-000000000401",
      exhibitionId: exhibition.id,
      status: "active",
      entitlementSource: "free_beta",
      revision: 1,
      publicToken: "00000000-0000-4000-8000-000000000501",
      nameKo: exhibition.nameKo,
      nameEn: exhibition.nameEn,
      receptionDate: exhibition.receptionDate,
      receptionStartTime: exhibition.receptionStartTime,
      rsvpCount: 0,
      guestCount: 0,
      checkedInCount: 0,
      updatedAt: "2026-08-22T00:00:00Z",
    };
    return launchKit;
  },
  listLaunchGuests: async (_kitId, query = "", status = "all") => ({
    records: guests.filter((guest) => (
      (!query || `${guest.name} ${guest.email}`.toLowerCase().includes(query.toLowerCase())) &&
      (status === "all" || guest.status === status)
    )),
    nextCursor: null,
  }),
  addLaunchGuest: async (kitId, name, email, partySize) => {
    const guest: LaunchGuest = {
      id: `00000000-0000-4000-8000-${String(guests.length + 601).padStart(12, "0")}`,
      launchKitId: kitId,
      name,
      email,
      partySize,
      status: "going",
      checkedInAt: null,
      createdAt: "2026-08-22T02:00:00Z",
    };
    guests = [...guests, guest];
    launchKit = {
      ...activeKit(),
      rsvpCount: activeKit().rsvpCount + 1,
      guestCount: activeKit().guestCount + partySize,
    };
    return guest;
  },
  checkInLaunchGuest: async (_kitId, guestId) => {
    const current = guests.find((guest) => guest.id === guestId);
    if (!current) throw new Error("Guest not found.");
    const updated = current.status === "checked_in" ? current : {
      ...current,
      status: "checked_in" as const,
      checkedInAt: "2026-08-22T02:15:00Z",
    };
    guests = guests.map((guest) => guest.id === guestId ? updated : guest);
    return updated;
  },
  rotateLaunchRsvpToken: async () => {
    launchKit = {
      ...activeKit(),
      publicToken: "00000000-0000-4000-8000-000000000502",
      revision: activeKit().revision + 1,
    };
    return launchKit;
  },
  listLocalPromotions: async () => {
    throw new Error("R4 must remain disabled in the R3 beta fixture.");
  },
  requestLocalPromotion: async () => {
    throw new Error("R4 must remain disabled in the R3 beta fixture.");
  },
};

createRoot(document.getElementById("root")!).render(
  <LocaleProvider initialLocale="en">
    <OwnerApp
      auth={auth}
      repository={repository}
      launchKitEnabled
      promotionEnabled={false}
      publicSiteUrl="https://preview.gallrmap.test"
    />
  </LocaleProvider>,
);
