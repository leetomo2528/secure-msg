import { useEffect, useRef, useState } from "react";
import { useStore } from "../store/useStore";
import { b64u } from "../crypto/keys";
import type { MessageAttachment } from "../store/db";
import { Avatar } from "./ChatList";

export default function ChatView({ cid }: { cid: string }) {
  const { activeMessages, conversations, sendContent, sid } = useStore();
  const [text, setText] = useState("");
  const [subject, setSubject] = useState("");
  const [attachments, setAttachments] = useState<MessageAttachment[]>([]);
  const [sending, setSending] = useState(false);
  const [attachmentError, setAttachmentError] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const conversation = conversations.find((item) => item.cid === cid);
  const title = conversation?.name || conversation?.members.join(", ") || "대화";

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
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-3 border-b border-fg/5 bg-night-soft/70 px-3 py-2.5 backdrop-blur">
        <button
          type="button"
          onClick={() => useStore.setState({ activeCid: null, activeMessages: [] })}
          className="grid h-8 w-8 shrink-0 place-items-center rounded-lg text-tx-3 ring-1 ring-fg/10 transition hover:bg-fg/[0.06] hover:text-tx-1 md:hidden"
          aria-label="대화 목록으로 돌아가기"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
            <path d="M15 5l-7 7 7 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        <Avatar label={title} size="h-8 w-8 text-[11px]" />
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-tx-1">{title}</div>
          <div className="text-[10px] text-tx-4">
            {conversation?.name ? "SMS · Android 게이트웨이 경유 발신" : "E2E 암호화 대화"}
          </div>
        </div>
      </div>

      <div ref={scrollRef} className="flex-1 space-y-2.5 overflow-y-auto px-4 py-5">
        {activeMessages.length === 0 && (
          <div className="pt-14 text-center text-xs leading-relaxed text-tx-4">
            메시지가 없습니다.<br />첫 메시지를 보내보세요.
          </div>
        )}
        {activeMessages.map((m) => {
          if (m.blocked) {
            return (
              <div key={`${m.cid}:${m.seq}`} className="py-1 text-center">
                <span className="inline-flex items-center gap-1 rounded-full bg-fg/[0.03] px-3 py-1 text-[10px] text-tx-4 ring-1 ring-fg/[0.05]">
                  ⛔ 차단된 메시지 (seq {m.seq})
                </span>
              </div>
            );
          }
          const mine = m.sender_sid === sid;
          return (
            <div key={`${m.cid}:${m.seq}`} className={`flex animate-rise ${mine ? "justify-end" : "justify-start"}`}>
              <div
                className={`max-w-[78%] whitespace-pre-wrap break-words rounded-2xl px-4 py-2.5 text-sm leading-relaxed shadow-bubble ${
                  mine
                    ? "rounded-br-md bg-gradient-to-br from-teal-500 to-sky-600 text-white"
                    : "rounded-bl-md bg-fg/[0.06] text-tx-1 ring-1 ring-fg/[0.06]"
                }`}
              >
                {m.content_type === "mms" && m.subject && <div className="mb-1 font-semibold">{m.subject}</div>}
                {m.plaintext && <div>{m.plaintext}</div>}
                {m.attachments?.map((attachment, index) => (
                  <AttachmentPreview key={`${m.cid}:${m.seq}:${index}:${attachment.name}`} attachment={attachment} mine={mine} />
                ))}
                <div className={`mt-1.5 text-[10px] tabular-nums ${mine ? "text-white/60" : "text-tx-4"}`}>
                  {new Date(m.created_at).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}
                  {mine && m.carrier_status && m.carrier_status !== "none" && ` · ${carrierLabel(m.carrier_status)}`}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <form onSubmit={submit} className="space-y-2 border-t border-fg/5 bg-night-soft/70 p-3 backdrop-blur">
        {(attachments.length > 0 || subject) && (
          <div className="flex items-center gap-2">
            <input
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              placeholder="MMS 제목(선택)"
              className="field flex-1 !py-1.5 text-xs"
              maxLength={120}
            />
            <span className="shrink-0 rounded-full bg-fg/[0.04] px-2.5 py-1 text-[10px] text-tx-3 ring-1 ring-fg/[0.06]">
              첨부 {attachments.length}개
            </span>
          </div>
        )}
        {attachmentError && <div className="text-[11px] text-danger-tx">{attachmentError}</div>}
        {attachments.length > 0 && (
          <div className="flex gap-1.5 overflow-x-auto pb-0.5">
            {attachments.map((attachment, index) => (
              <button
                key={`${attachment.name}-${index}`}
                type="button"
                onClick={() => setAttachments((current) => current.filter((_, i) => i !== index))}
                className="shrink-0 rounded-full bg-fg/[0.05] px-3 py-1.5 text-[11px] text-tx-2 ring-1 ring-fg/10 transition hover:bg-red-500/10 hover:text-red-500 hover:ring-red-400/30"
                title="첨부 제거"
              >
                {attachment.name} ×
              </button>
            ))}
          </div>
        )}
        <div className="flex items-center gap-2 rounded-2xl bg-fg/[0.04] px-2 py-1.5 ring-1 ring-fg/10 transition focus-within:ring-2 focus-within:ring-accent-tx/40">
          <label
            className="grid h-9 w-9 shrink-0 cursor-pointer place-items-center rounded-full text-tx-3 transition hover:bg-fg/[0.06] hover:text-accent-tx"
            title="파일 첨부"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden>
              <path
                d="M20 11.5l-8.2 8.2a5.5 5.5 0 0 1-7.8-7.8l8.5-8.5a3.7 3.7 0 0 1 5.2 5.2l-8.5 8.5a1.8 1.8 0 0 1-2.6-2.6l7.8-7.8"
                stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"
              />
            </svg>
            <input type="file" multiple className="hidden" onChange={chooseFiles} />
          </label>
          <input
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder={attachments.length > 0 ? "MMS 설명(선택)…" : "메시지 입력…"}
            className="min-w-0 flex-1 bg-transparent py-2 text-sm focus:outline-none"
            maxLength={20_000}
          />
          <button
            type="submit"
            disabled={sending || (!text.trim() && attachments.length === 0 && !subject.trim())}
            aria-label={sending ? "전송 중" : "전송"}
            title={sending ? "전송 중…" : "전송"}
            className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-brand-gradient text-slate-950 shadow-glow transition hover:brightness-110 active:scale-95 disabled:opacity-40 disabled:shadow-none"
          >
            {sending ? (
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" className="animate-spin" aria-hidden>
                <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2.5" opacity="0.25" />
                <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
              </svg>
            ) : (
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden>
                <path d="M3.5 11.2L20 4l-4.8 16.5-3.9-6.3-7.8-3z" fill="currentColor" opacity="0.35" />
                <path d="M20 4L11.3 14.2M20 4l-7.8 16.5-3.9-6.3L3.5 11.2 20 4z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
              </svg>
            )}
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
      <a href={url} download={attachment.name} className="mt-2 block">
        <img src={url} alt={attachment.name} className="max-h-56 max-w-full rounded-xl" loading="lazy" />
      </a>
    );
  }
  return (
    <a
      href={url}
      download={attachment.name}
      className={`mt-2 inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs underline underline-offset-2 ${
        mine ? "text-white/80 hover:text-white" : "text-accent-tx hover:opacity-80"
      }`}
    >
      📎 {attachment.name}
    </a>
  );
}
