export const ACCOUNT_RECOVERY_WARNING =
  "비밀번호는 가입 때 인증한 이메일로 재설정할 수 있습니다. 다만 비밀번호는 메시지 암호화 키가 아니므로, 재설정해도 새 기기에서 과거 메시지를 읽을 수는 없습니다.";

export const NEW_DEVICE_HISTORY_WARNING =
  "새 브라우저·새 설치는 기기 등록 이전 메시지를 복호화할 수 없습니다. 이미 승인된 기존 기기에서 ‘이전 대화 공유’를 실행해야 지난 메시지를 읽을 수 있으며, 암호화 백업 기능은 제공하지 않습니다.";

/**
 * The reset panel submits with a type="button" click, so the inputs' minLength
 * never runs constraint validation: an empty new password hashed fine and the
 * server stored it (it only sees the Argon2id hash), while login() refuses a
 * zero-length password client-side — the account came back unusable from the
 * web until another reset. Username and code mirror auth.py's rules so a typo
 * fails here instead of spending one of the 10/min reset-confirm tokens.
 */
export function passwordResetInputError(username: string, code: string, password: string): string | null {
  if (!/^[a-z0-9_]{3,20}$/.test(username)) return "아이디는 영소문자·숫자·_ 3~20자로 입력하세요";
  if (!/^[0-9]{6}$/.test(code)) return "이메일로 받은 6자리 숫자 코드를 입력하세요";
  if (password.length < 8 || password.length > 1024) return "비밀번호는 8~1,024자로 입력하세요";
  return null;
}
