import { NavLink } from "react-router-dom";
import { api } from "../../api/client";
import { NavIcon } from "../icons";

const LINKS = [
  { to: "/", label: "Dashboard", end: true, icon: "dashboard" as const },
  { to: "/channel-congestion", label: "WiFi", icon: "channels" as const },
  { to: "/cellular", label: "Cellular", icon: "cellular" as const },
  { to: "/ble-devices", label: "BLE Devices", icon: "ble" as const },
  { to: "/satellites", label: "Location", icon: "location" as const },
  { to: "/heatmap", label: "Heatmap", icon: "heatmap" as const },
  { to: "/lan-devices", label: "LAN", icon: "lan" as const },
  { to: "/download", label: "Download", icon: "download" as const },
  { to: "/remote", label: "Remote", icon: "remote" as const },
  { to: "/scans", label: "Manage Scans", icon: "scans" as const },
  { to: "/settings", label: "Settings", icon: "settings" as const },
];

export function NavBar({ onLogout }: { onLogout: () => void }) {
  async function handleLogout() {
    await api.logout().catch(() => undefined);
    onLogout();
  }

  return (
    <nav className="nav-bar">
      <span className="brand">whyfi</span>
      <div className="nav-links">
        {LINKS.map((link) => (
          <NavLink key={link.to} to={link.to} end={link.end} className={({ isActive }) => (isActive ? "active" : "")}>
            <NavIcon name={link.icon} />
            <span>{link.label}</span>
          </NavLink>
        ))}
        <button onClick={handleLogout}>Log out</button>
      </div>
    </nav>
  );
}
