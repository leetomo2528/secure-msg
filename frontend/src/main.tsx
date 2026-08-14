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

// Initialize crypto + stored session on first load
useStore.getState().init();
