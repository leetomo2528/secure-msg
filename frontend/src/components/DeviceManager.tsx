import { useCallback, useEffect, useMemo, useState } from "react";
import {
  api,
  type AccountDevice,
  type DeviceDirectoryResult,
} from "../net/api";
import {
  accountSafetyNumber,
  deviceFingerprint,
  signDeviceApproval,
  signDeviceRevoke,
  signLegacyUpgrade,
  verifyDirectoryProof,
} from "../crypto/deviceTrust";
import { pinTrustedDirectory, TrustViolationError } from "../store/db";
import { useStore } from "../store/useStore";
import { CollapsibleCard } from "./ui";

function securityError(error: unknown): string {
  if (error instanceof TrustViolationError) return `보안 경고: ${error.message} 키 사용을 중단했습니다.`;
  return error instanceof Error ? error.message : "기기 목록을 검증하지 못했습니다.";
}

export default function DeviceManager() {
  const mySid = useStore((s) => s.sid);
  const uid = useStore((s) => s.uid);
  const keypair = useStore((s) => s.keypair);
  const [open, setOpen] = useState(false);
  const [directory, setDirectory] = useState<DeviceDirectoryResult | null>(null);
  const [busySid, setBusySid] = useState<string | null>(null);
  const [securityWarning, setSecurityWarning] = useState<string | null>(null);

  const devices = directory?.devices ?? [];
  const pending = devices.filter((device) => device.trust_state === "pending");

  const load = useCallback(async () => {
    try {
      const [result, keyDirectory] = await Promise.all([api.listDevices(), api.keyDirectory()]);
      if (!result.ok || !result.devices) {
        useStore.setState({ error: result.error ?? "기기 목록을 불러오지 못했습니다." });
        return;
      }

      // Older servers may omit trust metadata. Keep device management usable,
      // but pin only complete authenticated directory snapshots.
      if (uid != null && keyDirectory.ok && keyDirectory.identity_sig_pub && keyDirectory.directory_hash
        && Number.isSafeInteger(keyDirectory.security_epoch) && keyDirectory.devices
        && keyDirectory.device_history && keyDirectory.approval_certificates
        && keyDirectory.revocation_certificates && keyDirectory.security_upgrade_certificates
        && (keyDirectory.security_mode === "legacy_v1" || keyDirectory.security_mode === "verified_v2")) {
        const approved = keyDirectory.devices.filter((device) => device.pub_key && device.sig_pub);
        verifyDirectoryProof({
          user_id: uid,
          identity_sig_pub: keyDirectory.identity_sig_pub,
          security_epoch: keyDirectory.security_epoch!,
          directory_hash: keyDirectory.directory_hash,
          trust_enforced_at: keyDirectory.trust_enforced_at,
          security_mode: keyDirectory.security_mode,
          device_history: keyDirectory.device_history,
          approval_certificates: keyDirectory.approval_certificates,
          revocation_certificates: keyDirectory.revocation_certificates,
          security_upgrade_certificates: keyDirectory.security_upgrade_certificates,
        }, approved);
        await pinTrustedDirectory({
          uid,
          identity_sig_pub: keyDirectory.identity_sig_pub,
          security_epoch: keyDirectory.security_epoch!,
          directory_hash: keyDirectory.directory_hash,
          security_mode: keyDirectory.security_mode!,
          devices: approved.map((device) => ({
            sid: device.sid,
            pub_key: device.pub_key,
            sig_pub: device.sig_pub,
            kind: device.kind,
            fingerprint: deviceFingerprint(device.pub_key, device.sig_pub).hash,
          })),
        });
      }
      setSecurityWarning(null);
      setDirectory({
        ...result,
        security_epoch: keyDirectory.security_epoch,
        directory_hash: keyDirectory.directory_hash,
        identity_sig_pub: keyDirectory.identity_sig_pub,
        security_mode: keyDirectory.security_mode,
      });
    } catch (error) {
      const message = securityError(error);
      setSecurityWarning(message);
      useStore.setState({ error: message });
    }
  }, [uid]);

  useEffect(() => {
    if (open) void load();
  }, [open, load]);

  useEffect(() => {
    const onPending = () => {
      void load();
      setOpen(true);
    };
    window.addEventListener("securemsg:device-pending", onPending);
    return () => window.removeEventListener("securemsg:device-pending", onPending);
  }, [load]);

  const safety = useMemo(() => {
    if (!directory?.identity_sig_pub) return null;
    try {
      return accountSafetyNumber(directory.identity_sig_pub, uid ?? undefined);
    } catch {
      return null;
    }
  }, [directory?.identity_sig_pub, uid]);

  const revoke = async (device: AccountDevice) => {
    if (device.sid === mySid && !confirm("이 기기를 폐기하면 즉시 로그아웃됩니다. 계속할까요?")) return;
    const parentEpoch = directory?.security_epoch;
    if (!keypair || uid == null || !mySid || parentEpoch == null) {
      useStore.setState({ error: "폐기 서명을 만들 현재 기기 키 또는 보안 epoch가 없습니다." });
      return;
    }
    setBusySid(device.sid);
    try {
      const signature = signDeviceRevoke({
        uid,
        subjectSid: device.sid,
        subjectPubKey: device.pub_key,
        subjectSigPub: device.sig_pub,
        actorSid: mySid,
        parentEpoch,
      }, keypair.sign.sk);
      const result = await api.deviceRevoke(device.sid, parentEpoch, signature);
      if (!result.ok) {
        useStore.setState({ error: result.error ?? "기기를 폐기하지 못했습니다." });
        return;
      }
      if (device.sid === mySid) await useStore.getState().forgetLocalDevice();
      else await load();
    } catch (error) {
      useStore.setState({ error: securityError(error) });
    } finally {
      setBusySid(null);
    }
  };

  const approve = async (device: AccountDevice) => {
    const parentEpoch = directory?.security_epoch;
    if (!keypair || uid == null || !device.challenge || parentEpoch == null) {
      useStore.setState({ error: "승인 challenge 또는 현재 기기 서명 키가 없습니다." });
      return;
    }
    setBusySid(device.sid);
    try {
      const signature = signDeviceApproval({
        uid,
        subjectSid: device.sid,
        pubKey: device.pub_key,
        sigPub: device.sig_pub,
        kind: device.kind,
        challenge: device.challenge,
        parentEpoch,
      }, keypair.sign.sk);
      const result = await api.deviceApprove(device.sid, device.challenge, parentEpoch, signature);
      if (!result.ok) {
        useStore.setState({ error: result.error ?? "새 기기를 승인하지 못했습니다." });
        return;
      }
      await load();
    } catch (error) {
      useStore.setState({ error: securityError(error) });
    } finally {
      setBusySid(null);
    }
  };

  const reject = async (device: AccountDevice) => {
    const parentEpoch = directory?.security_epoch;
    if (!device.challenge || parentEpoch == null) {
      useStore.setState({ error: "pending challenge 또는 보안 epoch가 없습니다." });
      return;
    }
    setBusySid(device.sid);
    try {
      const result = await api.deviceRejectPending(device.sid, device.challenge, parentEpoch);
      if (!result.ok) {
        useStore.setState({ error: result.error ?? "새 기기 승인 요청을 거부하지 못했습니다." });
        return;
      }
      await load();
    } catch (error) {
      useStore.setState({ error: securityError(error) });
    } finally {
      setBusySid(null);
    }
  };

  const upgradeSecurity = async () => {
    const parentEpoch = directory?.security_epoch;
    if (!keypair || uid == null || !mySid || parentEpoch == null
      || directory?.identity_sig_pub !== keypair.sign.pk) {
      useStore.setState({ error: "레거시 계정의 최초 identity 기기에서만 보안 업그레이드를 시작할 수 있습니다." });
      return;
    }
    if (!confirm("이 기기를 계정 신원 루트로 고정합니다. 다른 레거시 기기는 다시 승인을 받아야 합니다. 계속할까요?")) return;
    setBusySid(mySid);
    try {
      const signature = signLegacyUpgrade({
        uid, identitySid: mySid, identitySigPub: keypair.sign.pk, parentEpoch,
      }, keypair.sign.sk);
      const result = await api.securityUpgrade(parentEpoch, signature);
      if (!result.ok) {
        useStore.setState({ error: result.error ?? "검증된 보안 모드로 업그레이드하지 못했습니다." });
        return;
      }
      await load();
    } catch (error) {
      useStore.setState({ error: securityError(error) });
    } finally {
      setBusySid(null);
    }
  };

  return (
    <CollapsibleCard
      open={open}
      onToggle={() => setOpen((value) => !value)}
      icon={<span aria-hidden>🔐</span>}
      title="기기 및 암호화 검증"
      badge={pending.length > 0 ? pending.length : (open ? devices.length : undefined)}
    >
      {pending.length > 0 && (
        <div role="alert" className="rounded-lg border border-amber-400/40 bg-amber-500/10 p-2.5 text-[11px] text-amber-200">
          새 기기 {pending.length}대가 승인을 기다립니다. 본인이 추가한 기기가 아니라면 거부하고 비밀번호를 변경하세요.
        </div>
      )}
      {directory?.security_mode === "legacy_v1" && (
        <div role="alert" className="rounded-lg border border-amber-400/40 bg-amber-500/10 p-2.5 text-[11px] text-amber-200">
          <div className="font-semibold">레거시 기기 — 암호학적으로 검증되지 않음</div>
          <p className="mt-1 leading-relaxed">기존 기기는 마이그레이션을 위해 TOFU로 유지됐습니다. 승인 체인을 만들기 전까지 ‘검증됨’으로 표시하지 않습니다.</p>
          {directory.identity_sig_pub === keypair?.sign.pk ? (
            <button type="button" onClick={() => void upgradeSecurity()} disabled={busySid != null} className="mt-2 rounded-md bg-amber-400/15 px-2 py-1 text-[10px] font-semibold disabled:opacity-40">
              검증된 v2 보안으로 업그레이드
            </button>
          ) : (
            <p className="mt-2 text-[10px]">최초 identity 기기에서 업그레이드하세요.</p>
          )}
        </div>
      )}
      {securityWarning && (
        <div role="alert" className="rounded-lg border border-red-400/50 bg-red-500/10 p-2.5 text-[11px] text-danger-tx">
          {securityWarning}
        </div>
      )}
      {safety && (
        <div className="rounded-lg bg-fg/[0.04] p-2.5">
          <div className="text-[10px] font-semibold text-tx-3">계정 안전 번호</div>
          <code className="mt-1 block break-words text-[10px] leading-relaxed text-accent-tx">{safety.display}</code>
          <button
            type="button"
            onClick={() => void navigator.clipboard.writeText(safety.qrPayload)}
            className="mt-1 text-[9px] text-tx-4 underline"
          >
            QR 검증 payload 복사
          </button>
        </div>
      )}
      <p className="text-[10px] leading-relaxed text-tx-4">
        공개키 지문을 직접 비교하거나 안전 번호 payload를 QR로 비교하세요. 이미 확인한 기기의 키가 바뀌면 자동으로 덮어쓰지 않습니다.
      </p>
      <ul className="space-y-1">
        {devices.map((device) => {
          let fingerprint: string | null = null;
          try { fingerprint = deviceFingerprint(device.pub_key, device.sig_pub).display; } catch { /* malformed server key */ }
          const isPending = device.trust_state === "pending";
          return (
            <li key={device.sid} className={`rounded-lg px-2.5 py-2 text-xs ${isPending ? "bg-amber-500/10 ring-1 ring-amber-400/30" : "bg-fg/[0.04]"}`}>
              <div className="flex items-center justify-between gap-2">
                <div className="min-w-0">
                  <div className="truncate text-tx-2">
                    {device.kind === "android_gateway" ? "📱 " : "🌐 "}{device.name}{" "}
                    {device.sid === mySid && <span className="text-accent-tx">(현재 기기)</span>}
                    {isPending && <span className="ml-1 text-amber-300">승인 대기</span>}
                  </div>
                  <div className="text-[9px] text-tx-4">{new Date(device.last_seen * 1000).toLocaleString("ko-KR")}</div>
                </div>
                <div className="flex shrink-0 gap-1">
                  {isPending ? (
                    <>
                      <button onClick={() => void approve(device)} disabled={busySid != null} className="rounded-md bg-emerald-500/15 px-2 py-1 text-[10px] text-emerald-300 disabled:opacity-40">승인</button>
                      <button onClick={() => void reject(device)} disabled={busySid != null} className="rounded-md bg-red-500/10 px-2 py-1 text-[10px] text-danger-tx disabled:opacity-40">거부</button>
                    </>
                  ) : (
                    <button onClick={() => void revoke(device)} disabled={busySid != null} className="rounded-md px-2 py-1 text-[10px] text-danger-tx/80 ring-1 ring-red-400/30 disabled:opacity-40">폐기</button>
                  )}
                </div>
              </div>
              {fingerprint && <code className="mt-1 block break-all text-[8px] leading-relaxed text-tx-4">{fingerprint}</code>}
            </li>
          );
        })}
      </ul>
    </CollapsibleCard>
  );
}
