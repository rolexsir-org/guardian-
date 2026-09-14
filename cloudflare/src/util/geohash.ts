/**
 * Minimal geohash encoder.
 *
 * Used to (a) bucket community safety events and (b) address the Durable Object
 * that fans realtime events out to clients in the same area. Precision 4 covers
 * roughly 39 km x 19.5 km, which matches the realtime "nearby" broadcast cell.
 */

const BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

export function geohash(latitude: number, longitude: number, precision = 4): string {
  let latMin = -90;
  let latMax = 90;
  let lngMin = -180;
  let lngMax = 180;
  let hash = "";
  let bit = 0;
  let character = 0;
  let even = true;

  while (hash.length < precision) {
    if (even) {
      const midpoint = (lngMin + lngMax) / 2;
      if (longitude >= midpoint) {
        character = (character << 1) + 1;
        lngMin = midpoint;
      } else {
        character <<= 1;
        lngMax = midpoint;
      }
    } else {
      const midpoint = (latMin + latMax) / 2;
      if (latitude >= midpoint) {
        character = (character << 1) + 1;
        latMin = midpoint;
      } else {
        character <<= 1;
        latMax = midpoint;
      }
    }
    even = !even;
    bit += 1;
    if (bit === 5) {
      hash += BASE32[character] ?? "0";
      bit = 0;
      character = 0;
    }
  }

  return hash;
}

/** Haversine distance in metres. */
export function distanceMeters(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const earthRadius = 6_371_000;
  const toRadians = (value: number): number => (value * Math.PI) / 180;
  const dLat = toRadians(lat2 - lat1);
  const dLng = toRadians(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
  return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/**
 * Latitude/longitude bounding box for a radius search. The box is intentionally
 * slightly larger than the radius; the caller filters the exact distance.
 */
export function boundingBox(
  latitude: number,
  longitude: number,
  radiusMeters: number,
): { minLat: number; maxLat: number; minLng: number; maxLng: number } {
  const latDelta = radiusMeters / 111_320;
  const cosLat = Math.max(Math.cos((latitude * Math.PI) / 180), 0.01);
  const lngDelta = radiusMeters / (111_320 * cosLat);
  return {
    minLat: Math.max(latitude - latDelta, -90),
    maxLat: Math.min(latitude + latDelta, 90),
    minLng: Math.max(longitude - lngDelta, -180),
    maxLng: Math.min(longitude + lngDelta, 180),
  };
}
