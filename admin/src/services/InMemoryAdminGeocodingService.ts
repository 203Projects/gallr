import type { AdminGeocodeCandidate } from "../domain";
import type { AdminGeocodingService } from "./AdminGeocodingService";

const hannamCandidate: AdminGeocodeCandidate = {
  roadAddress: "서울 용산구 한남대로 28",
  jibunAddress: "서울 용산구 한남동 1-1",
  englishAddress: "28 Hannam-daero, Yongsan-gu, Seoul",
  latitude: "37.5344",
  longitude: "127.0005",
};

export class InMemoryAdminGeocodingService implements AdminGeocodingService {
  readonly mode = "fixture" as const;

  async searchAddress(address: string): Promise<AdminGeocodeCandidate[]> {
    const query = address.trim().replace(/\s+/g, " ");
    return query.includes("한남대로 28") ? [{ ...hannamCandidate }] : [];
  }
}
