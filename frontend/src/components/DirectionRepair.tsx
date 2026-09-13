import { useState } from "react";
import { useStore } from "../store/useStore";

/**
 * Re-classify which side of a thread each message belongs on.
 *
 * Direction is decided once, when a message is first stored, and older history
 * is stamped by a pass that runs at login and then records that it ran. If a
 * device ends up with unstamped rows AND a record saying they were handled,
 * every message in that thread renders as the other person's and nothing fixes
 * it on its own. This forces the pass to run again.
 *
 * It reports counts rather than a tick, because those counts are the only way
 * to tell a thread that could not be classified from one that was never looked
 * at.
 */
export default function DirectionRepair() {
  const repairDirections = useStore((s) => s.repairDirections);
  const [running, setRunning] = useState(false);
  const [report, setReport] = useState<string | null>(null);

  const run = async () => {
    if (running) return;
    setRunning(true);
    setReport(null);
    try {
      setReport(await repairDirections());
    } catch (error) {
      setReport(error instanceof Error ? error.message : String(error));
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="card px-3.5 py-3">
      <button
        type="button"
        onClick={() => void run()}
        disabled={running}
        className="flex w-full items-center justify-between text-xs font-semibold text-tx-2 transition hover:text-tx-1 disabled:opacity-50"
      >
        <span className="flex items-center gap-2">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden className="text-tx-4">
            <path
              d="M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6"
              stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"
            />
          </svg>
          보낸 문자·받은 문자 다시 구분
        </span>
        <span className="text-[10px] font-normal text-tx-4">{running ? "확인 중…" : "실행"}</span>
      </button>
      <p className="mt-2 text-[10px] leading-relaxed text-tx-4">
        말풍선이 전부 한쪽에만 붙어 있으면 실행하세요. 서버에서 과거 내역의 방향을 다시 읽어옵니다.
      </p>
      {report && (
        <p className="mt-2 rounded-lg bg-fg/[0.04] px-2 py-1.5 text-[10px] tabular-nums text-tx-3">
          {report}
        </p>
      )}
    </div>
  );
}
