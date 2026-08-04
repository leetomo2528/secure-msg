import { useEffect, useState } from "react";
import { useStore } from "../store/useStore";

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
    <div className="rounded-lg border border-slate-700">
      <button
        onClick={() => setOpen((v) => !v)}
        className="w-full flex items-center justify-between px-3 py-2 text-xs text-slate-300 hover:bg-slate-800/50"
      >
        <span>차단 키워드 ({blockKeywords.length})</span>
        <span>{open ? "▾" : "▸"}</span>
      </button>
      {open && (
        <div className="px-3 pb-3 space-y-2">
          <p className="text-[10px] text-slate-500 leading-relaxed">
            복호화된 메시지에 이 문자가 포함되면 숨김 처리됩니다.
            키워드 목록은 이 기기(IndexedDB)에만 저장되고 서버로 전송되지 않습니다.
            Android 기본 SMS 앱의 수신 차단은 휴대폰에서 별도로 적용됩니다.
          </p>
          <div className="flex gap-1">
            <input
              value={kw}
              onChange={(e) => setKw(e.target.value.slice(0, 120))}
              maxLength={120}
              placeholder="예: 광고, 스팸"
              className="flex-1 rounded border border-slate-700 px-2 py-1 text-xs focus:outline-none focus:border-cyan-500"
            />
            <button
              onClick={add}
              className="rounded bg-cyan-500/20 text-cyan-300 px-2 py-1 text-xs border border-cyan-500/40"
            >
              추가
            </button>
          </div>
          <ul className="space-y-1 max-h-32 overflow-y-auto">
            {blockKeywords.map((b) => (
              <li key={b.id} className="flex items-center justify-between bg-slate-800/50 rounded px-2 py-1 text-xs">
                <span className="truncate">{b.keyword}</span>
                <button
                  onClick={() => removeBlock(b.id)}
                  className="text-red-400/70 hover:text-red-400 ml-2"
                >
                  ×
                </button>
              </li>
            ))}
            {blockKeywords.length === 0 && (
              <li className="text-[10px] text-slate-500 text-center py-1">차단 키워드 없음</li>
            )}
          </ul>
        </div>
      )}
    </div>
  );
}
