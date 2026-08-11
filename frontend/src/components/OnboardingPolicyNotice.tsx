import { ACCOUNT_RECOVERY_WARNING, NEW_DEVICE_HISTORY_WARNING } from "./onboardingPolicy";

export default function OnboardingPolicyNotice() {
  return (
    <aside
      aria-label="계정 복구 및 새 기기 제한"
      className="space-y-2 rounded-2xl border border-amber-300/20 bg-amber-300/[0.07] px-3.5 py-3.5 text-[11px] leading-relaxed text-white/65"
    >
      <p><strong className="font-semibold text-amber-100">복구 불가</strong><span className="mx-1.5 text-amber-200/40">·</span>{ACCOUNT_RECOVERY_WARNING}</p>
      <p><strong className="font-semibold text-amber-100">새 기기 기록 제한</strong><span className="mx-1.5 text-amber-200/40">·</span>{NEW_DEVICE_HISTORY_WARNING}</p>
    </aside>
  );
}
