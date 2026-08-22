import { createRoot } from "react-dom/client";
import { ExhibitionWorkspace } from "../src/components/ExhibitionWorkspace";
import type { OwnerExhibition } from "../src/domain";
import { LocaleProvider } from "../src/i18n";
import "../src/styles.css";

const base: OwnerExhibition = {
  id: "visual-published",
  workingVersionId: "visual-version",
  versionNumber: 1,
  revision: 5,
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
  descriptionKo: "",
  descriptionEn: "",
  hours: "화–일 11:00–18:00",
  contact: "",
  receptionDate: "",
  receptionStartTime: "",
  ticketUrl: "",
  updatedAt: "2026-08-05T12:00:00Z",
  pageLoads30d: 42,
  pageLoadsAllTime: 210,
  cover: null,
};

const newDraft: OwnerExhibition = {
  ...base,
  id: "visual-new-draft",
  workingVersionId: "visual-new-draft-version",
  versionNumber: 1,
  revision: 1,
  ownerStatus: "draft",
  nameKo: "",
  nameEn: "",
  openingDate: "",
  closingDate: "",
  contact: "hello@gallery.example",
  updatedAt: "2026-08-08T12:00:00Z",
  pageLoads30d: 0,
  pageLoadsAllTime: 0,
};

const failure = new URLSearchParams(window.location.search).get("failure");

const repository = {
  listExhibitions: async () => [base, {
    ...base,
    id: "visual-submitted",
    workingVersionId: "visual-submitted-version",
    revision: 3,
    ownerStatus: "submitted" as const,
    nameKo: "여름의 기록",
    nameEn: "A Record of Summer",
  }],
  hideExhibition: async () => {
    if (failure === "hide") {
      throw new Error("owner_hide_exhibition failed [40001]: revision_conflict DETAIL: revision 9");
    }
  },
  createExhibitionDraft: async () => newDraft,
  saveExhibitionDraft: async () => base,
  uploadCover: async () => base,
  submitExhibition: async () => base,
  activateLaunchKit: async () => ({
    id: "visual-launch",
    exhibitionId: base.id,
    status: "active" as const,
    entitlementSource: "free_beta" as const,
    revision: 1,
    publicToken: "00000000-0000-4000-8000-000000000001",
    nameKo: base.nameKo,
    nameEn: base.nameEn,
    receptionDate: base.receptionDate,
    receptionStartTime: base.receptionStartTime,
    rsvpCount: 0,
    guestCount: 0,
    checkedInCount: 0,
    updatedAt: "2026-08-22T00:00:00Z",
  }),
};

createRoot(document.getElementById("root")!).render(
  <LocaleProvider>
    <ExhibitionWorkspace
      membershipStatus="active"
      repository={repository}
      onSignOut={() => undefined}
    />
  </LocaleProvider>,
);
