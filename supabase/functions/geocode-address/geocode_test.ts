import {
  buildNaverGeocodeRequest,
  GeocodingProviderError,
  parseNaverGeocodeResponse,
} from "./geocode.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test("NAVER request encodes one address and keeps credentials in headers", () => {
  const request = buildNaverGeocodeRequest(
    "서울 용산구 한남대로 28",
    "server-client-id",
    "server-client-secret",
  );
  const url = new URL(request.url);

  assert(
    url.origin === "https://maps.apigw.ntruss.com",
    "unexpected provider host",
  );
  assert(
    url.pathname === "/map-geocode/v2/geocode",
    "unexpected provider path",
  );
  assert(
    url.searchParams.get("query") === "서울 용산구 한남대로 28",
    "query was not encoded",
  );
  assert(
    url.searchParams.get("count") === "3",
    "candidate count must be bounded",
  );
  assert(
    !request.url.includes("server-client-secret"),
    "secret leaked into URL",
  );
  assert(
    request.headers.get("x-ncp-apigw-api-key-id") === "server-client-id",
    "missing client ID header",
  );
  assert(
    request.headers.get("x-ncp-apigw-api-key") === "server-client-secret",
    "missing secret header",
  );
});

Deno.test("NAVER x longitude and y latitude map to the public candidate contract", () => {
  const candidates = parseNaverGeocodeResponse({
    status: "OK",
    meta: { totalCount: 1, page: 1, count: 1 },
    addresses: [
      {
        roadAddress: "서울 용산구 한남대로 28",
        jibunAddress: "서울 용산구 한남동 1-1",
        englishAddress: "28 Hannam-daero, Yongsan-gu, Seoul",
        addressElements: [
          {
            types: ["SIDO"],
            longName: "서울특별시",
            shortName: "서울특별시",
            code: "",
          },
          {
            types: ["SIGUGUN"],
            longName: "용산구",
            shortName: "용산구",
            code: "",
          },
        ],
        x: "127.0005",
        y: "37.5344",
        distance: 0,
      },
    ],
  });

  assert(candidates.length === 1, "expected one candidate");
  assert(candidates[0].longitude === "127.0005", "x must be longitude");
  assert(candidates[0].latitude === "37.5344", "y must be latitude");
  assert(
    candidates[0].city_ko === "서울",
    "SIDO must become canonical Korean city",
  );
  assert(
    candidates[0].city_en === "Seoul",
    "SIDO must become canonical English city",
  );
  assert(
    candidates[0].region_ko === "용산구",
    "SIGUGUN must become Korean region",
  );
  assert(
    candidates[0].region_en === "Yongsan-gu",
    "English address must provide English region",
  );
});

Deno.test("province SIGUGUN is normalized to Gallr's city-level region", () => {
  const [candidate] = parseNaverGeocodeResponse({
    status: "OK",
    meta: { totalCount: 1, page: 1, count: 1 },
    addresses: [{
      roadAddress: "경기도 성남시 분당구 판교역로 166",
      jibunAddress: "경기도 성남시 분당구 백현동 532",
      englishAddress:
        "166 Pangyoyeok-ro, Bundang-gu, Seongnam-si, Gyeonggi-do, Republic of Korea",
      addressElements: [
        { types: ["SIDO"], longName: "경기도", shortName: "경기도", code: "" },
        {
          types: ["SIGUGUN"],
          longName: "성남시 분당구",
          shortName: "성남시 분당구",
          code: "",
        },
      ],
      x: "127.1109",
      y: "37.3947",
      distance: 0,
    }],
  });

  assert(
    candidate.city_ko === "경기",
    "province must use canonical Korean label",
  );
  assert(
    candidate.city_en === "Gyeonggi",
    "province must use canonical English label",
  );
  assert(
    candidate.region_ko === "성남시",
    "province region must use the first SIGUGUN level",
  );
  assert(
    candidate.region_en === "Seongnam-si",
    "English region must match the Korean region level",
  );
});

Deno.test("malformed or out-of-range provider coordinates fail closed", () => {
  let thrown: unknown;
  try {
    parseNaverGeocodeResponse({
      status: "OK",
      meta: { totalCount: 1, page: 1, count: 1 },
      addresses: [
        {
          roadAddress: "서울",
          jibunAddress: "",
          englishAddress: "Seoul",
          x: "37.5",
          y: "127.0",
          distance: 0,
        },
      ],
    });
  } catch (error) {
    thrown = error;
  }
  assert(
    thrown instanceof GeocodingProviderError,
    "expected provider schema rejection",
  );
});
