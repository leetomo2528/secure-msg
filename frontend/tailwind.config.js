/** Tailwind + base styles. */
/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Theme tokens (rgb triplets; see src/index.css :root / .dark).
        // `fg` is the overlay base: white in dark mode, slate-900 in light.
        night: "rgb(var(--bg) / <alpha-value>)",
        "night-soft": "rgb(var(--bg-soft) / <alpha-value>)",
        fg: "rgb(var(--fg) / <alpha-value>)",
        "tx-1": "rgb(var(--tx-1) / <alpha-value>)",
        "tx-2": "rgb(var(--tx-2) / <alpha-value>)",
        "tx-3": "rgb(var(--tx-3) / <alpha-value>)",
        "tx-4": "rgb(var(--tx-4) / <alpha-value>)",
        "accent-tx": "rgb(var(--accent-tx) / <alpha-value>)",
        "danger-tx": "rgb(var(--danger-tx) / <alpha-value>)",
      },
      backgroundImage: {
        // Indigo, and the end stop is #5b52e8 rather than #6366f1 so a white
        // label still clears AA (5.45:1 vs 4.47:1) at the light end.
        "brand-gradient": "linear-gradient(135deg, #4f46e5 0%, #5b52e8 100%)",
      },
      boxShadow: {
        glow: "0 0 40px -12px var(--glow)",
        bubble: "0 2px 12px -4px var(--shadow-bubble)",
      },
      keyframes: {
        rise: {
          from: { opacity: "0", transform: "translateY(4px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
      },
      animation: { rise: "rise 0.18s ease-out" },
    },
  },
  plugins: [],
};
