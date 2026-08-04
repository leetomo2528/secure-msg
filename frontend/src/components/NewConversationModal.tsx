import { useState } from "react";
import { useStore } from "../store/useStore";
import { Segmented } from "./ui";

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
        className="btn-primary w-full !py-2.5 text-xs"
      >
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden>
          <path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" />
        </svg>
        새 대화
      </button>
      {open && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4 backdrop-blur-sm"
          onClick={() => setOpen(false)}
        >
          <div
            className="w-full max-w-sm space-y-4 rounded-2xl bg-night-soft p-5 shadow-bubble ring-1 ring-white/10 animate-rise"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="text-sm font-bold text-slate-100">새 대화 시작</h2>
            <Segmented
              options={[
                { value: "sms", label: "SMS" },
                { value: "chat", label: "SecureMsg 대화" },
              ]}
              value={mode}
              onChange={(m) => { setMode(m); setErr(null); }}
            />
            <p className="text-[10px] leading-relaxed text-slate-500">
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
              className="field"
            />
            {err && <p className="text-[10px] text-red-400 animate-rise">{err}</p>}
            <div className="flex gap-2">
              <button
                onClick={() => setOpen(false)}
                className="btn-ghost flex-1 !py-2 text-xs"
              >
                취소
              </button>
              <button
                onClick={submit}
                disabled={busy}
                className="btn-primary flex-1 !py-2 text-xs"
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
