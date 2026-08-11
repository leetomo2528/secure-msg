import { useStore } from "./store/useStore";
import { useSyncExternalStore } from "react";
import Onboarding from "./components/Onboarding";
import ChatList from "./components/ChatList";
import ChatView from "./components/ChatView";
import BlocklistEditor from "./components/BlocklistEditor";
import DeviceManager from "./components/DeviceManager";
import NewConversationModal from "./components/NewConversationModal";
import BrandMark from "./components/BrandMark";
import NotifyToggle from "./components/NotifyToggle";
import PendingDeviceApproval from "./components/PendingDeviceApproval";
import { getThemeMode, setThemeMode, subscribeTheme, type ThemeMode } from "./theme";

export default function App() {
  const { ready, authed, approvalPending, securityLocked, activeCid, error } = useStore();

  if (!ready) {
    return (
      <div className="grid h-full place-items-center">
        <div className="flex flex-col items-center gap-4 animate-rise">
          <BrandMark className="h-12 w-12 rounded-2xl" />
          <p className="text-sm text-tx-3">초기화 중…</p>
        </div>
      </div>
    );
  }
  if (!authed) return <Onboarding />;
  if (approvalPending) return <PendingDeviceApproval />;
  if (securityLocked) return <SecurityLocked />;

  return (
    <div className="relative grid h-full grid-cols-1 md:grid-cols-[340px_1fr] bg-night">
      {error && (
        <div className="absolute top-3 left-1/2 z-50 flex max-w-[92%] -translate-x-1/2 items-center gap-2 rounded-xl bg-red-500/10 px-4 py-2.5 text-xs text-danger-tx shadow-bubble ring-1 ring-red-400/30 backdrop-blur animate-rise">
          <span>{error}</span>
          <button
            type="button"
            onClick={() => useStore.setState({ error: null })}
            className="ml-1 text-danger-tx/70 transition hover:text-danger-tx"
            aria-label="오류 닫기"
          >×</button>
        </div>
      )}
      <aside
        className={`${activeCid ? "hidden md:flex" : "flex"} flex-col overflow-y-auto bg-night-soft md:border-r md:border-fg/5`}
        style={{ paddingTop: "var(--safe-top)" }}
      >
        <div className="flex items-center justify-between px-4 pb-3 pt-4">
          <div className="flex items-center gap-2.5">
            <BrandMark />
            <div>
              <h1 className="text-[15px] font-bold leading-none tracking-tight text-tx-1">Secure Msg</h1>
              <p className="mt-1 text-[10px] leading-none text-tx-4">E2E 암호화 문자</p>
            </div>
          </div>
          <div className="flex items-center">
            <ThemeButton />
            <LogoutButton />
          </div>
        </div>
        <ChatList />
        <div className="mt-auto space-y-2 border-t border-fg/5 p-3">
          <NewConversationModal />
          <NotifyToggle />
          <BlocklistEditor />
          <DeviceManager />
        </div>
      </aside>
      <main
        className={`${activeCid ? "block" : "hidden md:block"} overflow-hidden min-h-0`}
        style={{ paddingTop: "var(--safe-top)", paddingBottom: "var(--safe-bottom)" }}
      >
        {activeCid ? <ChatView cid={activeCid} /> : <EmptyChat />}
      </main>
    </div>
  );
}

function SecurityLocked() {
  const logout = useStore((state) => state.logout);
  const error = useStore((state) => state.error);
  return (
    <div className="grid h-full place-items-center px-6">
      <div role="alert" className="w-full max-w-md space-y-4 rounded-2xl border border-red-400/40 bg-red-500/10 p-6 text-center">
        <div className="text-3xl" aria-hidden>🛑</div>
        <h1 className="text-lg font-bold text-danger-tx">키 디렉터리 보안 잠금</h1>
        <p className="text-xs leading-relaxed text-tx-2">{error ?? "고정된 기기 공개키와 서버 응답이 일치하지 않습니다."}</p>
        <p className="text-[10px] leading-relaxed text-tx-4">
          공격 또는 서버 복원 오류일 수 있습니다. 기존 신뢰 기록을 자동으로 덮어쓰지 않았습니다.
        </p>
        <button type="button" onClick={() => void logout()} className="btn-primary">안전하게 로그아웃</button>
      </div>
    </div>
  );
}

function EmptyChat() {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 text-center animate-rise">
      <div className="grid h-16 w-16 place-items-center rounded-3xl bg-fg/[0.04] ring-1 ring-fg/[0.06]">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden>
          <path
            d="M4 7a3 3 0 0 1 3-3h10a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H9l-4 4v-4H7a3 3 0 0 1-3-3V7z"
            stroke="#475569" strokeWidth="1.6" strokeLinejoin="round"
          />
          <path d="M8.5 9h7M8.5 12h4" stroke="#475569" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      </div>
      <div>
        <p className="text-sm font-medium text-tx-2">대화를 선택하세요</p>
        <p className="mt-1 text-xs text-tx-4">왼쪽 목록에서 선택하거나 새 대화를 시작할 수 있어요.</p>
      </div>
    </div>
  );
}

function LogoutButton() {
  const logout = useStore((s) => s.logout);
  const username = useStore((s) => s.username);
  return (
    <div className="flex items-center">
      <span className="hidden max-w-[90px] truncate rounded-full bg-fg/[0.04] px-2.5 py-1 text-[11px] text-tx-3 ring-1 ring-fg/[0.06] sm:block">
        {username}
      </span>
      <button
        onClick={logout}
        title="로그아웃"
        aria-label="로그아웃"
        className="ml-1.5 grid h-8 w-8 place-items-center rounded-lg text-tx-4 ring-1 ring-fg/10 transition hover:bg-fg/[0.06] hover:text-tx-1"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
          <path d="M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3M14 8l4 4-4 4M18 12H9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
    </div>
  );
}

const THEME_ORDER: ThemeMode[] = ["system", "light", "dark"];
const THEME_LABEL: Record<ThemeMode, string> = { system: "시스템", light: "라이트", dark: "다크" };

function ThemeButton() {
  const mode = useSyncExternalStore(subscribeTheme, getThemeMode);
  const next = THEME_ORDER[(THEME_ORDER.indexOf(mode) + 1) % THEME_ORDER.length];
  return (
    <button
      onClick={() => setThemeMode(next)}
      title={`테마: ${THEME_LABEL[mode]} (클릭하여 ${THEME_LABEL[next]}로 변경)`}
      aria-label={`테마 변경 (현재: ${THEME_LABEL[mode]})`}
      className="grid h-8 w-8 place-items-center rounded-lg text-tx-4 ring-1 ring-fg/10 transition hover:bg-fg/[0.06] hover:text-tx-1"
    >
      {mode === "system" && (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
          <rect x="3" y="4.5" width="18" height="12.5" rx="2" stroke="currentColor" strokeWidth="1.8" />
          <path d="M9 20.5h6M12 17v3.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
      )}
      {mode === "light" && (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
          <circle cx="12" cy="12" r="4.2" stroke="currentColor" strokeWidth="1.8" />
          <path d="M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M18.5 5.5l-1.7 1.7M7.2 16.8l-1.7 1.7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
      )}
      {mode === "dark" && (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
          <path d="M20.6 14.2A8.8 8.8 0 0 1 9.8 3.4a8.8 8.8 0 1 0 10.8 10.8z" fill="currentColor" />
        </svg>
      )}
    </button>
  );
}
