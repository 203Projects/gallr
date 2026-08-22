import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { GalleryInfoWorkspace } from "../src/components/GalleryInfoWorkspace";
import type { GalleryInfo } from "../src/domain";
import { LocaleProvider } from "../src/i18n";
import "../src/styles.css";

const galleryInfo: GalleryInfo = {
  galleryId: "visual-gallery",
  revision: 4,
  nameKo: "갤러리 한강",
  nameEn: "Gallery Hangang",
  venueNameKo: "갤러리 한강",
  venueNameEn: "Gallery Hangang",
  cityKo: "서울특별시",
  cityEn: "Seoul",
  regionKo: "용산구",
  regionEn: "Yongsan-gu",
  addressKo: "서울특별시 용산구 한남대로 28",
  addressEn: "28 Hannam-daero, Yongsan-gu, Seoul",
  latitude: 37.5344,
  longitude: 127.0005,
  hours: "화–일 11:00–18:00",
  contact: "hello@gallery.example",
  updatedAt: "2026-08-05T12:00:00Z",
};

const searchParams = new URLSearchParams(window.location.search);
const failure = searchParams.get("failure");
const result = searchParams.get("result");

const repository = {
  getGalleryInfo: async () => galleryInfo,
  saveGalleryInfo: async () => {
    if (failure === "save") {
      throw new Error("owner_save_gallery_info failed [40001]: revision_conflict DETAIL: current revision 8");
    }
    return { ...galleryInfo, revision: 5 };
  },
  searchGalleryAddress: async () => {
    if (failure === "search") {
      throw new Error("upstream socket failure at internal-host.example:5432");
    }
    if (result === "empty") return [];
    return [{
    roadAddress: "서울특별시 종로구 율곡로 3길 4",
    jibunAddress: "서울특별시 종로구 안국동 1",
    englishAddress: "4 Yulgok-ro 3-gil, Jongno-gu, Seoul",
    cityKo: "서울특별시",
    cityEn: "Seoul",
    regionKo: "종로구",
    regionEn: "Jongno-gu",
    latitude: 37.577,
    longitude: 126.986,
    }];
  },
};

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <LocaleProvider>
      <GalleryInfoWorkspace
        repository={repository}
        onNavigate={() => undefined}
        onSignOut={() => undefined}
      />
    </LocaleProvider>
  </StrictMode>,
);
