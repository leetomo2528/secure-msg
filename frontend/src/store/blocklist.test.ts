import { describe, expect, it } from "vitest";
import { matchBlockKeywords } from "./blocklist";
import type { BlockRow } from "./db";

const keyword = (value: string): BlockRow => ({
  id: value,
  keyword: value,
  created_at: 0,
});

describe("matchBlockKeywords", () => {
  it("matches case-insensitively", () => {
    expect(matchBlockKeywords("Limited SPAM offer", [keyword("spam")]).blocked).toBe(true);
  });

  it("normalizes compatibility characters", () => {
    expect(matchBlockKeywords("무료 ＣＯＵＰＯＮ 도착", [keyword("coupon")]).blocked).toBe(true);
  });

  it("does not let an empty keyword block everything", () => {
    expect(matchBlockKeywords("ordinary message", [keyword("   ")]).blocked).toBe(false);
  });
});
