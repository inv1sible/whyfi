// Coordinate formatting and outbound map links for printable reports.
//
// OpenStreetMap only, deliberately: it's the same source as the map tiles
// already used, and it keeps the project's no-Google posture (see the
// Android app's avoidance of Play Services). A report can be read by someone
// other than the person who generated it — linking to Google would hand that
// reader's identity plus a surveyed coordinate to a third party.

// The backend buckets coverage points to 5 decimal places (~1.1m), so
// printing more digits than that would invent precision the data hasn't got.
const COORD_DECIMALS = 5;

export function formatCoords(lat: number, lng: number): string {
  return `${lat.toFixed(COORD_DECIMALS)}, ${lng.toFixed(COORD_DECIMALS)}`;
}

/** Deep link that drops a marker at the exact point, rather than merely
 * centring there — `mlat`/`mlon` are what place the pin; the `#map=` hash
 * only sets the initial zoom and centre. */
export function osmLink(lat: number, lng: number): string {
  const la = lat.toFixed(COORD_DECIMALS);
  const ln = lng.toFixed(COORD_DECIMALS);
  return `https://www.openstreetmap.org/?mlat=${la}&mlon=${ln}#map=18/${la}/${ln}`;
}
