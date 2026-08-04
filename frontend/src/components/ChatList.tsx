import { useEffect } from "react";
import { useStore } from "../store/useStore";

export default function ChatList() {
  const { conversations, activeCid, selectConversation, refreshConversations } = useStore();

  useEffect(() => {
    refreshConversations();
  }, [refreshConversations]);

  return (
    <ul className="py-2">
      {conversations.length === 0 && (
        <li className="px-4 py-8 text-xs text-slate-500 text-center">
          대화가 없습니다. 아래 "새 대화"에서 시작하세요.
        </li>
      )}
      {conversations.map((c) => (
        <li key={c.cid}>
          <button
            onClick={() => selectConversation(c.cid)}
            className={`w-full text-left px-4 py-3 text-sm border-l-2 transition ${
              activeCid === c.cid
                ? "border-cyan-400 bg-cyan-500/10"
                : "border-transparent hover:bg-slate-800/50"
            }`}
            >
              <div className="font-medium truncate">
              {c.name || c.members.join(", ")}
              </div>
              <div className="text-[10px] text-slate-500 mt-0.5">
              {c.name ? "SMS" : `${c.members.length}명`} · {new Date(c.created_at * 1000).toLocaleDateString("ko-KR")}
              </div>
          </button>
        </li>
      ))}
    </ul>
  );
}
