import { useState } from "react";
import { getBackendUrlOverride, setBackendUrlOverride } from "../../api/client";
import { getThemePreference, setThemePreference, type ThemePreference } from "../../theme";

export function GeneralSettingsTab() {
  const [url, setUrl] = useState(getBackendUrlOverride() ?? "");
  const [saved, setSaved] = useState(false);
  const [theme, setTheme] = useState<ThemePreference>(getThemePreference());

  function handleSave() {
    setBackendUrlOverride(url.trim() || null);
    setSaved(true);
    setTimeout(() => window.location.reload(), 400);
  }

  function handleThemeChange(next: ThemePreference) {
    setTheme(next);
    setThemePreference(next);
  }

  return (
    <div>
      <h2>Appearance</h2>
      <div className="band-selector">
        {(["system", "light", "dark"] as const).map((option) => (
          <button
            key={option}
            className={theme === option ? "active" : ""}
            onClick={() => handleThemeChange(option)}
          >
            {option === "system" ? "System" : option === "light" ? "Light" : "Dark"}
          </button>
        ))}
      </div>

      <p className="page-hint">
        By default this PWA talks to the backend that served it. If you installed it to your home screen and want it
        to point at a different self-hosted whyfi instance on your LAN, set that here — note that login only works
        against the backend that served this page (same-origin session cookie); pointing at a different backend here
        currently means viewing data there won't be possible until you're served from that origin directly.
      </p>

      <label className="field">
        <span>Backend URL</span>
        <input
          type="url"
          placeholder="http://192.168.1.50:8000"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
        />
      </label>

      <button onClick={handleSave}>Save &amp; reload</button>
      {saved && <p className="page-hint">Saved. Reloading…</p>}

      <h2>iOS note</h2>
      <p className="page-hint">
        This PWA can be installed on iOS, but iOS never exposes WiFi/cellular/Bluetooth scanning to any app, native or
        web. On iOS this is always a viewer of data an Android device collected — see docs/architecture.md.
      </p>
    </div>
  );
}
