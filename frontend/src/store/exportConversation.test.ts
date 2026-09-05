import { describe, expect, it } from "vitest";
import { buildConversationExport } from "./exportConversation";
import type { MessageRow } from "./db";

const MY_SID = "sid-me";
const AT = new Date("2026-03-14T08:09:10.000Z");

function row(overrides: Partial<MessageRow> = {}): MessageRow {
  return {
    id: "sms-1:1",
    seq: 1,
    cid: "sms-1",
    sender_id: 1,
    sender_sid: MY_SID,
    plaintext: "안녕하세요",
    created_at: Date.UTC(2026, 2, 14, 1, 2, 3),
    ...overrides,
  };
}

function csvBody(rows: MessageRow[]): string {
  return buildConversationExport(rows, MY_SID, "+821012345678", "csv", AT).body;
}

function csvCells(rows: MessageRow[]): string[] {
  return csvBody(rows).split("\n").slice(1);
}

describe("CSV export", () => {
  it("prefixes an apostrophe to every cell a spreadsheet would run as a formula", () => {
    for (const payload of ["=1+1", "+82", "-2", "@SUM(A1)", "\tcmd", "\rcmd"]) {
      const [line] = csvCells([row({ plaintext: payload })]);
      expect(line).toContain(`"'${payload}"`);
    }
  });

  it("leaves ordinary and Korean text unprefixed", () => {
    const [line] = csvCells([row({ plaintext: "엄마 오늘 몇시에 와요?" })]);
    expect(line).toContain('"엄마 오늘 몇시에 와요?"');
    expect(line).not.toContain("'엄마");
  });

  it("escapes the subject the same way as the body", () => {
    const [line] = csvCells([row({ subject: "=HYPERLINK(1)", plaintext: "본문" })]);
    expect(line).toContain(`"'=HYPERLINK(1)"`);
  });

  it("doubles embedded quotes and keeps newlines inside the quoted cell", () => {
    const body = csvBody([row({ plaintext: 'he said "hi"\nsecond line' })]);
    expect(body).toContain('"he said ""hi""\nsecond line"');
    // The header plus one record: the embedded newline must not start a row.
    expect(body.split("\n")).toHaveLength(3);
  });

  it("starts with a BOM so Excel reads Korean as UTF-8", () => {
    expect(csvBody([row()]).startsWith("\uFEFF")).toBe(true);
  });

  it("labels direction by sender, not by order", () => {
    const [mine, theirs] = csvCells([row(), row({ seq: 2, sender_sid: "sid-peer" })]);
    expect(mine).toContain(",sent,");
    expect(theirs).toContain(",received,");
  });

  it("omits blocked rows", () => {
    const body = csvBody([row(), row({ seq: 2, plaintext: "스팸", blocked: true })]);
    expect(body).not.toContain("스팸");
    expect(body.split("\n")).toHaveLength(2);
  });
});

describe("JSON export", () => {
  it("omits blocked rows and reports the surviving count", () => {
    const out = buildConversationExport(
      [row(), row({ seq: 2, plaintext: "스팸", blocked: true })],
      MY_SID, "엄마", "json", AT,
    );
    const parsed = JSON.parse(out.body);
    expect(parsed.count).toBe(1);
    expect(parsed.messages).toHaveLength(1);
    expect(parsed.messages[0]).toMatchObject({ seq: 1, mine: true, text: "안녕하세요" });
    expect(parsed.conversation).toBe("엄마");
  });

  it("defaults content_type to text and keeps carrier status", () => {
    const out = buildConversationExport(
      [row({ carrier_status: "delivered" })], MY_SID, "엄마", "json", AT,
    );
    const parsed = JSON.parse(out.body);
    expect(parsed.messages[0].content_type).toBe("text");
    expect(parsed.messages[0].carrier_status).toBe("delivered");
  });
});

describe("export filename", () => {
  it("keeps Korean letters and digits, replacing everything else", () => {
    const out = buildConversationExport([], MY_SID, "엄마 (집)", "csv", AT);
    expect(out.filename).toBe("securemsg-엄마__집_-2026-03-14.csv");
  });

  it("keeps a phone identity readable and matches the format", () => {
    expect(buildConversationExport([], MY_SID, "+821012345678", "csv", AT).filename)
      .toBe("securemsg-+821012345678-2026-03-14.csv");
    expect(buildConversationExport([], MY_SID, "+821012345678", "json", AT).filename)
      .toBe("securemsg-+821012345678-2026-03-14.json");
  });

  it("never lets a title escape into a path", () => {
    const out = buildConversationExport([], MY_SID, "../../etc/passwd", "csv", AT);
    expect(out.filename).not.toContain("/");
    expect(out.filename).not.toContain("..");
  });
});
