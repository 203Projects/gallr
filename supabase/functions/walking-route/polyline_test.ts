import { assertEquals, assertStrictEquals } from "@std/assert";
import {
  decodePolyline,
  encodePolyline,
  type LatLng,
  reversePolyline,
  simplifyPolyline,
} from "./polyline.ts";

// The reference vector from the encoded polyline algorithm format.
const REFERENCE_POINTS: LatLng[] = [
  { lat: 38.5, lng: -120.2 },
  { lat: 40.7, lng: -120.95 },
  { lat: 43.252, lng: -126.453 },
];
const REFERENCE_ENCODED = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";

Deno.test("encodes the reference vector with precision 5, latitude first", () => {
  assertStrictEquals(encodePolyline(REFERENCE_POINTS), REFERENCE_ENCODED);
});

Deno.test("decodes the reference vector", () => {
  assertEquals(decodePolyline(REFERENCE_ENCODED), REFERENCE_POINTS);
});

Deno.test("round-trips Seoul coordinates at five decimals", () => {
  const points: LatLng[] = [
    { lat: 37.5445, lng: 127.056 },
    { lat: 37.54451, lng: 127.05599 },
    { lat: 37.5345, lng: 127.001 },
    { lat: 37.57, lng: 126.98 },
  ];
  assertEquals(decodePolyline(encodePolyline(points)), points);
});

Deno.test("rounds input to five decimals when encoding", () => {
  assertEquals(
    decodePolyline(encodePolyline([{ lat: 37.123456, lng: 127.000004 }])),
    [{ lat: 37.12346, lng: 127 }],
  );
});

Deno.test("decodes malformed input to null", () => {
  for (const malformed of ["_p~iF~ps|U_", "_p~iF", "abc def", "_p~iFÿ"]) {
    assertStrictEquals(decodePolyline(malformed), null, malformed);
  }
  assertEquals(decodePolyline(""), []);
});

Deno.test("reverses an encoded path", () => {
  const reversed = reversePolyline(REFERENCE_ENCODED);
  assertEquals(decodePolyline(reversed!), [...REFERENCE_POINTS].reverse());
  assertStrictEquals(reversePolyline("_p~iF"), null);
});

Deno.test("keeps a path of at most 500 points unchanged", () => {
  const points = Array.from(
    { length: 500 },
    (_, index) => ({
      lat: 37.5 + index * 0.0001,
      lng: 127 + (index % 2) * 0.001,
    }),
  );
  assertEquals(simplifyPolyline(points), points);
});

Deno.test("simplifies a long path to at most 500 points and keeps both endpoints", () => {
  const points = Array.from({ length: 5000 }, (_, index) => ({
    lat: 37.5 + index * 0.00001,
    lng: 127 + Math.sin(index / 7) * 0.0005,
  }));
  const simplified = simplifyPolyline(points);
  assertStrictEquals(simplified.length <= 500, true);
  assertStrictEquals(simplified.length > 2, true);
  assertEquals(simplified[0], points[0]);
  assertEquals(simplified[simplified.length - 1], points[points.length - 1]);
  const indices = simplified.map((point) => points.indexOf(point));
  assertEquals(indices, [...indices].sort((a, b) => a - b));
});

Deno.test("simplifies a straight line to its two ends", () => {
  const points = Array.from({ length: 1000 }, (_, index) => ({
    lat: 37.5 + index * 0.00001,
    lng: 127 + index * 0.00001,
  }));
  assertEquals(simplifyPolyline(points), [points[0], points[999]]);
});

Deno.test("honours a smaller point limit", () => {
  const points = Array.from({ length: 300 }, (_, index) => ({
    lat: 37.5 + index * 0.0001,
    lng: 127 + (index % 3) * 0.0003,
  }));
  assertStrictEquals(simplifyPolyline(points, 50).length <= 50, true);
});
