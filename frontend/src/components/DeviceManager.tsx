import { useEffect, useState } from "react";
import { api } from "../net/api";
import { useStore } from "../store/useStore";

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
    <div className="rounded-lg border border-slate-700">
      <button
        onClick={() => setOpen((v) => !v)}
        className="w-full flex items-center justify-between px-3 py-2 text-xs text-slate-300 hover:bg-slate-800/50"
      >
        <span>기기 관리</span>
        <span>{open ? "▾" : "▸"}</span>
      </button>
      {open && (
        <div className="px-3 pb-3 space-y-2">
          <p className="text-[10px] text-slate-500 leading-relaxed">
            각 기기는 고유 키쌍을 가집니다. 다른 기기에서 같은 아이디와 비밀번호로 로그인하면 새 기기가 자동으로 추가됩니다.
            폐기된 기기의 키로는 새 메시지를 복호화할 수 없습니다.
          </p>
          <ul className="space-y-1">
            {devices.map((d) => (
              <li key={d.sid} className="flex items-center justify-between bg-slate-800/50 rounded px-2 py-1.5 text-xs">
                <div className="min-w-0">
                  <div className="truncate">
                    {d.kind === "android_gateway" ? "📱 " : "🌐 "}{d.name}{" "}
                    {d.sid === mySid && <span className="text-cyan-400">(현재 기기)</span>}
                  </div>
                  <div className="text-[9px] text-slate-500">
                    {new Date(d.last_seen * 1000).toLocaleString("ko-KR")}
                  </div>
                </div>
                <button
                  onClick={() => revoke(d.sid)}
                  disabled={busy}
                  className="text-red-400/70 hover:text-red-400 ml-2 text-[10px] border border-red-500/30 rounded px-1.5 py-0.5"
                >
                  폐기
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
