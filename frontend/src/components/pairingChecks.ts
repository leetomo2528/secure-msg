import { parsePairingQr, type PairingQrFields } from "../crypto/pairing";
import type { AccountDevice } from "../net/api";

export type PairingPayloadCheck =
  | { ok: true; parsed: PairingQrFields; subject: AccountDevice }
  | { ok: false; error: string };

/**
 * Everything the approver decides about a scanned QR before a pairing session
 * exists. The scan transports only what the NEW device chose to put in it, so
 * each rejection here is a security decision, not input validation; keeping it
 * pure is what lets every one of them be pinned by a test.
 */
export function checkPairingPayload(
  payload: string,
  devices: AccountDevice[],
  origin: string,
): PairingPayloadCheck {
  const parsed = parsePairingQr(payload);
  if (!parsed) {
    return { ok: false, error: "QR 내용을 읽을 수 없습니다. 새 기기 화면의 코드를 다시 스캔하세요." };
  }
  if (parsed.server !== origin) {
    return { ok: false, error: "다른 서버의 페어링 코드입니다. 같은 릴레이의 기기만 연결할 수 있습니다." };
  }
  const subject = devices.find((device) => device.sid === parsed.sid);
  if (!subject || subject.trust_state !== "pending") {
    return { ok: false, error: "이 코드에 해당하는 승인 대기 기기를 찾지 못했습니다." };
  }
  // The relay's own pending row is the authority on the subject's keys; a QR
  // claiming different ones is either stale or an attempt to swap in a key.
  if (subject.pub_key !== parsed.box_pk || subject.sig_pub !== parsed.sig_pk
    || subject.challenge !== parsed.challenge) {
    return { ok: false, error: "QR의 키가 서버에 등록된 대기 기기와 일치하지 않습니다. 승인하지 마세요." };
  }
  return { ok: true, parsed, subject };
}
