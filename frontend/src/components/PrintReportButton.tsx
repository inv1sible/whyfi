import { useState } from "react";

// Must outlast RadioMap's own tile budget (TILE_MAX_WAIT_MS, 8s), or this
// timeout fires first and the careful settling logic there is wasted — which
// is what made the printed basemap hit-and-miss. On timeout we print anyway:
// a partial basemap still carries the coverage shapes, which are the content.
const PREPARE_TIMEOUT_MS = 9000;

// Upper bound on how long data stays frozen waiting for an afterprint that
// may never arrive. Generous — someone reviewing a print preview shouldn't
// have the page refresh under them — but finite.
const RESUME_BACKSTOP_MS = 120_000;

// How long to give the browser to enter the print flow before concluding it
// isn't going to. Chrome/Firefox fire beforeprint synchronously inside
// print(); anything that hasn't by now has declined silently.
const BEFOREPRINT_GRACE_MS = 1200;

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
  const [blocked, setBlocked] = useState(false);

  /**
   * Hands off to the browser's print flow and reports whether it took.
   *
   * Split out so it can be re-invoked straight from a click. Some browsers
   * only honour print() while a user gesture is still "live", and the await
   * on tile loading above can outlast that — in which case print() returns
   * having done nothing at all. There is no return value or exception to
   * detect that, so we watch for beforeprint instead.
   */
  function startPrinting(): void {
    let entered = false;
    const noteEntered = () => {
      entered = true;
    };
    window.addEventListener("beforeprint", noteEntered);

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

    try {
      window.print();
    } catch (err) {
      // print() is not universally implemented — some embedded and
      // privacy-hardened browsers omit it entirely.
      console.warn("whyfi: window.print() failed", err);
    }

    window.setTimeout(() => {
      window.removeEventListener("beforeprint", noteEntered);
      if (entered) return;
      // Nothing opened. Say so where the user is looking rather than only in
      // the console — a button that silently does nothing is indistinguishable
      // from a broken app, which is exactly how this was first reported.
      setBlocked(true);
      release();
    }, BEFOREPRINT_GRACE_MS);
  }

  async function handleClick() {
    setBlocked(false);
    setPreparing(true);
    onPrintingChange?.(true);
    try {
      if (onPrepare) {
        try {
          await Promise.race([
            onPrepare(),
            new Promise<void>((resolve) => setTimeout(resolve, PREPARE_TIMEOUT_MS)),
          ]);
        } catch (err) {
          // Preparation is best-effort: a report with a badly framed map beats
          // no report. This catch used to wrap window.print() as well, so a
          // throw here skipped printing entirely and the button just went
          // quiet — the "nothing happens" bug. Keep the two separate.
          console.warn("whyfi: preparing the page for print failed; printing anyway", err);
        }
      }
      startPrinting();
    } finally {
      setPreparing(false);
    }
  }

  return (
    <span className="print-hide">
      <button onClick={handleClick} disabled={preparing}>
        {preparing ? "Preparing…" : label}
      </button>
      {blocked && (
        <span className="print-blocked-hint">
          Your browser didn’t open a print dialog. Try again, or use the browser’s own Print command
          (Ctrl/⌘‑P) — the page is already laid out for it.
        </span>
      )}
    </span>
  );
}
