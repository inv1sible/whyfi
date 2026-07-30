import { useState } from "react";

// Long enough for a cold tile fetch over a slow link, short enough that a
// blocked or unreachable tile server can't wedge the button. On timeout we
// print anyway — a map with a partial basemap still carries the coverage
// shapes, which are the actual content.
const PREPARE_TIMEOUT_MS = 3000;

// Upper bound on how long data stays frozen waiting for an afterprint that
// may never arrive. Generous — someone reviewing a print preview shouldn't
// have the page refresh under them — but finite.
const RESUME_BACKSTOP_MS = 120_000;

interface PrintReportButtonProps {
  /** Resolves once the page is ready to be captured — in practice, once the
   * map has been resized, re-framed, and its tiles have loaded. */
  onPrepare?: () => Promise<void>;
  /** Called before preparing and again after the print dialog closes. Used to
   * pause polling, so a refresh can't swap the data out from under a report
   * between clicking Print and the dialog opening. */
  onPrintingChange?: (printing: boolean) => void;
  label?: string;
}

export function PrintReportButton({ onPrepare, onPrintingChange, label = "Print report" }: PrintReportButtonProps) {
  const [preparing, setPreparing] = useState(false);

  async function handleClick() {
    setPreparing(true);
    onPrintingChange?.(true);
    try {
      if (onPrepare) {
        await Promise.race([
          onPrepare(),
          new Promise<void>((resolve) => setTimeout(resolve, PREPARE_TIMEOUT_MS)),
        ]);
      }

      // Releasing the pause right after window.print() returns would be wrong:
      // it blocks until the dialog closes in most browsers but not all, and in
      // the ones where it returns immediately a poll could land while the
      // dialog is still open — exactly what the pause exists to prevent.
      // afterprint is the reliable signal. The timer is a backstop so a
      // never-fired afterprint can't leave polling paused forever.
      const release = () => {
        window.removeEventListener("afterprint", release);
        clearTimeout(backstop);
        onPrintingChange?.(false);
      };
      const backstop = setTimeout(release, RESUME_BACKSTOP_MS);
      window.addEventListener("afterprint", release);

      window.print();
    } catch (err) {
      // Still print — a report with a badly framed map beats no report. But
      // say so: a silently swallowed failure here once produced a printed map
      // zoomed out to three countries with no indication anything had gone
      // wrong, and the cause took a headless render to find.
      console.warn("whyfi: preparing the page for print failed; printing anyway", err);
      onPrintingChange?.(false);
    } finally {
      setPreparing(false);
    }
  }

  return (
    <button className="print-hide" onClick={handleClick} disabled={preparing}>
      {preparing ? "Preparing…" : label}
    </button>
  );
}
