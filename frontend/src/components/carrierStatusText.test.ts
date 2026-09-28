import { describe, expect, it } from "vitest";
import { carrierFailureReason, carrierLabel } from "./carrierStatusText";

const STALE_ERROR = "오래된 요청이라 보내지 않았습니다(접수 후 1시간 초과). 필요하면 다시 보내세요.";

describe("carrier status text", () => {
  it("keeps every existing carrier status label and passes unknown statuses through", () => {
    const labels: Record<string, string> = {
      queued: "대기",
      dispatched: "발송 요청",
      sent: "통신사 접수",
      delivered: "전달됨",
      failed: "발송 실패",
      delivery_failed: "전달 실패",
      unknown: "상태 확인 중",
    };
    for (const [status, label] of Object.entries(labels)) {
      expect(carrierLabel(status)).toBe(label);
    }
    expect(carrierLabel("future_status")).toBe("future_status");
  });

  it("shows the exact stale request reason for failed and delivery_failed", () => {
    for (const status of ["failed", "delivery_failed"]) {
      expect(carrierFailureReason(status, STALE_ERROR)).toBe(STALE_ERROR);
    }
  });

  it("hides an error on every non-failure status", () => {
    for (const status of ["queued", "dispatched", "sent", "delivered", "none", "unknown", "future_status", null, undefined]) {
      expect(carrierFailureReason(status, STALE_ERROR)).toBeNull();
    }
  });

  it("hides missing or blank failure errors", () => {
    for (const status of ["failed", "delivery_failed"]) {
      for (const error of [null, undefined, "", " \t\n "]) {
        expect(carrierFailureReason(status, error)).toBeNull();
      }
    }
  });

  it("trims surrounding whitespace without changing the failure reason", () => {
    expect(carrierFailureReason("failed", ` \n${STALE_ERROR}\t `)).toBe(STALE_ERROR);
  });

  it("caps long text at 300 Unicode characters", () => {
    expect(carrierFailureReason("failed", "가".repeat(301))).toBe("가".repeat(300));
    expect(carrierFailureReason("delivery_failed", "😀".repeat(301))).toBe("😀".repeat(300));
  });
});
