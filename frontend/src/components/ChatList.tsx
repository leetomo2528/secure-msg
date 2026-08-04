import { useEffect } from "react";
import { useStore } from "../store/useStore";

export default function ChatList() {
  const { conversations, activeCid, selectConversation, refreshConversations } = useStore();

  useEffect(() => {
    refreshConversations();
  }, [refreshConversations]);

  return (
    <ul className="space-y-1 px-2 py-2">
      {conversations.length === 0 && (
        <li className="px-4 py-10 text-center text-xs leading-relaxed text-tx-4">
          아직 대화가 없어요.<br />아래 <span className="text-accent-tx">+ 새 대화</span>에서 시작하세요.
        </li>
      )}
      {conversations.map((c) => {
        const display = c.name || c.members.join(", ");
        const active = activeCid === c.cid;
        return (
          <li key={c.cid}>
            <button
              onClick={() => selectConversation(c.cid)}
              className={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition ${
                active ? "bg-fg/[0.07] ring-1 ring-accent-tx/20" : "hover:bg-fg/[0.04]"
              }`}
            >
              <Avatar label={display} />
              <div className="min-w-0 flex-1">
                <div className="flex items-baseline justify-between gap-2">
                  <span className={`truncate text-sm font-semibold ${active ? "text-tx-1" : "text-tx-2"}`}>
                    {display}
                  </span>
                  <span className="shrink-0 text-[10px] text-tx-4">
                    {new Date(c.created_at * 1000).toLocaleDateString("ko-KR", { month: "short", day: "numeric" })}
                  </span>
                </div>
                <div className="mt-0.5 truncate text-[11px] text-tx-4">
                  {c.name ? "SMS" : `SecureMsg · ${c.members.length}명`}
                </div>
              </div>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

function hueOf(seed: string): number {
  let h = 0;
  for (const ch of seed) h = (h * 31 + (ch.codePointAt(0) ?? 0)) >>> 0;
  return h % 360;
}

export function Avatar({ label, size = "h-9 w-9 text-[13px]" }: { label: string; size?: string }) {
  const trimmed = (label || "?").trim() || "?";
  const hue = hueOf(trimmed);
  const initial = Array.from(trimmed)[0].toUpperCase();
  return (
    <div
      aria-hidden
      className={`grid shrink-0 place-items-center rounded-full font-bold text-white/90 ${size}`}
      style={{
        background: `linear-gradient(135deg, hsl(${hue} 62% 42%), hsl(${(hue + 45) % 360} 68% 28%))`,
      }}
    >
      {initial}
    </div>
  );
}
