import { useEffect, useMemo } from "react";
import { deviceFingerprint } from "../crypto/deviceTrust";
import { useStore } from "../store/useStore";
import BrandMark from "./BrandMark";
import { api } from "../net/api";

export default function PendingDeviceApproval() {
  const keypair = useStore((s) => s.keypair);
  const sid = useStore((s) => s.sid);
  const refreshPendingApproval = useStore((s) => s.refreshPendingApproval);
  const forgetLocalDevice = useStore((s) => s.forgetLocalDevice);
  const fingerprint = useMemo(() => {
    if (!keypair) return null;
    try { return deviceFingerprint(keypair.box.pk, keypair.sign.pk); } catch { return null; }
  }, [keypair]);

  const cancel = async () => {
    const result = await api.pendingDeviceRevoke();
    if (!result.ok) {
      useStore.setState({ error: result.error ?? "승인 요청을 취소하지 못했습니다." });
      return;
    }
    await forgetLocalDevice();
  };

  useEffect(() => {
    let stopped = false;
    const poll = async () => {
      const state = await refreshPendingApproval();
      if (!stopped && state === "revoked") await forgetLocalDevice();
    };
    void poll();
    const timer = window.setInterval(() => void poll(), 2_000);
    return () => { stopped = true; window.clearInterval(timer); };
  }, [forgetLocalDevice, refreshPendingApproval]);

  return (
    <div className="grid h-full place-items-center px-6">
      <div className="w-full max-w-md space-y-5 rounded-2xl bg-night-soft p-6 text-center ring-1 ring-fg/10">
        <div className="flex justify-center"><BrandMark className="h-14 w-14 rounded-2xl" /></div>
        <div>
          <h1 className="text-lg font-bold text-tx-1">기기 승인 대기 중</h1>
          <p className="mt-2 text-xs leading-relaxed text-tx-3">
            이미 승인된 SecureMsg 기기의 ‘기기 및 암호화 검증’에서 이 기기를 승인하세요.
            승인 전에는 대화·메시지 키에 접근할 수 없습니다.
          </p>
          <p className="mt-2 rounded-xl bg-accent-tx/10 px-3 py-2.5 text-left text-[11px] leading-relaxed text-accent-tx ring-1 ring-accent-tx/20">
            Android에서는 <strong>메시지 → 차단·설정 → 기기 보안</strong>으로 이동하면 승인 요청이 표시됩니다.
          </p>
        </div>
        <div className="rounded-xl bg-fg/[0.04] p-3 text-left">
          <div className="text-[10px] text-tx-4">기기 SID</div>
          <code className="block break-all text-[10px] text-tx-2">{sid}</code>
          {fingerprint && (
            <>
              <div className="mt-2 text-[10px] text-tx-4">공개키 지문</div>
              <code className="block break-all text-[9px] leading-relaxed text-accent-tx">{fingerprint.display}</code>
            </>
          )}
        </div>
        <div className="flex items-center justify-center gap-2 text-[10px] text-tx-4">
          <span className="h-2 w-2 animate-pulse rounded-full bg-amber-400" />2초마다 승인 상태 확인 중
        </div>
        <button
          type="button"
          onClick={() => void cancel()}
          className="text-[10px] text-danger-tx/80 underline"
        >
          서버 승인 요청 취소 및 이 기기 키 삭제
        </button>
      </div>
    </div>
  );
}
