import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { registerSW } from "virtual:pwa-register";
import { App } from "./App";
import { applyTheme, watchSystemTheme } from "./theme";
import "./index.css";

applyTheme();
watchSystemTheme();

// The generated service worker calls skipWaiting()/clientsClaim() on its
// own (registerType: "autoUpdate" in vite.config.ts), but that only takes
// over *future* network requests — it never reloads a tab that's already
// open, so an installed PWA left running silently keeps executing the old
// JS bundle indefinitely, forever "not seeing" any fix. Forcing a reload
// the moment a new version is detected is what actually makes
// "autoUpdate" live up to its name. See MEMORY.md.
registerSW({ immediate: true, onNeedRefresh: () => window.location.reload() });

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
