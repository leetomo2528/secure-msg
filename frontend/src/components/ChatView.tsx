import { useEffect, useRef, useState } from "react";
import { useStore } from "../store/useStore";
import { b64u } from "../crypto/keys";
import type { MessageAttachment } from "../store/db";

export default function ChatView({ cid }: { cid: string }) {
  const { activeMessages, conversations, sendContent, sid } = useStore();
  const [text, setText] = useState("");
  const [subject, setSubject] = useState("");
  const [attachments, setAttachments] = useState<MessageAttachment[]>([]);
  const [sending, setSending] = useState(false);
  const [attachmentError, setAttachmentError] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const conversation = conversations.find((item) => item.cid === cid);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [activeMessages.length]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (sending || (!text.trim() && attachments.length === 0 && !subject.trim())) return;
    setSending(true);
    try {
      const isMms = attachments.length > 0 || Boolean(subject.trim());
      const ok = await sendContent(cid, {
        v: 1,
        type: isMms ? "mms" : "text",
        text: text.trim(),
        subject: subject.trim() || undefined,
        attachments,
      });
      if (ok) {
        setText("");
        setSubject("");
        setAttachments([]);
      }
    } finally {
      setSending(false);
    }
  };

  const chooseFiles = async (event: React.ChangeEvent<HTMLInputElement>) => {
    setAttachmentError(null);
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (files.length + attachments.length > 8) {
      setAttachmentError("첨부파일은 최대 8개까지 가능합니다");
      return;
    }
    const total = attachments.reduce((sum, item) => sum + item.size, 0);
    const next: MessageAttachment[] = [];
    let nextTotal = total;
    for (const file of files) {
      nextTotal += file.size;
      if (nextTotal > 512 * 1024) {
        setAttachmentError("첨부파일 전체 크기는 512KB까지 가능합니다");
        return;
      }
      next.push({
        name: file.name.slice(0, 120) || "attachment",
        content_type: file.type || "application/octet-stream",
        data: b64u(new Uint8Array(await file.arrayBuffer())),
        size: file.size,
      });
    }
    setAttachments((current) => [...current, ...next]);
  };

  return (
    <div className="flex flex-col h-full">
      <div className="md:hidden flex items-center gap-3 border-b border-slate-800 px-3 py-2">
        <button
          type="button"
          onClick={() => useStore.setState({ activeCid: null, activeMessages: [] })}
          className="rounded border border-slate-700 px-2 py-1 text-xs text-slate-300"
          aria-label="대화 목록으로 돌아가기"
        >
          ← 목록
        </button>
        <div className="min-w-0 truncate text-sm font-medium text-slate-200">
          {conversation?.name || conversation?.members.join(", ") || "대화"}
        </div>
      </div>
      <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-4 space-y-2">
        {activeMessages.length === 0 && (
          <div className="text-xs text-slate-500 text-center pt-10">
            메시지가 없습니다. 첫 메시지를 보내보세요.
          </div>
        )}
        {activeMessages.map((m) => {
          if (m.blocked) {
            return (
              <div key={`${m.cid}:${m.seq}`} className="text-center text-[10px] text-slate-600 py-1">
                ⛔ 차단된 메시지 (seq {m.seq})
              </div>
            );
          }
          const mine = m.sender_sid === sid;
          return (
            <div key={`${m.cid}:${m.seq}`} className={`flex ${mine ? "justify-end" : "justify-start"}`}>
              <div className={`max-w-[75%] rounded-2xl px-3.5 py-2 text-sm whitespace-pre-wrap break-words ${
                mine ? "bg-cyan-500 text-slate-900" : "bg-slate-800 text-slate-100"
              }`}>
                {m.content_type === "mms" && m.subject && <div className="font-semibold mb-1">{m.subject}</div>}
                {m.plaintext && <div>{m.plaintext}</div>}
                {m.attachments?.map((attachment, index) => (
                  <AttachmentPreview key={`${m.cid}:${m.seq}:${index}:${attachment.name}`} attachment={attachment} mine={mine} />
                ))}
                <div className={`text-[9px] mt-1 ${mine ? "text-slate-700" : "text-slate-500"}`}>
                  {new Date(m.created_at).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}
                  {mine && m.carrier_status && m.carrier_status !== "none" && ` · ${carrierLabel(m.carrier_status)}`}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <form onSubmit={submit} className="p-3 border-t border-slate-800 space-y-2">
        {(attachments.length > 0 || subject) && (
          <div className="flex items-center gap-2 text-[10px] text-slate-400">
            <input
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              placeholder="MMS 제목(선택)"
              className="flex-1 rounded border border-slate-700 bg-slate-800 px-2 py-1 focus:outline-none focus:border-cyan-500"
              maxLength={120}
            />
            <span>{attachments.length}개 첨부</span>
          </div>
        )}
        {attachmentError && <div className="text-[10px] text-red-400">{attachmentError}</div>}
        {attachments.length > 0 && (
          <div className="flex gap-1 overflow-x-auto">
            {attachments.map((attachment, index) => (
              <button
                key={`${attachment.name}-${index}`}
                type="button"
                onClick={() => setAttachments((current) => current.filter((_, i) => i !== index))}
                className="shrink-0 rounded bg-slate-800 border border-slate-700 px-2 py-1 text-[10px] text-slate-300"
                title="첨부 제거"
              >
                {attachment.name} ×
              </button>
            ))}
          </div>
        )}
        <div className="flex items-center gap-2">
          <label className="cursor-pointer rounded-full border border-slate-700 px-3 py-2 text-xs text-slate-300 hover:border-cyan-500">
            첨부
            <input type="file" multiple className="hidden" onChange={chooseFiles} />
          </label>
        <input
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={attachments.length > 0 ? "MMS 설명(선택)…" : "메시지 입력…"}
          className="flex-1 rounded-full bg-slate-800 border border-slate-700 px-4 py-2.5 text-sm focus:outline-none focus:border-cyan-500"
          maxLength={20_000}
        />
        <button
          type="submit"
          disabled={sending || (!text.trim() && attachments.length === 0 && !subject.trim())}
          className="rounded-full bg-cyan-500 hover:bg-cyan-400 disabled:opacity-40 text-slate-900 font-medium px-4 py-2.5 text-sm"
        >
          {sending ? "전송 중…" : "전송"}
        </button>
        </div>
      </form>
    </div>
  );
}

function carrierLabel(status: string): string {
  return ({
    queued: "대기",
    dispatched: "발송 요청",
    sent: "통신사 접수",
    delivered: "전달됨",
    failed: "발송 실패",
    delivery_failed: "전달 실패",
    unknown: "상태 확인 중",
  } as Record<string, string>)[status] ?? status;
}

function dataUrl(attachment: MessageAttachment): string {
  const standard = attachment.data.replace(/-/g, "+").replace(/_/g, "/")
    .padEnd(Math.ceil(attachment.data.length / 4) * 4, "=");
  const mime = /^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/.test(attachment.content_type)
    ? attachment.content_type
    : "application/octet-stream";
  return `data:${mime};base64,${standard}`;
}

function AttachmentPreview({ attachment, mine }: { attachment: MessageAttachment; mine: boolean }) {
  const url = dataUrl(attachment);
  if (/^image\/[A-Za-z0-9!#$&^_.+-]+$/.test(attachment.content_type)) {
    return (
      <a href={url} download={attachment.name} className="block mt-2">
        <img src={url} alt={attachment.name} className="max-h-56 max-w-full rounded-lg" loading="lazy" />
      </a>
    );
  }
  return (
    <a
      href={url}
      download={attachment.name}
      className={`block mt-2 underline text-xs ${mine ? "text-slate-700" : "text-cyan-300"}`}
    >
      📎 {attachment.name}
    </a>
  );
}
