import { useStore } from "./store/useStore";
import Onboarding from "./components/Onboarding";
import ChatList from "./components/ChatList";
import ChatView from "./components/ChatView";
import BlocklistEditor from "./components/BlocklistEditor";
import DeviceManager from "./components/DeviceManager";
import NewConversationModal from "./components/NewConversationModal";

export default function App() {
  const { ready, authed, activeCid, error } = useStore();

  if (!ready) {
    return <div className="flex h-full items-center justify-center text-slate-400">초기화 중…</div>;
  }
  if (!authed) return <Onboarding />;

  return (
    <div className="relative grid grid-cols-1 md:grid-cols-[320px_1fr] h-full">
      {error && (
        <div className="absolute top-2 left-1/2 -translate-x-1/2 z-50 max-w-[90%] rounded border border-red-500/40 bg-slate-950 px-3 py-2 text-xs text-red-300 shadow-lg">
          <span>{error}</span>
          <button
            type="button"
            onClick={() => useStore.setState({ error: null })}
            className="ml-3 text-slate-400 hover:text-white"
            aria-label="오류 닫기"
          >×</button>
        </div>
      )}
      <aside
        className={`${activeCid ? "hidden md:block" : "block"} border-r border-slate-800 overflow-y-auto`}
        style={{ paddingTop: "var(--safe-top)" }}
      >
        <div className="p-4 flex items-center justify-between border-b border-slate-800">
          <h1 className="text-lg font-semibold text-cyan-400">Secure Msg</h1>
          <LogoutButton />
        </div>
        <ChatList />
        <div className="p-4 space-y-2 border-t border-slate-800">
          <BlocklistEditor />
          <DeviceManager />
          <NewConversationModal />
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
    <div className="flex h-full items-center justify-center text-slate-500 text-sm">
      왼쪽에서 대화를 선택하거나 새 대화를 시작하세요.
    </div>
  );
}

function LogoutButton() {
  const logout = useStore((s) => s.logout);
  const username = useStore((s) => s.username);
  return (
    <div className="flex items-center gap-2">
      <span className="text-xs text-slate-500">{username}</span>
      <button
        onClick={logout}
        className="text-xs text-slate-400 hover:text-cyan-400 px-2 py-1 rounded border border-slate-700"
      >
        로그아웃
      </button>
    </div>
  );
}
