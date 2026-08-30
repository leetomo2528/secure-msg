/**
 * History key sharing: grant another APPROVED device of this same account the
 * ability to read messages that were sent before it existed.
 *
 * A device is only ever addressed in envelopes created after it registered, so
 * a browser that just joined the account sees ciphertext it holds no key for.
 * An existing device fixes that by unwrapping its own copy of each message key
 * and re-sealing it for the new device — the relay only ever handles wrapped
 * keys, exactly as it does for a normal send.
 *
 * Three rules make this safe, and all are enforced here rather than by the
 * relay:
 *   - Every public key used (target and, for already-shared entries, the
 *     wrapper) comes from the locally pinned trust store. A relay that could
 *     name the target and supply its key would simply be asking us to encrypt
 *     history to a key it holds.
 *   - The target must still be ACTIVE in that store, not merely present in it.
 *     Pinning survives revocation on purpose (see TrustedDeviceRow), so the
 *     narrower active set is what authorizes a grant — otherwise a revoked,
 *     lost device would keep every past key re-sealed to it, and the sealed
 *     keys would reach the relay before its own 409 could reject them.
 *   - The run is bound to one SecurityContext and re-checks it across every
 *     await, so a logout, an account switch or a trust lock stops it instead
 *     of letting an invalidated session keep sharing.
 *
 * Driven entirely by the server's missing-keys list, so it is idempotent and
 * resumable: an interrupted run simply leaves work for the next one.
 */
import {
  api,
  type ConversationMembersResult,
  type ServerMessage,
  type ShareKeyEntry,
} from "../net/api";
import {
  rewrapMessageKey,
  type DeviceKeypair,
  type EnvelopeKey,
} from "../crypto/keys";
import { TrustViolationError } from "./db";
import {
  canUseCrypto,
  captureSecurityContext,
  lockForTrustViolation,
  ownActiveDeviceKeys,
  ownDeviceWrapperKeys,
  useStore,
  verifiedSenderPublicKey,
  verifyConversationKeyDirectory,
  type SecurityContext,
} from "./useStore";

export interface HistoryShareProgress {
  /** Conversations to visit; the count is known once the list is loaded. */
  conversationsTotal: number;
  conversationsDone: number;
  /** Message keys the server accepted for the target device. */
  shared: number;
  /** Messages this device could not open, or the server already had a key for. */
  skipped: number;
}

export interface HistoryShareOutcome extends HistoryShareProgress {
  ok: boolean;
  error?: string;
}

const MESSAGE_PAGE_SIZE = 200;
/** A logout, account switch or trust lock landing between two awaits. */
const CANCELLED = "세션이 변경되어 공유를 중단했습니다.";
/**
 * missing-keys answers with a bounded page (500 server-side), so one pass over
 * a long history needs several rounds. A round that adds nothing normally ends
 * the conversation first, but sequences this device cannot open stay in that
 * window forever and can crowd it, leaving only a handful of shareable ones
 * per round — so the cap is reachable on real data and running into it is
 * reported as an incomplete run, not as success.
 */
const MAX_ROUNDS_PER_CONVERSATION = 64;
/** The cap above was hit with the relay still listing shareable messages. */
const INCOMPLETE = "메시지가 많아 한 번에 다 공유하지 못했습니다. 다시 실행해 나머지를 공유하세요.";

/**
 * Share every readable past message with `targetSid`.
 *
 * Returns counts rather than throwing: a partial result is still useful, and
 * the next run picks up whatever remains.
 */
export async function shareHistoryWithDevice(
  targetSid: string,
  onProgress?: (progress: HistoryShareProgress) => void,
): Promise<HistoryShareOutcome> {
  const context = captureSecurityContext();
  const progress: HistoryShareProgress = {
    conversationsTotal: 0,
    conversationsDone: 0,
    shared: 0,
    skipped: 0,
  };
  const report = () => onProgress?.({ ...progress });
  const fail = (error: string): HistoryShareOutcome => ({ ...progress, ok: false, error });

  if (!canUseCrypto(context)) return fail("로그인 상태에서만 이전 대화를 공유할 수 있습니다.");
  const mySid = context.sid!;
  const myKeypair = context.keypair!;
  if (targetSid === mySid) return fail("현재 기기에는 이전 대화를 공유할 필요가 없습니다.");

  // Pinned keys only. An unpinned wrapper means this browser has not verified
  // that device itself, so a key it sealed is not evidence of anything.
  const pinnedKeys = await ownDeviceWrapperKeys(context);
  if (!canUseCrypto(context)) return fail(CANCELLED);
  // The target is held to the stricter test: still listed as active by the
  // newest verified directory. Pinned-but-revoked is exactly the lost-device
  // case, and re-sealing history to it is what revocation was meant to stop.
  const activeKeys = await ownActiveDeviceKeys(context);
  if (!canUseCrypto(context)) return fail(CANCELLED);
  const targetPubkey = activeKeys.get(targetSid);
  if (!targetPubkey) {
    return fail(pinnedKeys.has(targetSid)
      ? "폐기된 기기에는 이전 대화를 공유할 수 없습니다."
      : "이 브라우저가 확인한 기기 목록에 없는 기기입니다. 기기를 승인하고 기기 목록을 새로 고친 뒤 다시 시도하세요.");
  }

  // Straight from the relay rather than the rendered list: an empty store is
  // indistinguishable from a failed refresh, and reporting "0 shared, all
  // good" for a list we never got would be a lie.
  const list = await api.listConversations();
  if (!canUseCrypto(context)) return fail(CANCELLED);
  if (!list.ok || !Array.isArray(list.conversations)) {
    return fail(list.error ?? "대화 목록을 불러오지 못했습니다.");
  }
  const conversations = list.conversations as Array<{ cid: string }>;
  progress.conversationsTotal = conversations.length;
  report();

  let firstError: string | null = null;
  for (const conversation of conversations) {
    const cid = conversation.cid;
    if (!canUseCrypto(context)) return fail(CANCELLED);

    const members = await api.convMembers(cid);
    if (!canUseCrypto(context)) return fail(CANCELLED);
    if (!members.ok || !members.members) {
      firstError ??= members.error ?? "대화 기기 목록을 불러오지 못했습니다.";
      progress.conversationsDone += 1;
      report();
      continue;
    }
    try {
      await verifyConversationKeyDirectory(members, () => canUseCrypto(context));
      if (!canUseCrypto(context)) return fail(CANCELLED);
    } catch (error) {
      // Same fail-closed response as the sync path: a directory that does not
      // verify ends the whole session, not just this conversation.
      lockForTrustViolation(error, context);
      return fail(trustError(error));
    }

    // Sequences this device turned out not to be able to open, remembered for
    // the whole conversation. They are never posted, so the relay keeps
    // listing them in every later round's window: without this they would be
    // unwrapped again and counted again once per round, inflating the total
    // reported to the user by the number of rounds.
    const unshareable = new Set<number>();
    let round = 0;
    for (; round < MAX_ROUNDS_PER_CONVERSATION; round += 1) {
      const missing = await api.missingKeys(cid, targetSid);
      if (!canUseCrypto(context)) return fail(CANCELLED);
      if (!missing.ok || !missing.seqs) {
        firstError ??= missing.error ?? "공유할 메시지 목록을 불러오지 못했습니다.";
        break;
      }
      const wanted = new Set(missing.seqs.filter((seq) => !unshareable.has(seq)));
      if (wanted.size === 0) break; // only sequences already known to be unopenable

      const entries: ShareKeyEntry[] = [];
      const wantedSeqs = [...wanted];
      let cursor = Math.min(...wantedSeqs) - 1;
      const lastWanted = Math.max(...wantedSeqs);
      while (cursor < lastWanted) {
        const page = await api.fetchMessages(cid, cursor, MESSAGE_PAGE_SIZE);
        if (!canUseCrypto(context)) return fail(CANCELLED);
        if (!page.ok || !page.messages) {
          firstError ??= page.error ?? "메시지를 불러오지 못했습니다.";
          break;
        }
        if (page.messages.length === 0) break;
        let maxSeq = cursor;
        for (const message of page.messages) {
          maxSeq = Math.max(maxSeq, message.seq);
          if (!wanted.has(message.seq)) continue;
          const rewrapped = rewrapForTarget(
            message, members, mySid, myKeypair, pinnedKeys, targetPubkey, context,
          );
          if (rewrapped === TRUST_VIOLATION) return fail(useStore.getState().error ?? "키 디렉터리 검증 실패");
          if (!rewrapped) {
            // Not addressed to this device, or wrapped by a device this
            // browser has not pinned. Another device may still be able to
            // share it; leaving it missing is the honest outcome.
            unshareable.add(message.seq);
            continue;
          }
          entries.push({ seq: message.seq, ek: rewrapped.ek, n: rewrapped.n });
        }
        if (maxSeq <= cursor) break;
        cursor = maxSeq;
        if (page.messages.length < MESSAGE_PAGE_SIZE) break;
      }

      if (entries.length === 0) break; // nothing left this device can open
      const shared = await api.shareKeys(cid, targetSid, entries);
      if (!canUseCrypto(context)) return fail(CANCELLED);
      progress.shared += shared.added ?? 0;
      progress.skipped += shared.skipped ?? 0;
      report();
      if (!shared.ok) {
        firstError ??= shared.error ?? "이전 대화 키를 공유하지 못했습니다.";
        break;
      }
      // A round that adds nothing cannot make progress on the next one either.
      if ((shared.added ?? 0) === 0) break;
    }
    // Once per conversation: `unshareable` is a set, the rounds are not.
    progress.skipped += unshareable.size;
    // Only a loop that exhausted its rounds arrives with the counter at the
    // cap; every other exit above is a break, taken once the conversation is
    // genuinely finished. Work is then still pending unless the last round
    // happened to be the final one, and "possibly incomplete" has to be
    // reported as not ok: nothing else would ever prompt the missing re-run.
    if (round === MAX_ROUNDS_PER_CONVERSATION) firstError ??= INCOMPLETE;

    progress.conversationsDone += 1;
    report();
  }

  return { ...progress, ok: firstError == null, ...(firstError ? { error: firstError } : {}) };
}

/** Distinguishes "this message is not shareable" from "stop everything". */
const TRUST_VIOLATION = Symbol("trust-violation");

function rewrapForTarget(
  message: ServerMessage,
  members: ConversationMembersResult,
  mySid: string,
  myKeypair: DeviceKeypair,
  pinnedKeys: Map<string, string>,
  targetPubkey: string,
  context: SecurityContext,
): EnvelopeKey | null | typeof TRUST_VIOLATION {
  const mine = message.payload?.keys?.[mySid];
  if (!mine) return null;
  let openerPubkey: string | undefined;
  if (mine.by) {
    // Our own copy was itself shared by another device; it opens with THAT
    // device's key, and only a pinned one is acceptable.
    openerPubkey = pinnedKeys.get(mine.by);
  } else {
    try {
      openerPubkey = verifiedSenderPublicKey(
        members, message.sender_id, message.sender_sid, message.sender_pub_key,
      );
    } catch (error) {
      lockForTrustViolation(error, context);
      return TRUST_VIOLATION;
    }
  }
  if (!openerPubkey) return null;
  return rewrapMessageKey(message.payload, mySid, myKeypair, openerPubkey, targetPubkey);
}

function trustError(error: unknown): string {
  if (error instanceof TrustViolationError) return `보안 경고: ${error.message}`;
  return error instanceof Error ? error.message : "키 디렉터리를 검증하지 못했습니다.";
}
