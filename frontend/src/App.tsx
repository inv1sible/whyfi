import { useEffect, useState } from "react";
import { Route, Routes } from "react-router-dom";
import { api } from "./api/client";
import { Layout } from "./components/layout/Layout";
import { FilterProvider } from "./context/FilterContext";
import { BLEDeviceDetailPage } from "./pages/BLEDeviceDetailPage";
import { BLEDevicesPage } from "./pages/BLEDevicesPage";
import { CellTowerDetailPage } from "./pages/CellTowerDetailPage";
import { CellularPage } from "./pages/CellularPage";
import { ChannelCongestionPage } from "./pages/ChannelCongestionPage";
import { DashboardPage } from "./pages/DashboardPage";
import { DownloadPage } from "./pages/DownloadPage";
import { HeatmapPage } from "./pages/HeatmapPage";
import { LANDeviceDetailPage } from "./pages/LANDeviceDetailPage";
import { LANDevicesPage } from "./pages/LANDevicesPage";
import { LoginPage } from "./pages/LoginPage";
import { ManageScansPage } from "./pages/ManageScansPage";
import { NetworkDetailPage } from "./pages/NetworkDetailPage";
import { RemoteScanPage } from "./pages/RemoteScanPage";
import { SatelliteViewPage } from "./pages/SatelliteViewPage";
import { SettingsPage } from "./pages/SettingsPage";
import { SSIDGroupPage } from "./pages/SSIDGroupPage";

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
    <FilterProvider>
      <Layout onLogout={() => setAuthState("anonymous")}>
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/networks/:bssid" element={<NetworkDetailPage />} />
          <Route path="/networks/ssid/:ssid" element={<SSIDGroupPage />} />
          <Route path="/channel-congestion" element={<ChannelCongestionPage />} />
          <Route path="/cellular" element={<CellularPage />} />
          <Route path="/cellular/:towerKey" element={<CellTowerDetailPage />} />
          <Route path="/ble-devices" element={<BLEDevicesPage />} />
          <Route path="/ble-devices/:identifier" element={<BLEDeviceDetailPage />} />
          <Route path="/satellites" element={<SatelliteViewPage />} />
          <Route path="/heatmap" element={<HeatmapPage />} />
          <Route path="/lan-devices" element={<LANDevicesPage />} />
          <Route path="/lan-devices/:ip" element={<LANDeviceDetailPage />} />
          <Route path="/download" element={<DownloadPage />} />
          <Route path="/remote" element={<RemoteScanPage />} />
          <Route path="/scans" element={<ManageScansPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </Layout>
    </FilterProvider>
  );
}
