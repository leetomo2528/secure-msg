import { useEffect, useState } from "react";
import { useStore } from "../store/useStore";
import { CollapsibleCard } from "./ui";

export default function BlocklistEditor() {
  const blockKeywords = useStore((s) => s.blockKeywords);
  const blockedSenders = useStore((s) => s.blockedSenders);
  const addBlock = useStore((s) => s.addBlock);
  const removeBlock = useStore((s) => s.removeBlock);
  const addBlockedSenderRule = useStore((s) => s.addBlockedSenderRule);
  const removeBlockedSenderRule = useStore((s) => s.removeBlockedSenderRule);
  const refreshBlocklist = useStore((s) => s.refreshBlocklist);
  const [kw, setKw] = useState("");
  const [sender, setSender] = useState("");
  const [open, setOpen] = useState(false);

  useEffect(() => { refreshBlocklist(); }, [refreshBlocklist]);

  const addKeyword = async () => {
    const t = kw.trim();
    if (!t) return;
    await addBlock(t);
    setKw("");
  };

  const addSender = async () => {
    const t = sender.trim();
    if (!t) return;
    await addBlockedSenderRule(t);
    setSender("");
  };

  return (
    <CollapsibleCard
      open={open}
      onToggle={() => setOpen((v) => !v)}
      icon={(
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden>
          <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.8" />
          <path d="M5.7 5.7l12.6 12.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
      )}
      title="차단 관리"
      badge={blockKeywords.length + blockedSenders.length}
    >
      <p className="text-[10px] leading-relaxed text-tx-4">
        차단 키워드·발신번호는 모든 기기에 동기화됩니다.
        복호화된 메시지에 키워드가 포함되거나 차단된 번호에서 오면 숨김 처리됩니다.
        Android 수신 차단에도 동일하게 적용됩니다.
      </p>

      <div className="space-y-1.5">
        <div className="text-[10px] font-semibold text-tx-3">키워드</div>
        <div className="flex gap-1.5">
          <input
            value={kw}
            onChange={(e) => setKw(e.target.value.slice(0, 120))}
            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); void addKeyword(); } }}
            maxLength={120}
            placeholder="예: 광고, 스팸"
            className="field !py-1.5 text-xs"
          />
          <button
            onClick={addKeyword}
            className="shrink-0 rounded-xl bg-accent-tx/10 px-3 text-xs font-semibold text-accent-tx ring-1 ring-accent-tx/30 transition hover:bg-accent-tx/20"
          >
            추가
          </button>
        </div>
        <ul className="max-h-28 space-y-1 overflow-y-auto">
          {blockKeywords.map((b) => (
            <li key={b.id} className="flex items-center justify-between rounded-lg bg-fg/[0.04] px-2.5 py-1.5 text-xs text-tx-2">
              <span className="truncate">{b.keyword}</span>
              <button
                onClick={() => removeBlock(b.id)}
                aria-label={`"${b.keyword}" 차단 해제`}
                className="ml-2 rounded px-1 text-tx-4 transition hover:bg-red-500/10 hover:text-red-500"
              >
                ×
              </button>
            </li>
          ))}
          {blockKeywords.length === 0 && (
            <li className="py-1 text-center text-[10px] text-tx-4">차단 키워드 없음</li>
          )}
        </ul>
      </div>

      <div className="space-y-1.5">
        <div className="text-[10px] font-semibold text-tx-3">발신번호</div>
        <div className="flex gap-1.5">
          <input
            value={sender}
            onChange={(e) => setSender(e.target.value.slice(0, 32))}
            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); void addSender(); } }}
            maxLength={32}
            placeholder="예: +821012345678"
            className="field !py-1.5 text-xs"
          />
          <button
            onClick={addSender}
            className="shrink-0 rounded-xl bg-accent-tx/10 px-3 text-xs font-semibold text-accent-tx ring-1 ring-accent-tx/30 transition hover:bg-accent-tx/20"
          >
            추가
          </button>
        </div>
        <ul className="max-h-28 space-y-1 overflow-y-auto">
          {blockedSenders.map((s) => (
            <li key={s.id} className="flex items-center justify-between rounded-lg bg-fg/[0.04] px-2.5 py-1.5 text-xs text-tx-2">
              <span className="truncate">{s.sender}</span>
              <button
                onClick={() => removeBlockedSenderRule(s.id)}
                aria-label={`"${s.sender}" 차단 해제`}
                className="ml-2 rounded px-1 text-tx-4 transition hover:bg-red-500/10 hover:text-red-500"
              >
                ×
              </button>
            </li>
          ))}
          {blockedSenders.length === 0 && (
            <li className="py-1 text-center text-[10px] text-tx-4">차단 번호 없음</li>
          )}
        </ul>
      </div>
    </CollapsibleCard>
  );
}
