interface ConversationLike {
  name: string;
  members: string[];
}

export function normalizePhone(value: string): string {
  return value.trim().replace(/[\s().-]/g, "").replace(/^00/, "+");
}

/** Only a self-only conversation may be interpreted as a carrier SMS thread. */
export function ownedSmsPhone(
  conversation: ConversationLike,
  username: string | null,
): string | null {
  if (!username || conversation.members.length !== 1 || conversation.members[0] !== username) {
    return null;
  }
  const phone = normalizePhone(conversation.name);
  return /^\+?[0-9*#]{3,24}$/.test(phone) ? phone : null;
}
