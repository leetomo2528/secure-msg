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

export function isSafeMimeType(value: string): boolean {
  return /^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/.test(value)
    && value.length <= 120;
}

/** Exported for unit tests. Parses the decrypted relay JSON with hard limits. */
export function decodeRelayContent(value: string): RelayContent {
  try {
    const parsed = JSON.parse(value) as Partial<RelayContent>;
    if (parsed.v === 1 && (parsed.type === "text" || parsed.type === "mms")
      && typeof parsed.text === "string" && parsed.text.length <= 20_000) {
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
            || item.size > 512 * 1024
            || totalBytes + item.size > 512 * 1024) return false;
          try {
            if (unb64u(item.data).byteLength !== item.size) return false;
          } catch {
            return false;
          }
          totalBytes += item.size;
          return true;
        }).slice(0, 8);
      return {
        v: 1,
        type: parsed.type,
        text: parsed.text,
        subject: typeof parsed.subject === "string" ? parsed.subject.slice(0, 120) : undefined,
        attachments,
      };
    }
  } catch {
    // Legacy SMS rows were encrypted as plain text.
  }
  return { v: 1, type: "text", text: value.slice(0, 20_000), attachments: [] };
}
