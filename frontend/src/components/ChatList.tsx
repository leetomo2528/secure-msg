import { useEffect, useState } from "react";
import { useStore } from "../store/useStore";
import { searchMessages, type MessageRow } from "../store/db";
import { conversationDisplayName } from "../store/helpers";

export default function ChatList() {
  const { conversations, activeCid, selectConversation, refreshConversations } = useStore();
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<MessageRow[]>([]);

  useEffect(() => {
    const q = query.trim();
    if (!q) {
      setHits([]);
      return;
    }
    const timer = setTimeout(() => {
      void searchMessages(q).then(setHits);
    }, 200);
    return () => clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    refreshConversations();
  }, [refreshConversations]);

  const q = query.trim().toLowerCase();
  const convHits = q
    ? conversations.filter((c) =>
        [conversationDisplayName(c), c.name, c.members.join(", ")]
          .some((label) => label.toLowerCase().includes(q)))
    : [];
  const searching = q.length > 0;

  return (
    <div className="flex-1">
      <div className="px-3 pt-2">
        <div className="flex items-center gap-2 rounded-xl bg-fg/[0.04] px-3 py-2 ring-1 ring-fg/[0.07] transition focus-within:ring-accent-tx/40">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden className="shrink-0 text-tx-4">
            <circle cx="11" cy="11" r="7" stroke="currentColor" strokeWidth="2" />
            <path d="M16.5 16.5L21 21" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="대화·메시지 검색"
            className="min-w-0 flex-1 bg-transparent text-xs focus:outline-none"
          />
          {query && (
            <button
              type="button"
              onClick={() => setQuery("")}
              aria-label="검색 지우기"
              className="text-tx-4 transition hover:text-tx-2"
            >×</button>
          )}
        </div>
      </div>

      {searching ? (
        <div className="space-y-1 px-2 py-2">
          {convHits.map((c) => (
            <button
              key={c.cid}
              onClick={() => selectConversation(c.cid)}
              className="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left transition hover:bg-fg/[0.04]"
            >
              <Avatar label={conversationDisplayName(c)} size="h-8 w-8 text-[11px]" />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-semibold text-tx-2">
                  {conversationDisplayName(c)}
                </div>
                <div className="text-[10px] text-tx-4">대화</div>
              </div>
            </button>
          ))}
          {hits.map((m) => {
            const conv = conversations.find((c) => c.cid === m.cid);
            const snippet = (m.subject ? `${m.subject} — ` : "") + m.plaintext;
            return (
              <button
                key={`${m.cid}:${m.seq}`}
                onClick={() => selectConversation(m.cid)}
                className="flex w-full items-start gap-3 rounded-xl px-3 py-2 text-left transition hover:bg-fg/[0.04]"
              >
                <Avatar
                  label={conversationDisplayName(conv, "?")}
                  size="h-8 w-8 text-[11px]"
                />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="truncate text-xs font-semibold text-tx-2">
                      {conversationDisplayName(conv)}
                    </span>
                    <span className="shrink-0 text-[9px] text-tx-4">
                      {new Date(m.created_at).toLocaleDateString("ko-KR", { month: "short", day: "numeric" })}
                    </span>
                  </div>
                  <div className="mt-0.5 line-clamp-2 text-[11px] leading-snug text-tx-4">{snippet}</div>
                </div>
              </button>
            );
          })}
          {convHits.length === 0 && hits.length === 0 && (
            <div className="px-4 py-8 text-center text-xs text-tx-4">검색 결과가 없습니다</div>
          )}
        </div>
      ) : (
        <ul className="space-y-1 px-2 py-2">
      {conversations.length === 0 && (
        <li className="px-4 py-10 text-center text-xs leading-relaxed text-tx-4">
          아직 대화가 없어요.<br />아래 <span className="text-accent-tx">+ 새 대화</span>에서 시작하세요.
        </li>
      )}
      {conversations.map((c) => {
        const display = conversationDisplayName(c);
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
      )}
    </div>
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
