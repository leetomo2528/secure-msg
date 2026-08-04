import { useStore } from "./store/useStore";
import Onboarding from "./components/Onboarding";
import ChatList from "./components/ChatList";
import ChatView from "./components/ChatView";
import BlocklistEditor from "./components/BlocklistEditor";
import DeviceManager from "./components/DeviceManager";
import NewConversationModal from "./components/NewConversationModal";
import BrandMark from "./components/BrandMark";

export default function App() {
  const { ready, authed, activeCid, error } = useStore();

  if (!ready) {
    return (
      <div className="grid h-full place-items-center">
        <div className="flex flex-col items-center gap-4 animate-rise">
          <BrandMark className="h-12 w-12 rounded-2xl" />
          <p className="text-sm text-slate-400">초기화 중…</p>
        </div>
      </div>
    );
  }
  if (!authed) return <Onboarding />;

  return (
    <div className="relative grid h-full grid-cols-1 md:grid-cols-[340px_1fr] bg-night">
      {error && (
        <div className="absolute top-3 left-1/2 z-50 flex max-w-[92%] -translate-x-1/2 items-center gap-2 rounded-xl bg-red-500/10 px-4 py-2.5 text-xs text-red-200 shadow-bubble ring-1 ring-red-400/30 backdrop-blur animate-rise">
          <span>{error}</span>
          <button
            type="button"
            onClick={() => useStore.setState({ error: null })}
            className="ml-1 text-red-300/70 transition hover:text-red-100"
            aria-label="오류 닫기"
          >×</button>
        </div>
      )}
      <aside
        className={`${activeCid ? "hidden md:flex" : "flex"} flex-col overflow-y-auto bg-night-soft md:border-r md:border-white/5`}
        style={{ paddingTop: "var(--safe-top)" }}
      >
        <div className="flex items-center justify-between px-4 pb-3 pt-4">
          <div className="flex items-center gap-2.5">
            <BrandMark />
            <div>
              <h1 className="text-[15px] font-bold leading-none tracking-tight text-slate-100">Secure Msg</h1>
              <p className="mt-1 text-[10px] leading-none text-slate-500">E2E 암호화 문자</p>
            </div>
          </div>
          <LogoutButton />
        </div>
        <ChatList />
        <div className="mt-auto space-y-2 border-t border-white/5 p-3">
          <NewConversationModal />
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

function EmptyChat() {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 text-center animate-rise">
      <div className="grid h-16 w-16 place-items-center rounded-3xl bg-white/[0.04] ring-1 ring-white/[0.06]">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden>
          <path
            d="M4 7a3 3 0 0 1 3-3h10a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H9l-4 4v-4H7a3 3 0 0 1-3-3V7z"
            stroke="#475569" strokeWidth="1.6" strokeLinejoin="round"
          />
          <path d="M8.5 9h7M8.5 12h4" stroke="#475569" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      </div>
      <div>
        <p className="text-sm font-medium text-slate-300">대화를 선택하세요</p>
        <p className="mt-1 text-xs text-slate-500">왼쪽 목록에서 선택하거나 새 대화를 시작할 수 있어요.</p>
      </div>
    </div>
  );
}

function LogoutButton() {
  const logout = useStore((s) => s.logout);
  const username = useStore((s) => s.username);
  return (
    <div className="flex items-center gap-1.5">
      <span className="hidden max-w-[90px] truncate rounded-full bg-white/[0.04] px-2.5 py-1 text-[11px] text-slate-400 ring-1 ring-white/[0.06] sm:block">
        {username}
      </span>
      <button
        onClick={logout}
        title="로그아웃"
        aria-label="로그아웃"
        className="grid h-8 w-8 place-items-center rounded-lg text-slate-500 ring-1 ring-white/10 transition hover:bg-white/[0.06] hover:text-slate-200"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
          <path d="M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3M14 8l4 4-4 4M18 12H9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
    </div>
  );
}
