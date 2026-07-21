import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { VitePWA } from "vite-plugin-pwa";

// Base stays '/' (default): the built app is served by Django's WhiteNoise
// at the root via WHITENOISE_ROOT, not under /static/, so the PWA service
// worker gets root scope. See backend/config/settings.py + MEMORY.md.
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["icons/*.png"],
      manifest: {
        name: "whyfi",
        short_name: "whyfi",
        description: "Multi-radio wireless scanner & visualizer",
        theme_color: "#0f766e",
        background_color: "#0f172a",
        display: "standalone",
        start_url: "/",
        icons: [
          { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png" },
          { src: "/icons/maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
      workbox: {
        // App shell only — API responses are always fetched fresh, this is
        // a live viewer, not an offline-data tool.
        navigateFallbackDenylist: [/^\/api\//, /^\/admin\//, /^\/media\//],
      },
    }),
  ],
  server: {
    proxy: {
      "/api": "http://localhost:8000",
    },
  },
});
