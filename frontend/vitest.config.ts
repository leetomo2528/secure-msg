import { defineConfig } from "vitest/config";
import { fileURLToPath } from "node:url";

// Password hashing needs the sumo build (plain libsodium-wrappers ships
// WITHOUT crypto_pwhash). The sumo 0.7.x ESM bundle also references a missing
// sibling `libsodium-sumo.mjs`; we bypass the package `exports` map and point
// straight at the CJS bundle, which inlines the wasm and works under Node.
const SODIUM_CJS = fileURLToPath(
  new URL("./node_modules/libsodium-wrappers-sumo/dist/modules-sumo/libsodium-wrappers.js", import.meta.url),
);

export default defineConfig({
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
  resolve: {
    alias: {
      "libsodium-wrappers-sumo": SODIUM_CJS,
    },
  },
  ssr: { noExternal: ["libsodium-wrappers-sumo"] },
});
