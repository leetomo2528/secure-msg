import { describe, expect, it } from "vitest";
import { canAdvanceCarrierStatus } from "./db";

describe("carrier status ordering", () => {
  it("accepts normal forward progress", () => {
    expect(canAdvanceCarrierStatus("queued", "dispatched")).toBe(true);
    expect(canAdvanceCarrierStatus("dispatched", "sent")).toBe(true);
    expect(canAdvanceCarrierStatus("sent", "delivered")).toBe(true);
    expect(canAdvanceCarrierStatus("sent", "delivery_failed")).toBe(true);
  });

  it("rejects delayed regressions and terminal rewrites", () => {
    expect(canAdvanceCarrierStatus("sent", "dispatched")).toBe(false);
    expect(canAdvanceCarrierStatus("delivered", "sent")).toBe(false);
    expect(canAdvanceCarrierStatus("failed", "sent")).toBe(false);
    expect(canAdvanceCarrierStatus("delivery_failed", "delivered")).toBe(false);
  });

  it("rejects unknown state names", () => {
    expect(canAdvanceCarrierStatus("sent", "mystery")).toBe(false);
  });
});
