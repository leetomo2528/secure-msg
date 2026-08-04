import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";
import { fileURLToPath } from "node:url";

// Plain libsodium-wrappers ships WITHOUT crypto_pwhash (Argon2), so password
// hashing needs the sumo build. Its 0.7.x ESM bundle also references a
// missing sibling `libsodium-sumo.mjs`; we bypass the package `exports` map
// and point straight at the CJS bundle, which inlines the wasm.
const SODIUM_CJS = fileURLToPath(
  new URL("./node_modules/libsodium-wrappers-sumo/dist/modules-sumo/libsodium-wrappers.js", import.meta.url),
);

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["icons/icon-192.png", "icons/icon-512.png"],
      manifest: {
        name: "Secure Msg",
        short_name: "SecureMsg",
        description: "Self-hosted E2E-encrypted messenger. Multi-device sync, keyword blocklist, zero PII.",
        theme_color: "#0f172a",
        background_color: "#0f172a",
        display: "standalone",
        start_url: "/",
        icons: [
          { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png" },
          { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
      workbox: {
        // The app shell is cacheable; message data is always fetched live
        // (it's encrypted + stored in IndexedDB anyway).
        globPatterns: ["**/*.{js,css,html,svg,png}"],
        navigateFallback: "/index.html",
      },
    }),
  ],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://127.0.0.1:5050",
      "/socket.io": { target: "http://127.0.0.1:5050", ws: true },
    },
  },
  build: { target: "es2022", sourcemap: false },
  resolve: {
    alias: {
      "libsodium-wrappers-sumo": SODIUM_CJS,
    },
  },
});
