import L from "leaflet";

// Every radio/medium type that can get its own coverage shape on a map —
// wider than the API's HeatmapSource ("wifi"|"cellular"|"ble" only, since
// that's a heatmap-endpoint query param) because LAN devices get the same
// coverage-shape treatment on their own detail page despite LAN not being
// a heatmap source.
export type MapIconType = "wifi" | "cellular" | "ble" | "lan";

// Same glyph geometry as the nav bar's WiFi/cellular/BLE/LAN icons (see
// components/icons.tsx) — reused here as raw SVG strings (rather than
// rendering the React components to a string) since Leaflet markers need
// plain HTML, and it keeps the "estimated AP/tower/device location" marker
// visually consistent with the rest of the app.
const GLYPHS: Record<MapIconType, string> = {
  wifi: '<line x1="5" y1="17" x2="5" y2="9"/><line x1="10" y1="17" x2="10" y2="3"/><line x1="15" y1="17" x2="15" y2="12"/>',
  cellular:
    '<line x1="3.5" y1="17" x2="3.5" y2="13.5"/><line x1="7.8" y1="17" x2="7.8" y2="10"/>' +
    '<line x1="12.1" y1="17" x2="12.1" y2="6.5"/><line x1="16.5" y1="17" x2="16.5" y2="3"/>',
  ble:
    '<circle cx="10" cy="4" r="1.6"/><circle cx="4" cy="10" r="1.6"/><circle cx="16" cy="10" r="1.6"/>' +
    '<circle cx="10" cy="16" r="1.6"/><line x1="10" y1="5.6" x2="5.2" y2="9"/><line x1="10" y1="5.6" x2="14.8" y2="9"/>' +
    '<line x1="10" y1="14.4" x2="5.2" y2="11"/><line x1="10" y1="14.4" x2="14.8" y2="11"/>',
  lan:
    '<rect x="7" y="2.5" width="6" height="4" rx="1"/><line x1="10" y1="6.5" x2="10" y2="9.5"/>' +
    '<line x1="4" y1="9.5" x2="16" y2="9.5"/><line x1="4" y1="9.5" x2="4" y2="13"/>' +
    '<line x1="10" y1="9.5" x2="10" y2="13"/><line x1="16" y1="9.5" x2="16" y2="13"/>' +
    '<rect x="2" y="13" width="4" height="4" rx="1"/><rect x="8" y="13" width="4" height="4" rx="1"/>' +
    '<rect x="14" y="13" width="4" height="4" rx="1"/>',
};

const SIZE = 22;

/** A small dark circular badge with the radio type's glyph, centered on a
 * coverage polygon's estimated location (the weighted centroid — see
 * classifyCoverage in geo.ts). */
export function radioMarkerIcon(radioType: MapIconType): L.DivIcon {
  const html = `
    <div style="
      width: ${SIZE}px; height: ${SIZE}px; border-radius: 50%;
      background: #1a2540; border: 2px solid #fff;
      box-shadow: 0 1px 3px rgba(0,0,0,0.4);
      display: flex; align-items: center; justify-content: center;
    ">
      <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="#fff" stroke-width="1.8"
           stroke-linecap="round" stroke-linejoin="round">
        ${GLYPHS[radioType]}
      </svg>
    </div>
  `;
  return L.divIcon({
    html,
    className: "", // clear Leaflet's default marker-icon class/styling
    iconSize: [SIZE, SIZE],
    iconAnchor: [SIZE / 2, SIZE / 2],
  });
}

const PIN_WIDTH = 22;
const PIN_HEIGHT = 28;

/** A classic map-pin teardrop — marks where the *phone* stood for one exact
 * scan (as opposed to radioMarkerIcon, which marks the estimated location
 * of the AP/tower/device itself). Anchored at the pin's bottom tip so it
 * points exactly at the coordinate, not its own center. */
export function scanPointPinIcon(): L.DivIcon {
  const html = `
    <svg width="${PIN_WIDTH}" height="${PIN_HEIGHT}" viewBox="0 0 22 28" xmlns="http://www.w3.org/2000/svg">
      <path d="M11 1C5.5 1 1 5.4 1 10.8 1 18.5 11 27 11 27s10-8.5 10-16.2C21 5.4 16.5 1 11 1z"
            fill="#2563eb" stroke="#fff" stroke-width="1.5"/>
      <circle cx="11" cy="10.8" r="3.2" fill="#fff"/>
    </svg>
  `;
  return L.divIcon({
    html,
    className: "",
    iconSize: [PIN_WIDTH, PIN_HEIGHT],
    iconAnchor: [PIN_WIDTH / 2, PIN_HEIGHT - 1],
  });
}
