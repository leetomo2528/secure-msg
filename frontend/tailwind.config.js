/** Tailwind + base styles. */
/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: "#0f172a",
        panel: "#1e293b",
        accent: "#22d3ee",
      },
    },
  },
  plugins: [],
};
