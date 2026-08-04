/** Tailwind + base styles. */
/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        night: "#0a0f16",
        "night-soft": "#0d1420",
        card: "#111a28",
        "card-raised": "#16202f",
        line: "#1d2939",
      },
      backgroundImage: {
        "brand-gradient": "linear-gradient(135deg, #2dd4bf 0%, #38bdf8 100%)",
      },
      boxShadow: {
        glow: "0 0 40px -12px rgba(45, 212, 191, 0.45)",
        bubble: "0 2px 12px -4px rgba(0, 0, 0, 0.5)",
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
