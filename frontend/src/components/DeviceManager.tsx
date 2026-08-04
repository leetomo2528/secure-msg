import { useEffect, useState } from "react";
import { api } from "../net/api";
import { useStore } from "../store/useStore";
import { CollapsibleCard } from "./ui";

interface DeviceRow {
  sid: string;
  name: string;
  kind: "web" | "android_gateway";
  created_at: number;
  last_seen: number;
}

export default function DeviceManager() {
  const { sid: mySid } = useStore();
  const [open, setOpen] = useState(false);
  const [devices, setDevices] = useState<DeviceRow[]>([]);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    try {
      const r = await api.listDevices();
      if (r.ok && r.devices) {
        setDevices(r.devices);
      } else {
        useStore.setState({ error: r.error ?? "기기 목록을 불러오지 못했습니다." });
      }
    } catch (error) {
      useStore.setState({
        error: error instanceof Error ? error.message : "기기 목록을 불러오지 못했습니다.",
      });
    }
  };

  useEffect(() => {
    if (open) load();
  }, [open]);

  const revoke = async (sid: string) => {
    if (sid === mySid) {
      if (!confirm("이 기기는 자기 자신을 폐기하면 즉시 로그아웃됩니다. 계속할까요?")) return;
    }
    setBusy(true);
    try {
      const result = await api.deviceRevoke(sid);
      if (!result.ok) {
        useStore.setState({ error: result.error ?? "기기를 폐기하지 못했습니다." });
        return;
      }
      await load();
      if (sid === mySid) {
        await useStore.getState().forgetLocalDevice();
      }
    } catch (error) {
      useStore.setState({
        error: error instanceof Error ? error.message : "기기를 폐기하지 못했습니다.",
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <CollapsibleCard
      open={open}
      onToggle={() => setOpen((v) => !v)}
      icon={(
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden>
          <rect x="7" y="2.5" width="10" height="19" rx="2.5" stroke="currentColor" strokeWidth="1.8" />
          <path d="M10.5 18.5h3" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
      )}
      title="기기 관리"
      badge={open ? devices.length : undefined}
    >
      <p className="text-[10px] leading-relaxed text-slate-500">
        각 기기는 고유 키쌍을 가집니다. 다른 기기에서 같은 아이디와 비밀번호로 로그인하면 새 기기가 자동으로 추가됩니다.
        폐기된 기기의 키로는 새 메시지를 복호화할 수 없습니다.
      </p>
      <ul className="space-y-1">
        {devices.map((d) => (
          <li key={d.sid} className="flex items-center justify-between rounded-lg bg-white/[0.04] px-2.5 py-2 text-xs">
            <div className="min-w-0">
              <div className="truncate text-slate-200">
                {d.kind === "android_gateway" ? "📱 " : "🌐 "}{d.name}{" "}
                {d.sid === mySid && <span className="text-teal-300">(현재 기기)</span>}
              </div>
              <div className="text-[9px] text-slate-500">
                {new Date(d.last_seen * 1000).toLocaleString("ko-KR")}
              </div>
            </div>
            <button
              onClick={() => revoke(d.sid)}
              disabled={busy}
              className="ml-2 rounded-md px-2 py-1 text-[10px] text-red-300/80 ring-1 ring-red-400/30 transition hover:bg-red-500/10 hover:text-red-300 disabled:opacity-40"
            >
              폐기
            </button>
          </li>
        ))}
      </ul>
    </CollapsibleCard>
  );
}
