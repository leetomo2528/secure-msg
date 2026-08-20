/// <reference types="node" />
import { readFileSync } from "node:fs";
import { useStore } from "../store/useStore";

/**
 * Drive the shipped email-verified registration against a real relay.
 *
 * The relay under test runs with SECUREMSG_EMAIL_PROVIDER=console, which
 * appends each verification code to an outbox file instead of sending mail.
 * The test reads the code back from there, so the whole flow — Argon2id,
 * challenge issue, code verification, first device registration — runs for
 * real without a mail provider account.
 */
export function readLatestCode(outbox: string, recipient: string): string | null {
  let lines: string[];
  try {
    lines = readFileSync(outbox, "utf-8").split("\n").filter(Boolean);
  } catch {
    return null;
  }
  for (let i = lines.length - 1; i >= 0; i -= 1) {
    try {
      const record = JSON.parse(lines[i]) as { to?: string; code?: string };
      if (record.to === recipient && record.code) return record.code;
    } catch { /* skip a partially written line */ }
  }
  return null;
}

export async function registerViaEmail(
  outbox: string,
  username: string,
  password: string,
): Promise<boolean> {
  const email = `${username}@example.test`;
  const store = useStore.getState();
  const challengeId = await store.requestEmailRegistration(username, email, password);
  if (!challengeId) return false;
  const code = readLatestCode(outbox, email);
  if (!code) throw new Error(`no verification code was delivered for ${email}`);
  return await useStore.getState().verifyEmailRegistration(
    username, email, password, challengeId, code,
  );
}
