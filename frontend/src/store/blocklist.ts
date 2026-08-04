/**
 * Blocklist (keyword filter) — applied AFTER decryption, BEFORE display.
 *
 * Privacy property: the keyword list lives ONLY in IndexedDB. It is never sent
 * to the server. The server has no way to learn what's filtered.
 *
 * Blocking semantics: a decrypted message is hidden from the UI (and marked
 * `blocked=true` in IndexedDB) when ANY keyword in the list is a substring of
 * the plaintext. Match is case-insensitive, no regex (avoids ReDoS / injection).
 */
import { listBlockKeywords, setBlocked, type BlockRow } from "./db";

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

export async function shouldBlock(plaintext: string): Promise<BlockMatchResult> {
  return matchBlockKeywords(plaintext, await listBlockKeywords());
}

/**
 * Apply blocklist to a freshly decrypted message. If blocked, persist the
 * `blocked` flag in IndexedDB so the UI hides it on next render. Returns true
 * if the message should be displayed.
 */
export async function applyBlock(
  cid: string,
  seq: number,
  plaintext: string,
): Promise<boolean> {
  const result = await shouldBlock(plaintext);
  await setBlocked(cid, seq, result.blocked);
  return !result.blocked;
}
