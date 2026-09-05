import { describe, expect, it } from "vitest";
import { encodePairingQr, type PairingQrFields } from "../crypto/pairing";
import type { AccountDevice } from "../net/api";
import { checkPairingPayload } from "./pairingChecks";

const CHALLENGE = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE";
const BOX_PK = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI";
const SIG_PK = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM";
const NONCE_NEW = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ";
const OTHER_KEY = "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU";

const ORIGIN = "https://msg.example.com";
const SUBJECT_SID = "web_ABCDEF12";

function qr(overrides: Partial<PairingQrFields> = {}): string {
  return encodePairingQr({
    v: 1,
    type: "securemsg-pairing",
    server: ORIGIN,
    username: "yunje",
    sid: SUBJECT_SID,
    challenge: CHALLENGE,
    box_pk: BOX_PK,
    sig_pk: SIG_PK,
    nonce_new: NONCE_NEW,
    expires_at: 1_750_000_000,
    ...overrides,
  });
}

function device(overrides: Partial<AccountDevice> = {}): AccountDevice {
  return {
    sid: SUBJECT_SID,
    name: "새 브라우저",
    kind: "web",
    pub_key: BOX_PK,
    sig_pub: SIG_PK,
    trust_state: "pending",
    challenge: CHALLENGE,
    created_at: 1_750_000_000,
    last_seen: 1_750_000_000,
    ...overrides,
  };
}

function reject(payload: string, devices: AccountDevice[] = [device()]): string {
  const check = checkPairingPayload(payload, devices, ORIGIN);
  expect(check.ok).toBe(false);
  return check.ok ? "" : check.error;
}

describe("approver-side pairing QR checks", () => {
  it("accepts a QR that matches the relay's own pending row", () => {
    const check = checkPairingPayload(qr(), [device()], ORIGIN);
    expect(check.ok).toBe(true);
    if (!check.ok) return;
    expect(check.subject.sid).toBe(SUBJECT_SID);
    expect(check.parsed.nonce_new).toBe(NONCE_NEW);
  });

  it("rejects a payload it cannot parse", () => {
    expect(reject("not json at all")).toContain("QR 내용을 읽을 수 없습니다");
  });

  it("rejects a code minted for another relay", () => {
    expect(reject(qr({ server: "https://evil.example.com" })))
      .toContain("다른 서버의 페어링 코드입니다");
  });

  it("rejects a subject the relay never listed", () => {
    expect(reject(qr(), [device({ sid: "web_99999999" })]))
      .toContain("승인 대기 기기를 찾지 못했습니다");
  });

  it("rejects a subject that is not pending", () => {
    for (const state of ["approved", "rejected", "revoked"] as const) {
      expect(reject(qr(), [device({ trust_state: state })]))
        .toContain("승인 대기 기기를 찾지 못했습니다");
    }
  });

  it("rejects a QR naming a box key the pending row does not have", () => {
    expect(reject(qr({ box_pk: OTHER_KEY })))
      .toContain("QR의 키가 서버에 등록된 대기 기기와 일치하지 않습니다");
  });

  it("rejects a QR naming a signing key the pending row does not have", () => {
    expect(reject(qr({ sig_pk: OTHER_KEY })))
      .toContain("QR의 키가 서버에 등록된 대기 기기와 일치하지 않습니다");
  });

  it("rejects a QR whose two keys are swapped against the pending row", () => {
    // Cross-comparing box_pk against sig_pub would still find both values
    // present and approve the swap, so the pairing must be per-field.
    expect(reject(qr({ box_pk: SIG_PK, sig_pk: BOX_PK })))
      .toContain("QR의 키가 서버에 등록된 대기 기기와 일치하지 않습니다");
  });

  it("rejects a QR carrying a challenge other than the pending one", () => {
    expect(reject(qr({ challenge: OTHER_KEY })))
      .toContain("QR의 키가 서버에 등록된 대기 기기와 일치하지 않습니다");
  });

  it("rejects a pending row the relay sent without a challenge", () => {
    expect(reject(qr(), [device({ challenge: undefined })]))
      .toContain("QR의 키가 서버에 등록된 대기 기기와 일치하지 않습니다");
  });
});
