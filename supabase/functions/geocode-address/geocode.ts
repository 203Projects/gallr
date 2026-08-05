import { containsAsciiControlCharacters } from "./http.ts";

export const NAVER_GEOCODE_ENDPOINT =
  "https://maps.apigw.ntruss.com/map-geocode/v2/geocode";

export const MAX_GEOCODE_CANDIDATES = 3;

export interface GeocodeCandidate {
  road_address: string;
  jibun_address: string;
  english_address: string;
  city_ko: string;
  city_en: string;
  region_ko: string;
  region_en: string;
  latitude: string;
  longitude: string;
}

interface CanonicalCity {
  ko: string;
  en: string;
  englishAddressNames: readonly string[];
}

const CANONICAL_CITIES: Readonly<Record<string, CanonicalCity>> = {
  "서울특별시": { ko: "서울", en: "Seoul", englishAddressNames: ["Seoul"] },
  "부산광역시": { ko: "부산", en: "Busan", englishAddressNames: ["Busan"] },
  "대구광역시": { ko: "대구", en: "Daegu", englishAddressNames: ["Daegu"] },
  "인천광역시": { ko: "인천", en: "Incheon", englishAddressNames: ["Incheon"] },
  "광주광역시": { ko: "광주", en: "Gwangju", englishAddressNames: ["Gwangju"] },
  "대전광역시": { ko: "대전", en: "Daejeon", englishAddressNames: ["Daejeon"] },
  "울산광역시": { ko: "울산", en: "Ulsan", englishAddressNames: ["Ulsan"] },
  "세종특별자치시": {
    ko: "세종",
    en: "Sejong",
    englishAddressNames: ["Sejong-si", "Sejong"],
  },
  "경기도": {
    ko: "경기",
    en: "Gyeonggi",
    englishAddressNames: ["Gyeonggi-do", "Gyeonggi"],
  },
  "강원특별자치도": {
    ko: "강원",
    en: "Gangwon",
    englishAddressNames: ["Gangwon-do", "Gangwon State", "Gangwon"],
  },
  "강원도": {
    ko: "강원",
    en: "Gangwon",
    englishAddressNames: ["Gangwon-do", "Gangwon"],
  },
  "충청북도": {
    ko: "충북",
    en: "Chungbuk",
    englishAddressNames: ["Chungcheongbuk-do", "Chungbuk"],
  },
  "충청남도": {
    ko: "충남",
    en: "Chungnam",
    englishAddressNames: ["Chungcheongnam-do", "Chungnam"],
  },
  "전북특별자치도": {
    ko: "전북",
    en: "Jeonbuk",
    englishAddressNames: ["Jeonbuk State", "Jeollabuk-do", "Jeonbuk"],
  },
  "전라북도": {
    ko: "전북",
    en: "Jeonbuk",
    englishAddressNames: ["Jeollabuk-do", "Jeonbuk"],
  },
  "전라남도": {
    ko: "전남",
    en: "Jeonnam",
    englishAddressNames: ["Jeollanam-do", "Jeonnam"],
  },
  "경상북도": {
    ko: "경북",
    en: "Gyeongbuk",
    englishAddressNames: ["Gyeongsangbuk-do", "Gyeongbuk"],
  },
  "경상남도": {
    ko: "경남",
    en: "Gyeongnam",
    englishAddressNames: ["Gyeongsangnam-do", "Gyeongnam"],
  },
  "제주특별자치도": {
    ko: "제주",
    en: "Jeju",
    englishAddressNames: ["Jeju-do", "Jeju"],
  },
  "제주도": {
    ko: "제주",
    en: "Jeju",
    englishAddressNames: ["Jeju-do", "Jeju"],
  },
};

type ProviderErrorCode =
  | "provider_configuration_invalid"
  | "provider_response_invalid";

export class GeocodingProviderError extends Error {
  constructor(
    readonly code: ProviderErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "GeocodingProviderError";
  }
}

function providerResponseInvalid(message: string): never {
  throw new GeocodingProviderError("provider_response_invalid", message);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function boundedString(
  record: Record<string, unknown>,
  field: string,
  maximumLength: number,
): string {
  const value = record[field];
  if (
    typeof value !== "string" || value.length > maximumLength ||
    containsAsciiControlCharacters(value)
  ) {
    providerResponseInvalid(`Provider field ${field} is invalid.`);
  }
  return value.trim();
}

function nonNegativeInteger(
  record: Record<string, unknown>,
  field: string,
): number {
  const value = record[field];
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    providerResponseInvalid(`Provider field ${field} is invalid.`);
  }
  return value as number;
}

function coordinate(
  record: Record<string, unknown>,
  field: "x" | "y",
  minimum: number,
  maximum: number,
): string {
  const value = record[field];
  if (
    typeof value !== "string" || value.length > 32 ||
    !/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/u.test(value.trim())
  ) {
    providerResponseInvalid(`Provider coordinate ${field} is invalid.`);
  }

  const normalized = value.trim();
  const numeric = Number(normalized);
  if (!Number.isFinite(numeric) || numeric < minimum || numeric > maximum) {
    providerResponseInvalid(`Provider coordinate ${field} is out of range.`);
  }
  return normalized;
}

function addressElement(
  candidate: Record<string, unknown>,
  expectedType: "SIDO" | "SIGUGUN" | "DONGMYUN",
): string | null {
  const elements = candidate.addressElements;
  if (!Array.isArray(elements) || elements.length > 20) {
    providerResponseInvalid("Provider address elements are invalid.");
  }

  for (const element of elements) {
    if (
      !isRecord(element) || !Array.isArray(element.types) ||
      element.types.length > 10
    ) {
      providerResponseInvalid("Provider address element is invalid.");
    }
    if (
      !element.types.every((type) =>
        typeof type === "string" && type.length <= 50
      )
    ) {
      providerResponseInvalid("Provider address element types are invalid.");
    }
    if (element.types.includes(expectedType)) {
      const longName = boundedString(element, "longName", 100);
      return longName || null;
    }
  }
  return null;
}

function canonicalLocation(
  candidate: Record<string, unknown>,
  englishAddress: string,
): Pick<GeocodeCandidate, "city_ko" | "city_en" | "region_ko" | "region_en"> {
  const sido = addressElement(candidate, "SIDO");
  const city = sido === null ? undefined : CANONICAL_CITIES[sido];
  if (city === undefined) {
    providerResponseInvalid("Provider SIDO is missing or unsupported.");
  }

  const sigugun = addressElement(candidate, "SIGUGUN");
  const regionSource = sigugun || addressElement(candidate, "DONGMYUN");
  const regionKo = regionSource?.split(/\s+/u)[0] ?? "";
  if (!regionKo) {
    providerResponseInvalid("Provider SIGUGUN is missing.");
  }

  const englishParts = englishAddress.split(",").map((part) => part.trim())
    .filter(Boolean);
  const cityIndex = englishParts.findIndex((part) =>
    city.englishAddressNames.includes(part)
  );
  const regionEn = cityIndex > 0 ? englishParts[cityIndex - 1] : "";
  if (
    !regionEn || regionEn.length > 100 ||
    containsAsciiControlCharacters(regionEn)
  ) {
    providerResponseInvalid("Provider English region is missing or invalid.");
  }

  return {
    city_ko: city.ko,
    city_en: city.en,
    region_ko: regionKo,
    region_en: regionEn,
  };
}

export function buildNaverGeocodeRequest(
  address: string,
  apiKeyId: string,
  apiKey: string,
): Request {
  if (!address.trim() || !apiKeyId.trim() || !apiKey.trim()) {
    throw new GeocodingProviderError(
      "provider_configuration_invalid",
      "Address and NAVER credentials are required.",
    );
  }

  const url = new URL(NAVER_GEOCODE_ENDPOINT);
  url.searchParams.set("query", address.trim());
  url.searchParams.set("count", String(MAX_GEOCODE_CANDIDATES));

  return new Request(url, {
    method: "GET",
    headers: {
      Accept: "application/json",
      "x-ncp-apigw-api-key-id": apiKeyId.trim(),
      "x-ncp-apigw-api-key": apiKey.trim(),
    },
    redirect: "error",
  });
}

export function parseNaverGeocodeResponse(
  payload: unknown,
): GeocodeCandidate[] {
  if (!isRecord(payload) || payload.status !== "OK") {
    providerResponseInvalid("Provider response status is invalid.");
  }

  if (!isRecord(payload.meta)) {
    providerResponseInvalid("Provider response metadata is invalid.");
  }
  const totalCount = nonNegativeInteger(payload.meta, "totalCount");
  const page = nonNegativeInteger(payload.meta, "page");
  const count = nonNegativeInteger(payload.meta, "count");
  if (page < 1 || count > MAX_GEOCODE_CANDIDATES) {
    providerResponseInvalid("Provider response metadata is out of range.");
  }

  if (
    !Array.isArray(payload.addresses) ||
    payload.addresses.length > MAX_GEOCODE_CANDIDATES ||
    payload.addresses.length !== count ||
    totalCount < payload.addresses.length
  ) {
    providerResponseInvalid("Provider candidate count is invalid.");
  }

  return payload.addresses.map((candidate) => {
    if (!isRecord(candidate)) {
      providerResponseInvalid("Provider candidate is invalid.");
    }

    const roadAddress = boundedString(candidate, "roadAddress", 500);
    const jibunAddress = boundedString(candidate, "jibunAddress", 500);
    const englishAddress = boundedString(candidate, "englishAddress", 500);
    if (!roadAddress && !jibunAddress) {
      providerResponseInvalid("Provider candidate has no usable address.");
    }

    return {
      road_address: roadAddress,
      jibun_address: jibunAddress,
      english_address: englishAddress,
      ...canonicalLocation(candidate, englishAddress),
      // NAVER's x is longitude and y is latitude. Keep this explicit: a
      // silent axis swap can put a saved exhibition thousands of km away.
      longitude: coordinate(candidate, "x", -180, 180),
      latitude: coordinate(candidate, "y", -90, 90),
    };
  });
}
