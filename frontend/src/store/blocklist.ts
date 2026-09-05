/**
 * Blocklist (keyword filter) — applied AFTER decryption, BEFORE display.
 *
 * Privacy boundary: account block rules are synchronized through the relay and
 * cached in IndexedDB, so the server can see rule strings. Message plaintext is
 * never included in that synchronization; matching still happens locally after
 * decryption.
 *
 * Blocking semantics: a decrypted message is hidden from the UI (and marked
 * `blocked=true` in IndexedDB) when ANY keyword in the list is a substring of
 * the plaintext. Match is case-insensitive, no regex (avoids ReDoS / injection).
 */
import { type BlockRow } from "./db";

export interface BlockMatchResult {
  blocked: boolean;
  matched?: string;
}

export function matchBlockKeywords(
  plaintext: string,
  kws: BlockRow[],
): BlockMatchResult {
  const lower = plaintext.normalize("NFKC").toLowerCase();
  for (const k of kws) {
    const keyword = k.keyword.trim().normalize("NFKC").toLowerCase();
    if (keyword && lower.includes(keyword)) {
      return { blocked: true, matched: k.keyword };
    }
  }
  return { blocked: false };
}
