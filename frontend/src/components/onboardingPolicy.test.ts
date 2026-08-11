import { describe, expect, it } from "vitest";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import OnboardingPolicyNotice from "./OnboardingPolicyNotice";
import { ACCOUNT_RECOVERY_WARNING, NEW_DEVICE_HISTORY_WARNING } from "./onboardingPolicy";

describe("onboarding security policy warnings", () => {
  it("states that password reset and account recovery do not exist", () => {
    expect(ACCOUNT_RECOVERY_WARNING).toContain("비밀번호 재설정·계정 복구 수단이 없습니다");
    expect(ACCOUNT_RECOVERY_WARNING).toContain("세션은 만료 전까지 동작할 수");
    expect(ACCOUNT_RECOVERY_WARNING).toContain("세션 만료 후에는 다시 로그인할 수 없습니다");
  });

  it("states the new-device history cutoff and unavailable migration paths", () => {
    expect(NEW_DEVICE_HISTORY_WARNING).toContain("기기 등록 이전 메시지를 복호화할 수 없습니다");
    expect(NEW_DEVICE_HISTORY_WARNING).toContain("기존 기기 전송이나 암호화 백업 기능을 제공하지 않습니다");
  });

  it("renders both warnings in the pre-login onboarding notice", () => {
    const html = renderToStaticMarkup(createElement(OnboardingPolicyNotice));
    expect(html).toContain('aria-label="계정 복구 및 새 기기 제한"');
    expect(html).toContain(ACCOUNT_RECOVERY_WARNING);
    expect(html).toContain(NEW_DEVICE_HISTORY_WARNING);
  });
});
