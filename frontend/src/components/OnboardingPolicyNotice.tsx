import { ACCOUNT_RECOVERY_WARNING, NEW_DEVICE_HISTORY_WARNING } from "./onboardingPolicy";

/**
 * Account limits worth knowing BEFORE creating an account. Rendered only in
 * register mode: someone signing in already has an account and cannot act on
 * any of it, so showing it on every login was noise in front of the password
 * field. Quiet body text, not an alert — nothing here is going wrong.
 */
export default function OnboardingPolicyNotice() {
  return (
    <aside
      aria-label="계정 복구 및 새 기기 제한"
      className="space-y-1.5 text-[11px] leading-relaxed text-tx-4"
    >
      <p><span className="font-medium text-tx-3">비밀번호 재설정</span> · {ACCOUNT_RECOVERY_WARNING}</p>
      <p><span className="font-medium text-tx-3">새 기기</span> · {NEW_DEVICE_HISTORY_WARNING}</p>
    </aside>
  );
}
