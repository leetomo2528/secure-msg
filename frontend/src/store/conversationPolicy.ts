interface ConversationLike {
  name: string;
  members: string[];
}

export function normalizePhone(value: string): string {
  const compact = value.trim().replace(/[\s().-]/g, "").replace(/^00/, "+");
  const koreanNational = koreanNationalPart(compact);
  return koreanNational === null ? compact : `+82${koreanNational}`;
}

/**
 * Korean conversation identities use an E.164-like +82 form. The accepted
 * local shapes are 02 + 7/8 digits and 0xx + 7/8 digits. Bare 82 is considered
 * a country code only for those plausible Korean lengths, keeping short codes,
 * star/hash service codes, and non-Korean international numbers unchanged.
 */
function koreanNationalPart(value: string): string | null {
  let national: string;
  if (value.startsWith("+82")) national = value.slice(3).replace(/^0/, "");
  else if (value.startsWith("82")) national = value.slice(2).replace(/^0/, "");
  else if (value.startsWith("0")) national = value.slice(1);
  else return null;

  return /^(?:2[0-9]{7,8}|50[0-9]{9}|[1-9][0-9]{8,9})$/.test(national) ? national : null;
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
