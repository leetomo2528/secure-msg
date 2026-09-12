/**
 * Pure helpers extracted from useStore.ts — no zustand dependency,
 * individually unit-testable.
 */
import type { BlockRule } from "../net/api";
import { unb64u } from "../crypto/keys";
import type { BlockRow, SenderRow, MessageAttachment } from "./db";
import type { RelayContent } from "./useStore";
import { normalizePhone } from "./conversationPolicy";

export interface DisplayableConversation {
  name: string;
  members: string[];
  synced_contact_name?: string | null;
}

/**
 * A synchronized phone-book label is presentation data only. `name` remains
 * the stable SMS phone/conversation identity used by routing code.
 */
export function conversationDisplayName(
  conversation: DisplayableConversation | null | undefined,
  fallback = "대화",
): string {
  if (!conversation) return fallback;
  const contactName = conversation.synced_contact_name?.trim();
  if (contactName) return contactName;
  const identity = conversation.name.trim();
  if (identity) return identity;
  const members = conversation.members.join(", ").trim();
  return members || fallback;
}

export function ruleToKeywordRow(rule: BlockRule): BlockRow {
  return { id: `srv:${rule.id}`, keyword: rule.value, created_at: rule.created_at * 1000 };
}

export function ruleToSenderRow(rule: BlockRule): SenderRow {
  return { id: `srv:${rule.id}`, sender: rule.value, created_at: rule.created_at * 1000 };
}

export function matchesBlockedSender(phone: string, senders: SenderRow[]): boolean {
  const canonical = normalizePhone(phone).normalize("NFKC").trim().toLowerCase();
  if (!canonical) return false;
  for (const row of senders) {
    const blockedCanonical = normalizePhone(row.sender)
      .normalize("NFKC").trim().toLowerCase();
    if (canonical === blockedCanonical) return true;
  }
  return false;
}

export function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * Relay content limits.
 *
 * Three layers enforce them: the file picker refuses at attach time, the send
 * path refuses at send time, and decodeRelayContent silently DROPS anything
 * over the line on receipt. That last one is why they cannot be per-file
 * literals — a cap raised in the sender and not the decoder turns into
 * attachments that vanish on the other device with no error anywhere.
 */
export const MAX_ATTACHMENTS = 8;
export const MAX_ATTACHMENT_BYTES = 512 * 1024;
export const MAX_TEXT_CHARS = 20_000;
export const MAX_SUBJECT_CHARS = 120;

export function isSafeMimeType(value: string): boolean {
  return /^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/.test(value)
    && value.length <= 120;
}

/**
 * Types rendered as a picture rather than as a download link.
 *
 * A fixed whitelist, not a generic `image/*` test: the content is relayed from
 * whoever sent the SMS, and a wildcard would let them pick an exotic image
 * type the browser treats as something else. Kept here rather than in the view
 * because the conversation-list preview has to agree with the bubble about
 * what counts as a photo.
 */
export function isInlineImage(mime: string | null | undefined): boolean {
  return /^image\/(png|jpe?g|gif|webp|bmp)$/i.test((mime ?? "").split(";")[0].trim());
}

/**
 * What a message with no text of its own says in a preview or a notification.
 *
 * An all-photo message used to read "(첨부파일)", which is what a PDF says too.
 */
export function attachmentPreviewLabel(
  attachments: readonly { content_type: string }[] | null | undefined,
): string {
  if (!attachments?.length) return "";
  if (!attachments.every((item) => isInlineImage(item.content_type))) return "(첨부파일)";
  return attachments.length === 1 ? "사진" : `사진 ${attachments.length}장`;
}

/**
 * Direction inferred from the sending client's idempotency key.
 *
 * The Android gateway mints `in_<sha256 prefix>` for anything the carrier
 * delivered and a UUID for a message it sent itself, so the two shapes are a
 * function of which code path created the row. A UUID cannot start with `in_`
 * (it holds no `i`, `n` or `_`), so the two tests cannot both match.
 *
 * This value rides OUTSIDE the sealed envelope, so the relay could rewrite it.
 * It is a display hint and a way to classify history that predates the sealed
 * `dir` field — never a security boundary. Anything unrecognised stays null so
 * the caller renders it neutrally instead of guessing a side.
 */
export function directionFromMid(mid: string | null | undefined): "in" | "out" | null {
  if (!mid) return null;
  if (mid.startsWith("in_")) return "in";
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(mid)) return "out";
  return null;
}

/**
 * Which side of the thread a stored message belongs on.
 *
 * `sender_sid` alone cannot answer this for an SMS thread — the gateway relays
 * the owner's own texts under the same sid as the peer's — so a stored
 * `direction` comes first. That field is only ever recorded for this account's
 * own messages: a conversation peer seals `dir: "out"` into their sends too,
 * and taking theirs at face value would move every one of their messages onto
 * our side of the thread.
 *
 * Beyond that, an account other than ours is a definite sender, while our own
 * other device is not: it is our account but not this browser, which is why
 * "unknown" stays a real answer. It renders on the neutral side and must never
 * be exported as if we knew.
 */
export function messageDirection(
  message: { direction?: "in" | "out"; sender_sid: string; sender_id?: number },
  mySid: string | null,
  myUid?: number | null,
): "in" | "out" | "unknown" {
  if (message.direction === "in" || message.direction === "out") return message.direction;
  if (mySid && message.sender_sid === mySid) return "out";
  if (myUid != null && message.sender_id != null && message.sender_id !== myUid) return "in";
  return "unknown";
}

/**
 * The direction to STORE for a relayed row, or undefined to store none.
 *
 * Both inputs are only meaningful for our own account. A conversation peer
 * seals `dir: "out"` into their sends exactly as we do, and their browser mints
 * UUID relay ids exactly as ours does, so either one read without checking the
 * sender would put every message they send on our side of the thread — and
 * silence its notification with it. Their messages are left unrecorded; the
 * reader classifies them from the account id instead.
 */
export function recordedDirection(
  sealed: "in" | "out" | undefined,
  clientMid: string | null | undefined,
  senderId: number,
  myUid: number | null | undefined,
): "in" | "out" | undefined {
  if (myUid == null || senderId !== myUid) return undefined;
  return sealed ?? directionFromMid(clientMid) ?? undefined;
}

/** Exported for unit tests. Parses the decrypted relay JSON with hard limits. */
export function decodeRelayContent(value: string): RelayContent {
  try {
    const parsed = JSON.parse(value) as Partial<RelayContent>;
    if (parsed.v === 1 && (parsed.type === "text" || parsed.type === "mms")
      && typeof parsed.text === "string" && parsed.text.length <= MAX_TEXT_CHARS) {
      let totalBytes = 0;
      const candidates = Array.isArray(parsed.attachments) ? parsed.attachments.slice(0, 64) : [];
      const attachments = candidates
        .filter((item): item is MessageAttachment => {
          if (!item || typeof item !== "object"
            || typeof item.name !== "string"
            || typeof item.content_type !== "string"
            || !isSafeMimeType(item.content_type)
            || typeof item.data !== "string"
            || typeof item.size !== "number"
            || !Number.isInteger(item.size)
            || item.size < 0
            || item.size > MAX_ATTACHMENT_BYTES
            || totalBytes + item.size > MAX_ATTACHMENT_BYTES) return false;
          try {
            if (unb64u(item.data).byteLength !== item.size) return false;
          } catch {
            return false;
          }
          totalBytes += item.size;
          return true;
        }).slice(0, MAX_ATTACHMENTS);
      return {
        v: 1,
        type: parsed.type,
        text: parsed.text,
        subject: typeof parsed.subject === "string" ? parsed.subject.slice(0, MAX_SUBJECT_CHARS) : undefined,
        // Optional, and deliberately NOT a version bump: both decoders reject
        // anything but v:1 and fall through to rendering the raw JSON as the
        // message body, so a v:2 would turn every message into gibberish on a
        // client that has not updated. An unknown key is simply ignored there.
        dir: parsed.dir === "in" || parsed.dir === "out" ? parsed.dir : undefined,
        attachments,
      };
    }
  } catch {
    // Legacy SMS rows were encrypted as plain text.
  }
  return { v: 1, type: "text", text: value.slice(0, MAX_TEXT_CHARS), attachments: [] };
}
