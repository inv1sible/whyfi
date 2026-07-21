import type { ReactNode } from "react";
import { LastUpdated } from "./LastUpdated";
import { NavBar } from "./NavBar";

export function Layout({ children, onLogout }: { children: ReactNode; onLogout: () => void }) {
  return (
    <div className="app-shell">
      <NavBar onLogout={onLogout} />
      <main className="app-main">{children}</main>
      <LastUpdated />
    </div>
  );
}
