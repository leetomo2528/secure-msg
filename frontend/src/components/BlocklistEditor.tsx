import { useEffect, useState } from "react";
import { useStore } from "../store/useStore";
import { CollapsibleCard } from "./ui";

export default function BlocklistEditor() {
  const { blockKeywords, addBlock, removeBlock, refreshBlocklist } = useStore();
  const [kw, setKw] = useState("");
  const [open, setOpen] = useState(false);

  useEffect(() => { refreshBlocklist(); }, [refreshBlocklist]);

  const add = async () => {
    const t = kw.trim();
    if (!t) return;
    await addBlock(t);
    setKw("");
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
      title="차단 키워드"
      badge={blockKeywords.length}
    >
      <p className="text-[10px] leading-relaxed text-slate-500">
        복호화된 메시지에 이 문자가 포함되면 숨김 처리됩니다.
        키워드 목록은 이 기기(IndexedDB)에만 저장되고 서버로 전송되지 않습니다.
        Android 기본 SMS 앱의 수신 차단은 휴대폰에서 별도로 적용됩니다.
      </p>
      <div className="flex gap-1.5">
        <input
          value={kw}
          onChange={(e) => setKw(e.target.value.slice(0, 120))}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); void add(); } }}
          maxLength={120}
          placeholder="예: 광고, 스팸"
          className="field !py-1.5 text-xs"
        />
        <button
          onClick={add}
          className="shrink-0 rounded-xl bg-teal-400/10 px-3 text-xs font-semibold text-teal-300 ring-1 ring-teal-300/30 transition hover:bg-teal-400/20"
        >
          추가
        </button>
      </div>
      <ul className="max-h-32 space-y-1 overflow-y-auto">
        {blockKeywords.map((b) => (
          <li key={b.id} className="flex items-center justify-between rounded-lg bg-white/[0.04] px-2.5 py-1.5 text-xs text-slate-300">
            <span className="truncate">{b.keyword}</span>
            <button
              onClick={() => removeBlock(b.id)}
              aria-label={`"${b.keyword}" 차단 해제`}
              className="ml-2 rounded px-1 text-slate-500 transition hover:bg-red-500/10 hover:text-red-400"
            >
              ×
            </button>
          </li>
        ))}
        {blockKeywords.length === 0 && (
          <li className="py-1.5 text-center text-[10px] text-slate-500">차단 키워드 없음</li>
        )}
      </ul>
    </CollapsibleCard>
  );
}
