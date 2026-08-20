import { useEffect, useMemo, useState } from "react";
import QRCode from "qrcode";
import { deviceFingerprint } from "../crypto/deviceTrust";
import {
  encodePairingQr,
  pairingSafetyNumber,
  randomPairingNonce,
} from "../crypto/pairing";
import { useStore } from "../store/useStore";
import BrandMark from "./BrandMark";
import { api } from "../net/api";

/** How long the QR advertises itself as usable. Informational only — the
 * server's own pairing session carries the authoritative 120s TTL. */
const QR_LIFETIME_SECONDS = 600;

/**
 * The nonce must survive a reload: an approver may already have scanned the
 * old QR and be looking at a safety number derived from it. Regenerating on
 * every mount would show the two screens different numbers and read as an
 * attack rather than a refresh.
 */
function persistentNonce(sid: string): string {
  const key = `securemsg-pairing-nonce:${sid}`;
  try {
    const stored = localStorage.getItem(key);
    if (stored && /^[A-Za-z0-9_-]{43}$/.test(stored)) return stored;
  } catch { /* private mode: fall through to a session-only nonce */ }
  const fresh = randomPairingNonce();
  try {
    localStorage.setItem(key, fresh);
  } catch { /* not persisted; a reload will show a new QR */ }
  return fresh;
}

export default function PendingDeviceApproval() {
  const keypair = useStore((s) => s.keypair);
  const sid = useStore((s) => s.sid);
  const username = useStore((s) => s.username);
  const pendingChallenge = useStore((s) => s.pendingChallenge);
  const pendingPairing = useStore((s) => s.pendingPairing);
  const refreshPendingApproval = useStore((s) => s.refreshPendingApproval);
  const forgetLocalDevice = useStore((s) => s.forgetLocalDevice);
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [showDetails, setShowDetails] = useState(false);

  const fingerprint = useMemo(() => {
    if (!keypair) return null;
    try { return deviceFingerprint(keypair.box.pk, keypair.sign.pk); } catch { return null; }
  }, [keypair]);

  const nonceNew = useMemo(() => (sid ? persistentNonce(sid) : null), [sid]);

  const qrPayload = useMemo(() => {
    if (!sid || !keypair || !username || !pendingChallenge || !nonceNew) return null;
    try {
      return encodePairingQr({
        v: 1,
        type: "securemsg-pairing",
        server: window.location.origin,
        username,
        sid,
        challenge: pendingChallenge,
        box_pk: keypair.box.pk,
        sig_pk: keypair.sign.pk,
        nonce_new: nonceNew,
        expires_at: Math.floor(Date.now() / 1000) + QR_LIFETIME_SECONDS,
      });
    } catch {
      return null;
    }
  }, [sid, keypair, username, pendingChallenge, nonceNew]);

  const safetyNumber = useMemo(() => {
    if (!pendingPairing || !nonceNew || !sid || !keypair) return null;
    try {
      return pairingSafetyNumber({
        nonceNew,
        nonceApprover: pendingPairing.nonceApprover,
        sid,
        pubKey: keypair.box.pk,
        sigPub: keypair.sign.pk,
      });
    } catch {
      return null;
    }
  }, [pendingPairing, nonceNew, sid, keypair]);

  useEffect(() => {
    if (!qrPayload) {
      setQrDataUrl(null);
      return;
    }
    let cancelled = false;
    void QRCode.toDataURL(qrPayload, {
      errorCorrectionLevel: "M",
      margin: 1,
      scale: 6,
      color: { dark: "#0f172a", light: "#ffffff" },
    }).then((url) => {
      if (!cancelled) setQrDataUrl(url);
    }).catch(() => {
      if (!cancelled) setQrDataUrl(null);
    });
    return () => { cancelled = true; };
  }, [qrPayload]);

  const cancel = async () => {
    const result = await api.pendingDeviceRevoke();
    if (!result.ok) {
      useStore.setState({ error: result.error ?? "승인 요청을 취소하지 못했습니다." });
      return;
    }
    try {
      if (sid) localStorage.removeItem(`securemsg-pairing-nonce:${sid}`);
    } catch { /* nothing to clean up */ }
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
    <div className="onboarding-shell grid min-h-full place-items-center overflow-y-auto px-5 py-10">
      <div className="flex w-full max-w-[400px] flex-col items-center gap-6 animate-rise">
        <div className="flex items-center gap-2.5">
          <BrandMark className="h-8 w-8 rounded-[11px]" />
          <p className="text-base font-bold tracking-tight text-tx-1">SecureMsg</p>
        </div>

        <div className="onboarding-card w-full space-y-5 text-center">
          {safetyNumber ? (
            <>
              <div>
                <h1 className="text-lg font-semibold text-tx-1">두 화면의 숫자가 같습니까?</h1>
                <p className="mt-1.5 text-xs leading-relaxed text-tx-3">
                  기존 기기에도 같은 숫자가 떠 있어야 합니다. 다르면 승인하지 말고 취소하세요.
                </p>
              </div>
              <p className="rounded-xl bg-fg/[0.04] px-3 py-4 font-mono text-lg font-semibold tracking-[0.08em] text-accent-tx">
                {safetyNumber}
              </p>
              <p className="text-[11px] text-tx-4">기존 기기에서 확인을 누르면 연결됩니다.</p>
            </>
          ) : (
            <>
              <div>
                <h1 className="text-lg font-semibold text-tx-1">기존 기기로 스캔하세요</h1>
                <p className="mt-1.5 text-xs leading-relaxed text-tx-3">
                  이미 쓰고 있는 SecureMsg 기기에서 <strong className="font-semibold text-tx-2">기기 및 암호화 검증 → QR 스캔</strong>을 열어 이 코드를 비추세요.
                </p>
              </div>
              {qrDataUrl ? (
                <img
                  src={qrDataUrl}
                  alt="기기 페어링 QR 코드"
                  className="mx-auto h-52 w-52 rounded-xl bg-white p-2"
                />
              ) : (
                <div className="mx-auto grid h-52 w-52 place-items-center rounded-xl bg-fg/[0.04] text-[11px] text-tx-4">
                  QR 준비 중…
                </div>
              )}
              <p className="flex items-center justify-center gap-1.5 text-[11px] text-tx-4">
                <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-amber-400" />
                스캔을 기다리는 중
              </p>
            </>
          )}

          <div className="space-y-2 border-t border-fg/10 pt-4">
            <button
              type="button"
              onClick={() => setShowDetails((value) => !value)}
              aria-expanded={showDetails}
              className="text-[11px] text-tx-4 transition hover:text-tx-2"
            >
              {showDetails ? "직접 확인 정보 숨기기" : "QR을 못 쓰나요? 직접 확인하기"}
            </button>
            {showDetails && (
              <div className="space-y-2 rounded-xl bg-fg/[0.04] p-3 text-left animate-rise">
                <p className="text-[11px] leading-relaxed text-tx-3">
                  기존 기기의 기기 목록에서 아래 지문이 같은지 확인하고 승인하세요.
                </p>
                <div className="text-[10px] text-tx-4">기기 SID</div>
                <code className="block break-all text-[10px] text-tx-2">{sid}</code>
                {fingerprint && (
                  <>
                    <div className="text-[10px] text-tx-4">공개키 지문</div>
                    <code className="block break-all text-[9px] leading-relaxed text-accent-tx">
                      {fingerprint.display}
                    </code>
                  </>
                )}
              </div>
            )}
          </div>
        </div>

        <button
          type="button"
          onClick={() => void cancel()}
          className="text-[11px] text-tx-4 underline underline-offset-2 transition hover:text-danger-tx"
        >
          승인 요청 취소 및 이 기기 키 삭제
        </button>
      </div>
    </div>
  );
}
