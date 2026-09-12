import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import DesignPreview from "./components/DesignPreview";
import "./index.css";
import "./family.css";
import { useStore } from "./store/useStore";

const preview = window.location.pathname === "/design-preview";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    {preview ? <DesignPreview /> : <App />}
  </React.StrictMode>,
);

/**
 * Ask the browser to stop evicting this origin under storage pressure.
 *
 * Photos make the local database an order of magnitude larger, which is what
 * turns eviction from theoretical into likely — and eviction here does not
 * merely drop cached messages. It takes `meta` with it, which holds this
 * device's keypair, so the owner comes back to a browser that has to register
 * as a new device and wait for another one to re-wrap the history key.
 *
 * Best-effort by design: a browser may decline, and Firefox prompts. Either way
 * the app must carry on, so the rejection is swallowed.
 */
void navigator.storage?.persist?.().catch(() => {});

// Initialize crypto + stored session on first load
useStore.getState().init();
