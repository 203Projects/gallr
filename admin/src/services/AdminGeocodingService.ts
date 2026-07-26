import type { AdminGeocodeCandidate } from "../domain";

export type AdminGeocodingMode =
  | "fixture"
  | "naver-browser"
  | "naver-server";

export interface AdminGeocodingService {
  readonly mode: AdminGeocodingMode;
  searchAddress(address: string): Promise<AdminGeocodeCandidate[]>;
}
