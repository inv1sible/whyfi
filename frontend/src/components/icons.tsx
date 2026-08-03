// Small hand-rolled icon set (basic shapes, no path-data memorization risk,
// no new dependency) — one per nav item. Consistent 20x20 viewBox,
// stroke=currentColor so they follow the active/inactive link color.
import type { SVGProps } from "react";

type IconName =
  | "dashboard"
  | "channels"
  | "cellular"
  | "ble"
  | "location"
  | "heatmap"
  | "lan"
  | "download"
  | "scans"
  | "remote"
  | "settings"
  | "crash";

const commonProps: SVGProps<SVGSVGElement> = {
  width: 18,
  height: 18,
  viewBox: "0 0 20 20",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.6,
  strokeLinecap: "round",
  strokeLinejoin: "round",
};

export function NavIcon({ name }: { name: IconName }) {
  switch (name) {
    case "dashboard":
      return (
        <svg {...commonProps}>
          <rect x="2.5" y="2.5" width="6" height="6" />
          <rect x="11.5" y="2.5" width="6" height="6" />
          <rect x="2.5" y="11.5" width="6" height="6" />
          <rect x="11.5" y="11.5" width="6" height="6" />
        </svg>
      );
    case "channels":
      return (
        <svg {...commonProps}>
          <line x1="5" y1="17" x2="5" y2="9" />
          <line x1="10" y1="17" x2="10" y2="3" />
          <line x1="15" y1="17" x2="15" y2="12" />
        </svg>
      );
    case "cellular":
      return (
        <svg {...commonProps}>
          <line x1="3.5" y1="17" x2="3.5" y2="13.5" />
          <line x1="7.8" y1="17" x2="7.8" y2="10" />
          <line x1="12.1" y1="17" x2="12.1" y2="6.5" />
          <line x1="16.5" y1="17" x2="16.5" y2="3" />
        </svg>
      );
    case "ble":
      return (
        <svg {...commonProps}>
          <circle cx="10" cy="4" r="1.6" />
          <circle cx="4" cy="10" r="1.6" />
          <circle cx="16" cy="10" r="1.6" />
          <circle cx="10" cy="16" r="1.6" />
          <line x1="10" y1="5.6" x2="5.2" y2="9" />
          <line x1="10" y1="5.6" x2="14.8" y2="9" />
          <line x1="10" y1="14.4" x2="5.2" y2="11" />
          <line x1="10" y1="14.4" x2="14.8" y2="11" />
        </svg>
      );
    case "location":
      return (
        <svg {...commonProps}>
          <path d="M10 18s-6-5.2-6-9.5A6 6 0 0 1 16 8.5C16 12.8 10 18 10 18z" />
          <circle cx="10" cy="8.3" r="2" />
        </svg>
      );
    case "heatmap":
      return (
        <svg {...commonProps}>
          <polygon points="2.5,5 7.5,3.2 12.5,5 17.5,3.2 17.5,15 12.5,16.8 7.5,15 2.5,16.8" />
          <line x1="7.5" y1="3.2" x2="7.5" y2="15" />
          <line x1="12.5" y1="5" x2="12.5" y2="16.8" />
        </svg>
      );
    case "lan":
      return (
        <svg {...commonProps}>
          <rect x="7" y="2.5" width="6" height="4" rx="1" />
          <line x1="10" y1="6.5" x2="10" y2="9.5" />
          <line x1="4" y1="9.5" x2="16" y2="9.5" />
          <line x1="4" y1="9.5" x2="4" y2="13" />
          <line x1="10" y1="9.5" x2="10" y2="13" />
          <line x1="16" y1="9.5" x2="16" y2="13" />
          <rect x="2" y="13" width="4" height="4" rx="1" />
          <rect x="8" y="13" width="4" height="4" rx="1" />
          <rect x="14" y="13" width="4" height="4" rx="1" />
        </svg>
      );
    case "download":
      return (
        <svg {...commonProps}>
          <line x1="10" y1="2.5" x2="10" y2="12.5" />
          <polyline points="5.5,9 10,13.5 14.5,9" />
          <line x1="3.5" y1="17" x2="16.5" y2="17" />
        </svg>
      );
    case "scans":
      return (
        <svg {...commonProps}>
          <path d="M4 5h12l-1 11.5a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 5 16.5z" />
          <line x1="2.5" y1="5" x2="17.5" y2="5" />
          <path d="M7.5 5V3.5A1.5 1.5 0 0 1 9 2h2a1.5 1.5 0 0 1 1.5 1.5V5" />
          <line x1="8" y1="8.5" x2="8" y2="13.5" />
          <line x1="12" y1="8.5" x2="12" y2="13.5" />
        </svg>
      );
    case "remote":
      // A phone with signal arcs coming off it — controlling a device remotely.
      return (
        <svg {...commonProps}>
          <rect x="2.5" y="2.5" width="8" height="15" rx="1.5" />
          <line x1="5.5" y1="14.5" x2="7.5" y2="14.5" />
          <path d="M13.5 7a4 4 0 0 1 0 6" />
          <path d="M15.8 4.7a7.2 7.2 0 0 1 0 10.6" />
        </svg>
      );
    case "settings":
      return (
        <svg {...commonProps}>
          <line x1="3" y1="6" x2="17" y2="6" />
          <circle cx="12" cy="6" r="1.8" />
          <line x1="3" y1="10.5" x2="17" y2="10.5" />
          <circle cx="7" cy="10.5" r="1.8" />
          <line x1="3" y1="15" x2="17" y2="15" />
          <circle cx="13" cy="15" r="1.8" />
        </svg>
      );
    case "crash":
      // A plain warning triangle — distinct from "scans" (a phone) even
      // though both are diagnostic/housekeeping pages.
      return (
        <svg {...commonProps}>
          <path d="M10 3 2.5 16.5h15Z" />
          <line x1="10" y1="8.5" x2="10" y2="12.5" />
          <circle cx="10" cy="14.7" r="0.9" fill="currentColor" stroke="none" />
        </svg>
      );
    default:
      return null;
  }
}
