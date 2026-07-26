import type { ReactNode } from "react";
import { BackButton } from "./BackButton";
import { GlobalFilterBar } from "./GlobalFilterBar";
import { LastUpdated } from "./LastUpdated";
import { NavBar } from "./NavBar";

export function Layout({ children, onLogout }: { children: ReactNode; onLogout: () => void }) {
  return (
    <div className="app-shell">
      <NavBar onLogout={onLogout} />
      <main className="app-main">
        <GlobalFilterBar />
        <BackButton />
        {children}
      </main>
      <LastUpdated />
    </div>
  );
}
