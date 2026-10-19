/** A point on the map in degrees. */
export interface LatLng {
  lat: number;
  lng: number;
}

const PRECISION = 1e5;
export const MAX_POLYLINE_POINTS = 500;

function encodeValue(value: number): string {
  let shifted = value < 0 ? ~(value << 1) : value << 1;
  let encoded = "";
  while (shifted >= 0x20) {
    encoded += String.fromCharCode((0x20 | (shifted & 0x1f)) + 63);
    shifted >>>= 5;
  }
  return encoded + String.fromCharCode(shifted + 63);
}

/** Encodes points as an encoded polyline, precision 5, latitude first. */
export function encodePolyline(points: readonly LatLng[]): string {
  let previousLat = 0;
  let previousLng = 0;
  let encoded = "";
  for (const point of points) {
    const lat = Math.round(point.lat * PRECISION);
    const lng = Math.round(point.lng * PRECISION);
    encoded += encodeValue(lat - previousLat) + encodeValue(lng - previousLng);
    previousLat = lat;
    previousLng = lng;
  }
  return encoded;
}

/** Decodes an encoded polyline (precision 5); malformed input gives null. */
export function decodePolyline(encoded: string): LatLng[] | null {
  const values: number[] = [];
  let index = 0;
  while (index < encoded.length) {
    let result = 0;
    let shift = 0;
    let chunk: number;
    do {
      if (index >= encoded.length || shift > 30) return null;
      chunk = encoded.charCodeAt(index++) - 63;
      if (chunk < 0 || chunk > 63) return null;
      result |= (chunk & 0x1f) << shift;
      shift += 5;
    } while (chunk >= 0x20);
    values.push(result & 1 ? ~(result >> 1) : result >> 1);
  }
  if (values.length % 2 !== 0) return null;
  const points: LatLng[] = [];
  let lat = 0;
  let lng = 0;
  for (let position = 0; position < values.length; position += 2) {
    lat += values[position];
    lng += values[position + 1];
    points.push({
      lat: Math.round(lat) / PRECISION,
      lng: Math.round(lng) / PRECISION,
    });
  }
  return points;
}

/** The same path walked the other way; malformed input gives null. */
export function reversePolyline(encoded: string): string | null {
  const points = decodePolyline(encoded);
  return points ? encodePolyline(points.reverse()) : null;
}

const METERS_PER_DEGREE = 111_320;

function offsetMeters(point: LatLng, origin: LatLng): [number, number] {
  const scale = Math.cos(origin.lat * Math.PI / 180);
  return [
    (point.lng - origin.lng) * METERS_PER_DEGREE * scale,
    (point.lat - origin.lat) * METERS_PER_DEGREE,
  ];
}

function segmentDistance(
  point: [number, number],
  start: [number, number],
  end: [number, number],
): number {
  const dx = end[0] - start[0];
  const dy = end[1] - start[1];
  const lengthSquared = dx * dx + dy * dy;
  const t = lengthSquared === 0 ? 0 : Math.max(
    0,
    Math.min(
      1,
      ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) /
        lengthSquared,
    ),
  );
  return Math.hypot(point[0] - start[0] - t * dx, point[1] - start[1] - t * dy);
}

function douglasPeucker(
  projected: readonly [number, number][],
  tolerance: number,
): boolean[] {
  const keep = new Array<boolean>(projected.length).fill(false);
  keep[0] = true;
  keep[projected.length - 1] = true;
  const stack: Array<[number, number]> = [[0, projected.length - 1]];
  while (stack.length > 0) {
    const [first, last] = stack.pop()!;
    let farthest = -1;
    let farthestDistance = tolerance;
    for (let index = first + 1; index < last; index += 1) {
      const distance = segmentDistance(
        projected[index],
        projected[first],
        projected[last],
      );
      if (distance > farthestDistance) {
        farthest = index;
        farthestDistance = distance;
      }
    }
    if (farthest >= 0) {
      keep[farthest] = true;
      stack.push([first, farthest], [farthest, last]);
    }
  }
  return keep;
}

/**
 * Douglas–Peucker simplification to at most [maxPoints] points, keeping both ends. A path already within the limit
 * is returned unchanged; otherwise the tolerance starts at half a metre and doubles until the path fits.
 */
export function simplifyPolyline(
  points: readonly LatLng[],
  maxPoints = MAX_POLYLINE_POINTS,
): LatLng[] {
  if (points.length <= maxPoints) return [...points];
  const projected = points.map((point) => offsetMeters(point, points[0]));
  for (let tolerance = 0.5;; tolerance *= 2) {
    const keep = douglasPeucker(projected, tolerance);
    const kept = points.filter((_, index) => keep[index]);
    if (kept.length <= maxPoints) return kept;
  }
}
