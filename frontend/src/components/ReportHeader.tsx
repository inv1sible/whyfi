import type { ReactNode } from "react";
import type { MapDisplayMode } from "../context/FilterContext";
import { formatDuration } from "../hooks/useTimeScanFilter";

export interface ReportField {
  label: string;
  value: ReactNode;
}

interface ReportHeaderProps {
  title: string;
  /** What this report shows — counts, spans, ranges. */
  summary: ReportField[];
  /** The view that produced it. Printed so the map is reproducible. */
  viewSettings: ReportField[];
}

/**
 * The block that opens a printed report. Print-only — on screen the live
 * controls already say all of this, and repeating it would be noise.
 *
 * The *view settings* half is the point: a coverage map with no record of
 * which scans and which slider position produced it isn't reportable. Every
 * value here is passed in from the page's own render, so it can't drift from
 * the data the shapes were drawn from.
 *
 * Takes plain props (no data fetching, no context reads) so the entity detail
 * pages can reuse it with their own fields.
 */
export function ReportHeader({ title, summary, viewSettings }: ReportHeaderProps) {
  // Two sibling blocks rather than one wrapper, so the print stylesheet can
  // slot the coverage map between them: title, map, then the detail. A single
  // <header> would force the map below all of it.
  return (
    <>
      <div className="report-title-row print-only">
        <h1>{title}</h1>
        <span className="report-generated">Generated {new Date().toLocaleString()}</span>
      </div>

      <div className="report-fields print-only">
        <section>
          <h2>Summary</h2>
          <dl>
            {summary.map((field) => (
              <div key={field.label}>
                <dt>{field.label}</dt>
                <dd>{field.value}</dd>
              </div>
            ))}
          </dl>
        </section>

        <section>
          <h2>View settings</h2>
          <dl>
            {viewSettings.map((field) => (
              <div key={field.label}>
                <dt>{field.label}</dt>
                <dd>{field.value}</dd>
              </div>
            ))}
          </dl>
        </section>
      </div>
    </>
  );
}

/** How the time/scan filter was set, in the same words the filter bar uses.
 * Shared by every report page so they can't describe it inconsistently. */
export function describeFilterWindow(filter: {
  mode: "time" | "last-n-scans" | "range";
  minutes: number;
  scanCount: number;
  isAllTime: boolean;
  isAllScans: boolean;
  since?: string;
  until?: string;
}): string {
  if (filter.mode === "last-n-scans") {
    return filter.isAllScans ? "All scans" : `Last ${filter.scanCount} scans`;
  }
  if (filter.mode === "range") {
    // Absolute both ends — this is the mode that exists so a report covers a
    // fixed interval, so printing anything relative here would defeat it.
    const from = filter.since ? new Date(filter.since).toLocaleString() : "—";
    const to = filter.until ? new Date(filter.until).toLocaleString() : "—";
    return `${from} to ${to}`;
  }
  if (filter.isAllTime) return "All time";
  // Both the relative phrasing (what the slider says) and the absolute
  // cutoff — a report read next week needs the absolute one to mean anything.
  const relative = formatDuration(filter.minutes);
  return filter.since ? `${relative} (since ${new Date(filter.since).toLocaleString()})` : relative;
}

export function describeDisplayMode(mode: MapDisplayMode): string {
  return mode === "solo"
    ? "Solo — only the scan selected below"
    : "Accumulate — everything up to the scan selected below";
}
