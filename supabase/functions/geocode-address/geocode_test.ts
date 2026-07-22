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
        x: "127.0005",
        y: "37.5344",
        distance: 0,
      },
    ],
  });

  assert(candidates.length === 1, "expected one candidate");
  assert(candidates[0].longitude === "127.0005", "x must be longitude");
  assert(candidates[0].latitude === "37.5344", "y must be latitude");
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
