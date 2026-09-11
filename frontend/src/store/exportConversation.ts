/**
 * Conversation export format. Kept out of ChatView because the CSV escaping
 * has to neutralize attacker-controlled SMS text, and closed over component
 * state it could not be tested at all.
 */
import type { MessageRow } from "./db";
import { messageDirection } from "./helpers";

const DIRECTION_LABEL = { out: "sent", in: "received", unknown: "unknown" } as const;

export interface ConversationExport {
  filename: string;
  mime: string;
  body: string;
}

/**
 * Quote-escape cells, and neutralize spreadsheet formula injection: SMS text
 * is attacker-controlled, so a leading =+-@ (or tab/CR) would execute as a
 * formula when the export is opened in Excel/Sheets.
 */
function esc(value: string): string {
  return `"${(/^[=+\-@\t\r]/.test(value) ? `'${value}` : value).replace(/"/g, '""')}"`;
}

export function buildConversationExport(
  rows: MessageRow[],
  mySid: string | null,
  title: string,
  format: "csv" | "json",
  now: Date = new Date(),
): ConversationExport {
  // Blocked rows were never shown; exporting them would hand back exactly the
  // content the blocklist exists to suppress.
  const visible = rows.filter((m) => !m.blocked);
  const stamp = now.toISOString().slice(0, 10);
  // Keep unicode letters/digits (Korean names) in the filename.
  const base = `securemsg-${title.replace(/[^\p{L}\p{N}_+\-]/gu, "_")}-${stamp}`;
  if (format === "json") {
    const messages = visible.map((m) => ({
      seq: m.seq,
      // Three-valued on purpose. A row the gateway relayed before direction
      // was recorded is genuinely unclassifiable, and an export that calls it
      // "sent" states something nobody knows. `mine` stays for compatibility
      // with exports already taken, and answers the narrower question.
      direction: messageDirection(m, mySid),
      mine: messageDirection(m, mySid) === "out",
      text: m.plaintext,
      subject: m.subject ?? undefined,
      content_type: m.content_type ?? "text",
      carrier_status: m.carrier_status,
      created_at: new Date(m.created_at).toISOString(),
    }));
    return {
      filename: `${base}.json`,
      mime: "application/json",
      body: JSON.stringify({
        conversation: title, exported_at: now.toISOString(), count: messages.length,
        messages,
      }, null, 2),
    };
  }
  const lines = [
    ["seq", "direction", "subject", "text", "carrier_status", "created_at"].join(","),
    ...visible.map((m) => [
      String(m.seq),
      DIRECTION_LABEL[messageDirection(m, mySid)],
      esc(m.subject ?? ""),
      esc(m.plaintext),
      m.carrier_status ?? "",
      new Date(m.created_at).toISOString(),
    ].join(",")),
  ];
  return {
    filename: `${base}.csv`,
    mime: "text/csv;charset=utf-8",
    // BOM so Excel reads UTF-8 (Korean) correctly.
    body: "\uFEFF" + lines.join("\n"),
  };
}

export function downloadText(filename: string, mime: string, body: string): void {
  const blob = new Blob([body], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  // Some browsers (notably Safari/iOS PWA) abort the download if the URL is
  // revoked before the download actually starts.
  setTimeout(() => URL.revokeObjectURL(url), 1_000);
}
