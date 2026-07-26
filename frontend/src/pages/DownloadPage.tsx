import QRCode from "qrcode";
import { useEffect, useRef, useState } from "react";
import { api, downloadWithProgress } from "../api/client";
import type { AppRelease, BuildStatusResponse } from "../api/types";

const ACTIVE_STATES = new Set(["QUEUED", "BUILDING"]);

export function DownloadPage() {
  const [release, setRelease] = useState<AppRelease | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [buildStatus, setBuildStatus] = useState<BuildStatusResponse | null>(null);
  const [triggerError, setTriggerError] = useState<string | null>(null);
  const pollTimeoutRef = useRef<number | null>(null);

  const [isDownloading, setIsDownloading] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState<number | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [downloadReady, setDownloadReady] = useState(false);

  function loadLatestRelease() {
    api
      .latestRelease()
      .then((r) => {
        setRelease(r);
        setNotFound(false);
      })
      .catch(() => setNotFound(true));
  }

  function pollBuildStatus() {
    api.androidBuildStatus().then((status) => {
      setBuildStatus(status);
      if (ACTIVE_STATES.has(status.build_status)) {
        pollTimeoutRef.current = window.setTimeout(pollBuildStatus, 3000);
      } else if (status.build_status === "SUCCESS") {
        loadLatestRelease();
      }
    });
  }

  useEffect(() => {
    loadLatestRelease();
    pollBuildStatus();
    return () => {
      if (pollTimeoutRef.current) window.clearTimeout(pollTimeoutRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!release?.download_url) {
      setQrDataUrl(null);
      return;
    }
    QRCode.toDataURL(release.download_url, { width: 220, margin: 1 }).then(setQrDataUrl);
  }, [release]);

  async function handleBuildClick() {
    setTriggerError(null);
    try {
      await api.triggerAndroidBuild();
      pollBuildStatus();
    } catch (err) {
      const detail = err instanceof Error ? err.message : "Unknown error";
      setTriggerError(`Could not start a build — ${detail}`);
    }
  }

  async function handleDownloadClick() {
    if (!release?.download_url) return;
    setDownloadError(null);
    setDownloadReady(false);
    setDownloadProgress(null);
    setIsDownloading(true);
    try {
      const blob = await downloadWithProgress(release.download_url, (received, total) => {
        setDownloadProgress(total > 0 ? received / total : null);
      });

      // Catches a transfer truncated/corrupted in transit (flaky mobile
      // network, a reverse proxy mishandling a large binary response) —
      // without this check Android's installer just fails with an
      // unhelpful "app not installed" and no indication why. See MEMORY.md.
      if (release.apk_size != null && blob.size !== release.apk_size) {
        throw new Error(
          `Downloaded ${blob.size} bytes but expected ${release.apk_size} — the transfer was likely corrupted. Try again.`,
        );
      }

      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = `whyfi-${release.version_name}.apk`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      setTimeout(() => URL.revokeObjectURL(objectUrl), 60000);
      setDownloadReady(true);
    } catch (err) {
      setDownloadError(err instanceof Error ? err.message : "Download failed.");
    } finally {
      setIsDownloading(false);
      setDownloadProgress(null);
    }
  }

  const isBuilding = buildStatus ? ACTIVE_STATES.has(buildStatus.build_status) : false;

  return (
    <section>
      <h1>Download the whyfi Android app</h1>
      <p className="page-hint">
        Scanning WiFi, cellular, and Bluetooth only works from the native Android app — no browser can do it.
      </p>

      <div className="download-card">
        <h2>Build a new version</h2>
        <p className="page-hint">
          Runs in a Docker container (Gradle + Android SDK) already running alongside this backend — no terminal
          needed. Takes a few minutes.
        </p>
        <button onClick={handleBuildClick} disabled={isBuilding}>
          {isBuilding ? `Building… (${buildStatus?.build_status})` : "Build Android App"}
        </button>
        {triggerError && <p className="error-text">{triggerError}</p>}

        {buildStatus && buildStatus.build_status !== "NONE" && (
          <div>
            <p className="page-hint">
              Latest build: {buildStatus.version_name} (build {buildStatus.version_code}) —{" "}
              {buildStatus.build_status}
            </p>
            {buildStatus.build_status === "FAILED" && <p className="error-text">Build failed — see log below.</p>}
            {buildStatus.build_log_tail && (
              <details>
                <summary>Build log</summary>
                <pre className="build-log">{buildStatus.build_log_tail}</pre>
              </details>
            )}
          </div>
        )}
      </div>

      {notFound && <p className="empty-state">No release has been published yet — click "Build Android App" above.</p>}

      {release && release.download_url && (
        <div className="download-card">
          <h2>
            whyfi {release.version_name} <span className="page-hint">(build {release.version_code})</span>
          </h2>
          {release.release_notes && <p>{release.release_notes}</p>}

          <button onClick={handleDownloadClick} disabled={isDownloading}>
            {isDownloading ? "Downloading…" : "Download APK"}
          </button>

          {isDownloading && (
            <div className="progress-track">
              {downloadProgress !== null ? (
                <div className="progress-fill" style={{ width: `${Math.round(downloadProgress * 100)}%` }} />
              ) : (
                <div className="progress-fill progress-fill-indeterminate" />
              )}
            </div>
          )}

          {downloadError && <p className="error-text">{downloadError}</p>}

          {downloadReady && (
            <p className="page-hint">
              Downloaded and verified ({((release.apk_size ?? 0) / (1024 * 1024)).toFixed(1)} MB, byte count matched
              the server). Check your notification shade — tap "Download complete" to open the installer. Android
              requires that tap; no browser is allowed to install an app without it.
            </p>
          )}

          {qrDataUrl && (
            <div>
              <p className="page-hint">Or scan from another device:</p>
              <img src={qrDataUrl} alt="QR code linking to the APK download" width={220} height={220} />
            </div>
          )}
          <p className="page-hint">
            You'll need to enable "install unknown apps" for your browser/file manager the first time.
          </p>
        </div>
      )}
    </section>
  );
}
