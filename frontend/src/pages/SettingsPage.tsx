import { useSearchParams } from "react-router-dom";
import { GeneralSettingsTab } from "./settings/GeneralSettingsTab";
import { SensorsTab } from "./settings/SensorsTab";

type Tab = "general" | "sensors";

// URL-addressable (?tab=sensors) rather than plain useState, so other pages
// (see DownloadPage's "create a sensor first" link) can deep-link straight
// to the Sensors tab instead of landing on General and making the reader
// find it themselves.
export function SettingsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab: Tab = searchParams.get("tab") === "sensors" ? "sensors" : "general";

  function setTab(next: Tab) {
    setSearchParams(next === "general" ? {} : { tab: next });
  }

  return (
    <section>
      <h1>Settings</h1>

      <div className="band-selector">
        <button className={tab === "general" ? "active" : ""} onClick={() => setTab("general")}>
          General
        </button>
        <button className={tab === "sensors" ? "active" : ""} onClick={() => setTab("sensors")}>
          Sensors
        </button>
      </div>

      {tab === "general" && <GeneralSettingsTab />}
      {tab === "sensors" && <SensorsTab />}
    </section>
  );
}
