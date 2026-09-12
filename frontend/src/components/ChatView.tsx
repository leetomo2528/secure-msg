import { useEffect, useMemo, useRef, useState } from "react";
import { useStore } from "../store/useStore";
import type { MessageAttachment } from "../store/db";
import { Avatar } from "./ChatList";
import {
  conversationDisplayName,
  isInlineImage,
  messageDirection,
  MAX_ATTACHMENTS,
} from "../store/helpers";
import { prepareAttachments } from "../store/imageCodec";
import { COMPOSE_ATTACHMENT_BUDGET } from "../store/imageShrink";
import { ownedSmsPhone } from "../store/conversationPolicy";
import { buildConversationExport, downloadText } from "../store/exportConversation";

export default function ChatView({ cid }: { cid: string }) {
  const activeMessages = useStore((s) => s.activeMessages);
  const conversations = useStore((s) => s.conversations);
  const sendContent = useStore((s) => s.sendContent);
  const sid = useStore((s) => s.sid);
  // The account, not just this browser: a message from another account is a
  // definite "received" even when no direction was ever recorded on it.
  const uid = useStore((s) => s.uid);
  const username = useStore((s) => s.username);
  const [text, setText] = useState("");
  const [subject, setSubject] = useState("");
  const [attachments, setAttachments] = useState<MessageAttachment[]>([]);
  const [sending, setSending] = useState(false);
  const [processing, setProcessing] = useState(false);
  const [attachmentError, setAttachmentError] = useState<string | null>(null);
  const [lightbox, setLightbox] = useState<MessageAttachment | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const conversation = conversations.find((item) => item.cid === cid);
  const title = conversationDisplayName(conversation);
  // Merely having a `name` is not the SMS test: a renamed group chat has one
  // and nothing routes it through the carrier.
  const isSms = Boolean(conversation && ownedSmsPhone(conversation, username));
  const [renaming, setRenaming] = useState(false);
  const [nameDraft, setNameDraft] = useState("");
  const [exportMenu, setExportMenu] = useState(false);
  const renameConversation = useStore((s) => s.renameConversation);
  const stagedBytes = attachments.reduce((sum, item) => sum + item.size, 0);

  const startRename = () => {
    setNameDraft(conversation?.name ?? "");
    setRenaming(true);
  };

  const commitRename = async () => {
    setRenaming(false);
    const next = nameDraft.trim().slice(0, 100);
    if (!next || next === conversation?.name) return;
    await renameConversation(cid, next);
  };

  const exportMessages = (format: "csv" | "json") => {
    setExportMenu(false);
    const file = buildConversationExport(activeMessages, sid, uid, title, format);
    downloadText(file.filename, file.mime, file.body);
  };

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [activeMessages.length]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (sending || processing) return;
    if (!text.trim() && attachments.length === 0 && !subject.trim()) return;
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
        // Otherwise a red line about the photo that was refused stays over a
        // composer that has since sent something else successfully.
        setAttachmentError(null);
      }
    } finally {
      setSending(false);
    }
  };

  /**
   * Read, re-encode and stage picked files.
   *
   * The order matters and is the opposite of what it used to be: files are
   * decoded and shrunk FIRST, and the budget is then checked against the bytes
   * that were actually produced. Checking File.size up front is what made a
   * phone photo unsendable — it is the size before the resize that never ran.
   */
  const ingest = async (picked: File[]) => {
    if (!picked.length || processing) return;
    setAttachmentError(null);
    setProcessing(true);
    try {
      // `attachments` here is the value this handler closed over. Re-reading it
      // inside the updater below is what keeps two overlapping picks from both
      // validating against the same stale snapshot.
      const result = await prepareAttachments(picked, attachments);
      setAttachments((current) => {
        const room = MAX_ATTACHMENTS - current.length;
        if (result.attachments.length > room) {
          setAttachmentError(`첨부파일은 최대 ${MAX_ATTACHMENTS}개까지 가능합니다`);
          return current;
        }
        return [...current, ...result.attachments];
      });
      if (result.errors.length) setAttachmentError(result.errors.join(" · "));
    } finally {
      setProcessing(false);
    }
  };

  const chooseFiles = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(event.target.files ?? []);
    event.target.value = "";
    await ingest(picked);
  };

  /**
   * A screenshot on the clipboard is the most common way to want a picture in
   * a message on a desktop, and pasting one did nothing at all before.
   */
  const pasteFiles = (event: React.ClipboardEvent) => {
    const picked = Array.from(event.clipboardData?.files ?? []);
    if (!picked.length) return;
    // A copy out of a spreadsheet or a mail client puts a rendered image in
    // `files` ALONGSIDE the text, so files alone is not the test. Text wins:
    // swallowing what someone meant to paste as text is worse than ignoring a
    // picture they can still attach with the button.
    if (event.clipboardData?.getData("text/plain")) return;
    event.preventDefault();
    if (processing) {
      // preventDefault already ran, so staying silent would drop the paste
      // with no cue at all — unlike the file inputs, which visibly disable.
      setAttachmentError("이전 사진을 처리하는 중입니다. 잠시 후 다시 붙여넣어 주세요");
      return;
    }
    void ingest(picked);
  };

  return (
    <div className="family-chat flex h-full flex-col">
      <div className="family-chat-header flex items-center gap-3 border-b border-fg/5 bg-night-soft/70 px-3 py-2.5 backdrop-blur">
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
          {renaming ? (
            <input
              autoFocus
              value={nameDraft}
              onChange={(e) => setNameDraft(e.target.value)}
              onBlur={() => void commitRename()}
              onKeyDown={(e) => {
                if (e.key === "Enter") { e.preventDefault(); void commitRename(); }
                if (e.key === "Escape") setRenaming(false);
              }}
              maxLength={100}
              className="w-48 rounded-lg bg-fg/[0.05] px-2 py-1 text-sm font-semibold text-tx-1 ring-1 ring-accent-tx/40 focus:outline-none"
              aria-label="대화 이름 변경"
            />
          ) : (
            <div className="truncate text-sm font-semibold text-tx-1">{title}</div>
          )}
          <div className="text-[10px] text-tx-4">
            {isSms ? "SMS · Android 게이트웨이" : "E2E 암호화"}
          </div>
        </div>
        <div className="ml-auto flex items-center gap-1">
          <button
            type="button"
            onClick={startRename}
            title="대화 이름 변경"
            aria-label="대화 이름 변경"
            className="grid h-8 w-8 place-items-center rounded-lg text-tx-4 ring-1 ring-fg/10 transition hover:bg-fg/[0.06] hover:text-tx-1"
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden>
              <path d="M4 20l4.5-.9L20 7.6a2 2 0 0 0-2.8-2.8L5.7 16.3 4 20z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
            </svg>
          </button>
          <div className="relative">
            <button
              type="button"
              onClick={() => setExportMenu((v) => !v)}
              title="대화 내보내기"
              aria-label="대화 내보내기"
              className="grid h-8 w-8 place-items-center rounded-lg text-tx-4 ring-1 ring-fg/10 transition hover:bg-fg/[0.06] hover:text-tx-1"
            >
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden>
                <path d="M12 4v10m0 0l-4-4m4 4l4-4M5 19h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
            {exportMenu && (
              <div className="absolute right-0 top-9 z-30 w-36 overflow-hidden rounded-xl bg-night-soft shadow-bubble ring-1 ring-fg/10 animate-rise">
                <button
                  type="button"
                  onClick={() => exportMessages("csv")}
                  className="block w-full px-3 py-2 text-left text-xs text-tx-2 transition hover:bg-fg/[0.06]"
                >CSV로 내보내기</button>
                <button
                  type="button"
                  onClick={() => exportMessages("json")}
                  className="block w-full px-3 py-2 text-left text-xs text-tx-2 transition hover:bg-fg/[0.06]"
                >JSON으로 내보내기</button>
              </div>
            )}
          </div>
        </div>
      </div>

      <div ref={scrollRef} className="family-message-surface flex-1 space-y-2.5 overflow-y-auto px-4 py-5">
        {activeMessages.length === 0 && (
          <div className="pt-14 text-center text-xs leading-relaxed text-tx-4">
            메시지가 없습니다.
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
          // "unknown" renders like a received message rather than guessing a
          // side, but the carrier line below still shows, so a misplaced row
          // stays recognisable instead of hiding the evidence.
          const mine = messageDirection(m, sid, uid) === "out";
          return (
            <div key={`${m.cid}:${m.seq}`} className={`flex animate-rise ${mine ? "justify-end" : "justify-start"}`}>
              <div
                className={`family-message-bubble max-w-[78%] whitespace-pre-wrap break-words rounded-2xl px-4 py-2.5 text-sm leading-relaxed shadow-bubble ${
                  mine
                    ? "rounded-br-md bg-gradient-to-br from-teal-500 to-sky-600 text-white"
                    : "rounded-bl-md bg-fg/[0.06] text-tx-1 ring-1 ring-fg/[0.06]"
                }`}
              >
                {m.content_type === "mms" && m.subject && <div className="mb-1 font-semibold">{m.subject}</div>}
                {m.plaintext && <div>{m.plaintext}</div>}
                {m.attachments?.map((attachment, index) => (
                  <AttachmentPreview
                    key={`${m.cid}:${m.seq}:${index}:${attachment.name}`}
                    attachment={attachment}
                    mine={mine}
                    onOpen={() => setLightbox(attachment)}
                  />
                ))}
                <div className={`mt-1.5 text-[10px] tabular-nums ${mine ? "text-white/60" : "text-tx-4"}`}>
                  {new Date(m.created_at).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}
                  {m.carrier_status && m.carrier_status !== "none" && ` · ${carrierLabel(m.carrier_status)}`}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <form onSubmit={submit} className="family-composer space-y-2 border-t border-fg/5 bg-night-soft/70 p-3 backdrop-blur">
        {(attachments.length > 0 || subject) && (
          <div className="flex items-center gap-2">
            <input
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              placeholder="MMS 제목(선택)"
              className="field flex-1 !py-1.5 text-xs"
              maxLength={120}
            />
            <span className="shrink-0 rounded-full bg-fg/[0.04] px-2.5 py-1 text-[10px] tabular-nums text-tx-3 ring-1 ring-fg/[0.06]">
              첨부 {attachments.length}개 · {Math.round(stagedBytes / 1024)}/{COMPOSE_ATTACHMENT_BUDGET / 1024}KB
            </span>
          </div>
        )}
        {attachmentError && <div className="text-[11px] text-danger-tx">{attachmentError}</div>}
        {processing && (
          <div className="text-[11px] text-tx-3">사진을 줄이는 중…</div>
        )}
        {attachments.length > 0 && (
          <div className="flex gap-1.5 overflow-x-auto pb-0.5">
            {attachments.map((attachment, index) => (
              <button
                key={`${attachment.name}-${index}`}
                type="button"
                onClick={() => setAttachments((current) => current.filter((_, i) => i !== index))}
                className="group flex shrink-0 items-center gap-1.5 rounded-full bg-fg/[0.05] py-1 pl-1 pr-3 text-[11px] text-tx-2 ring-1 ring-fg/10 transition hover:bg-red-500/10 hover:text-red-500 hover:ring-red-400/30"
                title="첨부 제거"
              >
                {isInlineImage(attachment.content_type) ? (
                  <img
                    src={dataUrl(attachment)}
                    alt=""
                    className="h-6 w-6 rounded-full object-cover"
                  />
                ) : (
                  <span className="grid h-6 w-6 place-items-center rounded-full bg-fg/[0.06]">📎</span>
                )}
                <span className="max-w-[9rem] truncate">{attachment.name}</span>
                <span className="tabular-nums text-tx-4">{Math.round(attachment.size / 1024)}KB</span>
                <span aria-hidden>×</span>
              </button>
            ))}
          </div>
        )}
        <div className="flex items-center gap-2 rounded-2xl bg-fg/[0.04] px-2 py-1.5 ring-1 ring-fg/10 transition focus-within:ring-2 focus-within:ring-accent-tx/40">
          {/*
            Two pickers, not one. `accept="image/*"` is what makes the iOS
            Photos picker hand over a JPEG instead of a HEIC no browser but
            Safari can decode, and it puts the phone's camera roll first — but
            it would also hide every non-image, so the paperclip stays.
          */}
          <label
            className={`grid h-9 w-9 shrink-0 place-items-center rounded-full text-tx-3 transition hover:bg-fg/[0.06] hover:text-accent-tx ${
              processing ? "cursor-default opacity-40" : "cursor-pointer"
            }`}
            title="사진 첨부"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden>
              <rect x="3" y="5" width="18" height="14" rx="2.5" stroke="currentColor" strokeWidth="1.7" />
              <circle cx="8.5" cy="10" r="1.6" stroke="currentColor" strokeWidth="1.5" />
              <path d="M4 16.5l4.8-4.2 3.6 3.2 3-2.6L20 16.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <input type="file" multiple accept="image/*" className="hidden" disabled={processing} onChange={chooseFiles} />
          </label>
          <label
            className={`grid h-9 w-9 shrink-0 place-items-center rounded-full text-tx-3 transition hover:bg-fg/[0.06] hover:text-accent-tx ${
              processing ? "cursor-default opacity-40" : "cursor-pointer"
            }`}
            title="파일 첨부"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden>
              <path
                d="M20 11.5l-8.2 8.2a5.5 5.5 0 0 1-7.8-7.8l8.5-8.5a3.7 3.7 0 0 1 5.2 5.2l-8.5 8.5a1.8 1.8 0 0 1-2.6-2.6l7.8-7.8"
                stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"
              />
            </svg>
            <input type="file" multiple className="hidden" disabled={processing} onChange={chooseFiles} />
          </label>
          <input
            value={text}
            onChange={(e) => setText(e.target.value)}
            onPaste={pasteFiles}
            placeholder={attachments.length > 0 ? "MMS 설명(선택)…" : "메시지 입력…"}
            className="min-w-0 flex-1 bg-transparent py-2 text-sm focus:outline-none"
            maxLength={20_000}
          />
          <button
            type="submit"
            disabled={sending || processing || (!text.trim() && attachments.length === 0 && !subject.trim())}
            aria-label={sending ? "전송 중" : "전송"}
            title={sending ? "전송 중…" : "전송"}
            className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-brand-gradient text-white shadow-glow transition hover:brightness-110 active:scale-95 disabled:opacity-40 disabled:shadow-none"
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

      {lightbox && <Lightbox attachment={lightbox} onClose={() => setLightbox(null)} />}
    </div>
  );
}

/**
 * Full-size view for a received photo.
 *
 * Tapping a picture used to start a download, which on a phone means the image
 * disappears into Files and the thread scrolls away. The overlay keeps the
 * conversation where it is and leaves saving as a deliberate second choice.
 */
function Lightbox({ attachment, onClose }: { attachment: MessageAttachment; onClose: () => void }) {
  const url = useMemo(() => dataUrl(attachment), [attachment]);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div
      className="fixed inset-0 z-50 flex flex-col bg-black/90 backdrop-blur-sm"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label={attachment.name}
    >
      <div className="flex items-center justify-between gap-3 px-4 py-3 text-xs text-white/70">
        <span className="min-w-0 truncate">{attachment.name}</span>
        <div className="flex shrink-0 items-center gap-3">
          <a
            href={url}
            download={attachment.name}
            onClick={(e) => e.stopPropagation()}
            className="rounded-lg px-2 py-1 ring-1 ring-white/20 transition hover:bg-white/10 hover:text-white"
          >저장</a>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className="rounded-lg px-2 py-1 ring-1 ring-white/20 transition hover:bg-white/10 hover:text-white"
          >닫기</button>
        </div>
      </div>
      <div className="flex flex-1 items-center justify-center overflow-auto p-4">
        <img
          src={url}
          alt={attachment.name}
          onClick={(e) => e.stopPropagation()}
          className="max-h-full max-w-full object-contain"
        />
      </div>
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

function AttachmentPreview({
  attachment, mine, onOpen,
}: { attachment: MessageAttachment; mine: boolean; onOpen: () => void }) {
  // ChatView re-renders on every composer keystroke, and rebuilding this
  // re-pads up to 512 KiB of base64 per attachment and hands the browser a
  // fresh `src` each time.
  const url = useMemo(() => dataUrl(attachment), [attachment]);
  // Render inline images only for a fixed safe whitelist. A generic MIME
  // pattern would let remote-controlled content pick exotic image types.
  if (isInlineImage(attachment.content_type)) {
    return (
      <button type="button" onClick={onOpen} className="mt-2 block" title="크게 보기">
        <img src={url} alt={attachment.name} className="max-h-56 max-w-full rounded-xl" loading="lazy" />
      </button>
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
