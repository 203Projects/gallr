import type { LatLng } from "./polyline.ts";

const KEY_DECIMALS = 5;
const EARTH_RADIUS_METERS = 6_371_008.8;

/**
 * Rounds like Postgres `round(value::double precision::numeric, 5)`: the double is read with 15 significant digits
 * (DBL_DIG), then rounded half away from zero, always printing five decimals.
 */
function roundedDecimal(value: number): string {
  const text = value.toPrecision(15);
  if (!/^-?\d+(\.\d+)?$/u.test(text)) throw new RangeError("Out of range.");
  const negative = text.startsWith("-");
  const [whole, fraction = ""] = (negative ? text.slice(1) : text).split(".");
  const padded = fraction.padEnd(KEY_DECIMALS + 1, "0");
  let scaled = BigInt(whole + padded.slice(0, KEY_DECIMALS));
  if (padded.charCodeAt(KEY_DECIMALS) >= "5".charCodeAt(0)) scaled += 1n;
  const digits = scaled.toString().padStart(KEY_DECIMALS + 1, "0");
  const sign = negative && scaled !== 0n ? "-" : "";
  return `${sign}${digits.slice(0, -KEY_DECIMALS)}.${
    digits.slice(-KEY_DECIMALS)
  }`;
}

/** The cache key of a position, identical to `content_private.walking_position_key`. */
export function positionKey(lat: number, lng: number): string {
  return `${roundedDecimal(lat)},${roundedDecimal(lng)}`;
}

/** Cache rows are undirected: the bytewise-smaller key comes first. */
export function orderedPair(
  fromKey: string,
  toKey: string,
): { fromKey: string; toKey: string; reversed: boolean } {
  return fromKey < toKey
    ? { fromKey, toKey, reversed: false }
    : { fromKey: toKey, toKey: fromKey, reversed: true };
}

/** Great-circle distance in metres. */
export function distanceMeters(from: LatLng, to: LatLng): number {
  const radians = Math.PI / 180;
  const dLat = (to.lat - from.lat) * radians;
  const dLng = (to.lng - from.lng) * radians;
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(from.lat * radians) * Math.cos(to.lat * radians) *
      Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
}
