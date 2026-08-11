import { ACCOUNT_RECOVERY_WARNING, NEW_DEVICE_HISTORY_WARNING } from "./onboardingPolicy";

export default function OnboardingPolicyNotice() {
  return (
    <aside
      aria-label="계정 복구 및 새 기기 제한"
      className="space-y-2 rounded-xl bg-amber-500/10 px-3.5 py-3 text-[11px] leading-relaxed text-tx-2 ring-1 ring-amber-400/30"
    >
      <p><strong className="font-semibold text-tx-1">복구 불가:</strong> {ACCOUNT_RECOVERY_WARNING}</p>
      <p><strong className="font-semibold text-tx-1">새 기기 기록 제한:</strong> {NEW_DEVICE_HISTORY_WARNING}</p>
    </aside>
  );
}
