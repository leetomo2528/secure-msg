/**
 * Theme manager: "light" | "dark" | "system" (follow OS preference).
 *
 * The resolved choice toggles the `dark` class on <html>; all colors are CSS
 * variables (src/index.css), so no component re-render is needed for the
 * switch. Persisted in localStorage; an inline script in index.html applies
 * the class before first paint to avoid a flash.
 */
export type ThemeMode = "light" | "dark" | "system";

const STORAGE_KEY = "securemsg-theme";
const THEME_COLOR: Record<"light" | "dark", string> = {
  light: "#f3f6fa",
  dark: "#0a0f16",
};

const listeners = new Set<() => void>();

function readStored(): ThemeMode {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === "light" || v === "dark" || v === "system" ? v : "system";
  } catch {
    return "system";
  }
}

let mode: ThemeMode = readStored();
const media = window.matchMedia("(prefers-color-scheme: dark)");

export function isDarkResolved(): boolean {
  return mode === "dark" || (mode === "system" && media.matches);
}

export function applyTheme(): void {
  const dark = isDarkResolved();
  document.documentElement.classList.toggle("dark", dark);
  document.documentElement.style.backgroundColor = THEME_COLOR[dark ? "dark" : "light"];
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", THEME_COLOR[dark ? "dark" : "light"]);
}

export function getThemeMode(): ThemeMode {
  return mode;
}

export function setThemeMode(next: ThemeMode): void {
  mode = next;
  try {
    localStorage.setItem(STORAGE_KEY, next);
  } catch { /* private mode etc. — theme still applies for this session */ }
  applyTheme();
  listeners.forEach((l) => l());
}

export function subscribeTheme(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

media.addEventListener("change", () => {
  if (mode !== "system") return;
  applyTheme();
  listeners.forEach((l) => l());
});

applyTheme();
