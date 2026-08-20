import { describe, expect, it } from "vitest";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import OnboardingPolicyNotice from "./OnboardingPolicyNotice";
import { ACCOUNT_RECOVERY_WARNING, NEW_DEVICE_HISTORY_WARNING } from "./onboardingPolicy";

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

  it("states the new-device history cutoff and unavailable migration paths", () => {
    expect(NEW_DEVICE_HISTORY_WARNING).toContain("기기 등록 이전 메시지를 복호화할 수 없습니다");
    expect(NEW_DEVICE_HISTORY_WARNING).toContain("기존 기기 전송이나 암호화 백업 기능을 제공하지 않습니다");
  });

  it("renders both warnings in the onboarding notice", () => {
    const html = renderToStaticMarkup(createElement(OnboardingPolicyNotice));
    expect(html).toContain('aria-label="계정 복구 및 새 기기 제한"');
    expect(html).toContain(ACCOUNT_RECOVERY_WARNING);
    expect(html).toContain(NEW_DEVICE_HISTORY_WARNING);
  });
});
