import { useState } from "react";
import { GeneralSettingsTab } from "./settings/GeneralSettingsTab";
import { SensorsTab } from "./settings/SensorsTab";

type Tab = "general" | "sensors";

export function SettingsPage() {
  const [tab, setTab] = useState<Tab>("general");

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
