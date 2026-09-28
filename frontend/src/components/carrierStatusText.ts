export function carrierLabel(status: string): string {
  return ({
    queued: "대기",
    dispatched: "발송 요청",
    sent: "통신사 접수",
    delivered: "전달됨",
    failed: "발송 실패",
    delivery_failed: "전달 실패",
    unknown: "상태 확인 중",
  } as Record<string, string>)[status] ?? status;
}

export function carrierFailureReason(
  status: string | null | undefined,
  error: string | null | undefined,
): string | null {
  if (status !== "failed" && status !== "delivery_failed") return null;
  const trimmed = error?.trim();
  if (!trimmed) return null;
  return Array.from(trimmed).slice(0, 300).join("");
}
