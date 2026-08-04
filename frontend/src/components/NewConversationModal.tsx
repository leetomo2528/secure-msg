import { useState } from "react";
import { useStore } from "../store/useStore";

export default function NewConversationModal() {
  const { newConversation, newSmsConversation, selectConversation } = useStore();
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<"sms" | "chat">("sms");
  const [members, setMembers] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    const list = members.split(",").map((s) => s.trim()).filter(Boolean);
    if (list.length === 0) {
      setErr(mode === "sms" ? "전화번호를 입력하세요" : "상대 아이디를 한 개 이상 입력하세요");
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const cid = mode === "sms"
        ? await newSmsConversation(list[0])
        : await newConversation(list);
      if (cid) {
        setOpen(false);
        setMembers("");
        await selectConversation(cid);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="w-full rounded-lg bg-cyan-500/20 border border-cyan-500/40 text-cyan-300 py-2 text-xs hover:bg-cyan-500/30"
      >
        + 새 대화
      </button>
      {open && (
        <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 px-4" onClick={() => setOpen(false)}>
          <div className="bg-ink panel border border-slate-700 rounded-xl p-5 w-full max-w-sm space-y-3" onClick={(e) => e.stopPropagation()}>
            <h2 className="text-sm font-semibold text-cyan-300">새 대화 시작</h2>
            <div className="flex rounded-lg border border-slate-700 overflow-hidden">
              <button
                type="button"
                onClick={() => { setMode("sms"); setErr(null); }}
                className={`flex-1 py-2 text-xs ${mode === "sms" ? "bg-cyan-500/20 text-cyan-300" : "text-slate-400"}`}
              >SMS</button>
              <button
                type="button"
                onClick={() => { setMode("chat"); setErr(null); }}
                className={`flex-1 py-2 text-xs ${mode === "chat" ? "bg-cyan-500/20 text-cyan-300" : "text-slate-400"}`}
              >SecureMsg 대화</button>
            </div>
            <p className="text-[10px] text-slate-500">
              {mode === "sms"
                ? "Android 휴대폰을 통해 발신할 전화번호를 입력하세요. (예: +821012345678)"
                : "상대방의 SecureMsg 아이디를 쉼표로 구분해 입력하세요."}
            </p>
            <input
              value={members}
              onChange={(e) => setMembers(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  if (!busy) void submit();
                }
              }}
              maxLength={1024}
              placeholder={mode === "sms" ? "+821012345678" : "alice_92, bob_dev"}
              className="w-full rounded-lg border border-slate-700 px-3 py-2 text-sm focus:outline-none focus:border-cyan-500"
            />
            {err && <p className="text-[10px] text-red-400">{err}</p>}
            <div className="flex gap-2">
              <button
                onClick={() => setOpen(false)}
                className="flex-1 rounded-lg border border-slate-700 text-slate-300 py-2 text-xs"
              >
                취소
              </button>
              <button
                onClick={submit}
                disabled={busy}
                className="flex-1 rounded-lg bg-cyan-500 hover:bg-cyan-400 disabled:opacity-40 text-slate-900 font-medium py-2 text-xs"
              >
                {busy ? "생성 중…" : "생성"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
