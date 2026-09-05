import { describe, expect, it } from "vitest";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import OnboardingPolicyNotice from "./OnboardingPolicyNotice";
import { ACCOUNT_RECOVERY_WARNING, NEW_DEVICE_HISTORY_WARNING, passwordResetInputError } from "./onboardingPolicy";

describe("onboarding security policy warnings", () => {
  it("points at the email password reset that the server actually implements", () => {
    expect(ACCOUNT_RECOVERY_WARNING).toContain("가입 때 인증한 이메일로 재설정할 수 있습니다");
  });

  it("still states that a reset does not recover past messages", () => {
    expect(ACCOUNT_RECOVERY_WARNING).toContain("메시지 암호화 키가 아니");
    expect(ACCOUNT_RECOVERY_WARNING).toContain("과거 메시지를 읽을 수는 없습니다");
  });

  it("never claims recovery is impossible while the reset flow ships", () => {
    // v0.10.6 added email recovery but left this text saying it did not exist,
    // one line above the screen's own "비밀번호를 잊으셨나요?" control.
    expect(ACCOUNT_RECOVERY_WARNING).not.toContain("복구 수단이 없습니다");
  });

  it("states the new-device history cutoff and the share path that recovers it", () => {
    expect(NEW_DEVICE_HISTORY_WARNING).toContain("기기 등록 이전 메시지를 복호화할 수 없습니다");
    expect(NEW_DEVICE_HISTORY_WARNING).toContain("‘이전 대화 공유’를 실행해야");
    expect(NEW_DEVICE_HISTORY_WARNING).toContain("암호화 백업 기능은 제공하지 않습니다");
  });

  it("never denies the existing-device transfer while it ships", () => {
    // v0.14.0 added ‘이전 대화 공유’ and the pending-device screen tells users
    // to run it, but this notice still said no such transfer existed.
    expect(NEW_DEVICE_HISTORY_WARNING).not.toContain("기존 기기 전송이나");
  });

  it("renders both warnings in the onboarding notice", () => {
    const html = renderToStaticMarkup(createElement(OnboardingPolicyNotice));
    expect(html).toContain('aria-label="계정 복구 및 새 기기 제한"');
    expect(html).toContain(ACCOUNT_RECOVERY_WARNING);
    expect(html).toContain(NEW_DEVICE_HISTORY_WARNING);
  });
});

describe("password reset input policy", () => {
  const CODE = "123456";
  const OK = "correct-horse";

  it("refuses a new password that would lock the account out of web login", () => {
    // The panel's button is type="button", so the input's minLength never runs;
    // the server stores whatever hash it gets, and login() then refuses an
    // empty password client-side.
    expect(passwordResetInputError("alice_92", CODE, "")).not.toBeNull();
    expect(passwordResetInputError("alice_92", CODE, "short7c")).not.toBeNull();
    expect(passwordResetInputError("alice_92", CODE, "a".repeat(1025))).not.toBeNull();
  });

  it("accepts a password at both ends of the allowed range", () => {
    expect(passwordResetInputError("alice_92", CODE, "12345678")).toBeNull();
    expect(passwordResetInputError("alice_92", CODE, "a".repeat(1024))).toBeNull();
  });

  it("refuses a username or code the server would reject anyway", () => {
    expect(passwordResetInputError("Alice_92", CODE, OK)).not.toBeNull();
    expect(passwordResetInputError("ab", CODE, OK)).not.toBeNull();
    expect(passwordResetInputError("alice_92", "12345", OK)).not.toBeNull();
    expect(passwordResetInputError("alice_92", "12345a", OK)).not.toBeNull();
  });
});
