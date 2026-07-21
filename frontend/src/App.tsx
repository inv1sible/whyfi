import { useEffect, useState } from "react";
import { Route, Routes } from "react-router-dom";
import { api } from "./api/client";
import { Layout } from "./components/layout/Layout";
import { BLEDeviceDetailPage } from "./pages/BLEDeviceDetailPage";
import { BLEDevicesPage } from "./pages/BLEDevicesPage";
import { CellularPage } from "./pages/CellularPage";
import { ChannelCongestionPage } from "./pages/ChannelCongestionPage";
import { DashboardPage } from "./pages/DashboardPage";
import { DownloadPage } from "./pages/DownloadPage";
import { HeatmapPage } from "./pages/HeatmapPage";
import { LANDevicesPage } from "./pages/LANDevicesPage";
import { LoginPage } from "./pages/LoginPage";
import { NetworkDetailPage } from "./pages/NetworkDetailPage";
import { SatelliteViewPage } from "./pages/SatelliteViewPage";
import { SettingsPage } from "./pages/SettingsPage";

type AuthState = "checking" | "authenticated" | "anonymous";

export function App() {
  const [authState, setAuthState] = useState<AuthState>("checking");

  useEffect(() => {
    api
      .session()
      .then((s) => setAuthState(s.authenticated ? "authenticated" : "anonymous"))
      .catch(() => setAuthState("anonymous"));
  }, []);

  if (authState === "checking") {
    return <div className="app-loading">Loading…</div>;
  }

  if (authState === "anonymous") {
    return <LoginPage onLoggedIn={() => setAuthState("authenticated")} />;
  }

  return (
    <Layout onLogout={() => setAuthState("anonymous")}>
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/networks/:bssid" element={<NetworkDetailPage />} />
        <Route path="/channel-congestion" element={<ChannelCongestionPage />} />
        <Route path="/cellular" element={<CellularPage />} />
        <Route path="/ble-devices" element={<BLEDevicesPage />} />
        <Route path="/ble-devices/:identifier" element={<BLEDeviceDetailPage />} />
        <Route path="/satellites" element={<SatelliteViewPage />} />
        <Route path="/heatmap" element={<HeatmapPage />} />
        <Route path="/lan-devices" element={<LANDevicesPage />} />
        <Route path="/download" element={<DownloadPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Routes>
    </Layout>
  );
}
