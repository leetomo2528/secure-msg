import { useEffect, useState } from "react";
import { useStore } from "../store/useStore";
import { api } from "../net/api";
import { hashPassword, saltForUser } from "../crypto/keys";
import BrandMark from "./BrandMark";
import OnboardingPolicyNotice from "./OnboardingPolicyNotice";
import { Segmented } from "./ui";

type Mode = "login" | "register";

export default function Onboarding() {
  const login = useStore((s) => s.login);
  const requestEmailRegistration = useStore((s) => s.requestEmailRegistration);
  const verifyEmailRegistration = useStore((s) => s.verifyEmailRegistration);
  const forgetLocalDevice = useStore((s) => s.forgetLocalDevice);
  const error = useStore((s) => s.error);
  const rememberedUsername = useStore((s) => s.username);
  const [mode, setMode] = useState<Mode>("login");
  const [username, setUsername] = useState(rememberedUsername ?? "");
  const [password, setPassword] = useState("");
  const [email, setEmail] = useState("");
  const [registrationChallenge, setRegistrationChallenge] = useState<string | null>(null);
  const [registrationCode, setRegistrationCode] = useState("");
  const [resetOpen, setResetOpen] = useState(false);
  const [resetUsername, setResetUsername] = useState("");
  const [resetEmail, setResetEmail] = useState("");
  const [resetCode, setResetCode] = useState("");
  const [resetChallenge, setResetChallenge] = useState<string | null>(null);
  const [resetPassword, setResetPassword] = useState("");
  const [resetMessage, setResetMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [resetBusy, setResetBusy] = useState(false);

  useEffect(() => {
    if (rememberedUsername) setUsername(rememberedUsername);
  }, [rememberedUsername]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    try {
      const normalizedUsername = username.trim().toLowerCase();
      const ok = mode === "login"
        ? await login(normalizedUsername, password)
        : registrationChallenge
          ? await verifyEmailRegistration(normalizedUsername, email.trim().toLowerCase(), password, registrationChallenge, registrationCode.trim())
          : Boolean(await requestEmailRegistration(normalizedUsername, email.trim().toLowerCase(), password).then((challenge) => {
              setRegistrationChallenge(challenge);
              return challenge;
            }));
      void ok; // a false result already put the error text in the store
    } finally {
      setBusy(false);
    }
  };

  const requestReset = async () => {
    if (resetBusy) return;
    setResetBusy(true);
    try {
      setResetMessage(null);
      const result = await api.passwordResetRequest(resetUsername.trim().toLowerCase(), resetEmail.trim().toLowerCase());
      if (result.ok) {
        setResetMessage("계정이 존재하면 이메일로 인증 코드를 보냈습니다.");
        setResetChallenge(result.challenge_id ?? null);
      } else setResetMessage(result.error ?? "재설정 메일을 보내지 못했습니다.");
    } finally {
      setResetBusy(false);
    }
  };

  const confirmReset = async () => {
    if (resetBusy) return;
    if (!resetChallenge || resetChallenge === "pending") {
      setResetMessage("먼저 인증 메일을 요청하세요.");
      return;
    }
    setResetBusy(true);
    try {
      const pwHash = await hashPassword(resetPassword, saltForUser(resetUsername.trim().toLowerCase()));
      const result = await api.passwordResetConfirm(
        resetUsername.trim().toLowerCase(), resetEmail.trim().toLowerCase(), resetChallenge, resetCode.trim(), pwHash,
      );
      setResetMessage(result.ok ? "비밀번호가 변경되었습니다. 로그인해 주세요." : result.error ?? "인증 코드가 올바르지 않습니다.");
      if (result.ok) {
        setResetOpen(false);
        setResetChallenge(null);
        setPassword("");
      }
    } finally {
      setResetBusy(false);
    }
  };

  const openReset = () => {
    setResetOpen((open) => {
      // Carry the ID already typed above so the panel does not ask for it twice.
      if (!open && !resetUsername) setResetUsername(username.trim().toLowerCase());
      return !open;
    });
  };

  return (
    <div className="onboarding-shell grid min-h-full place-items-center overflow-y-auto px-5 py-10">
      <div className="flex w-full max-w-[400px] flex-col items-center gap-6 animate-rise">
        <div className="flex items-center gap-2.5">
          <BrandMark className="h-8 w-8 rounded-[11px]" />
          <p className="text-base font-bold tracking-tight text-tx-1">SecureMsg</p>
        </div>

        <form onSubmit={submit} className="onboarding-card w-full space-y-4">
          <Segmented
            tone="surface"
            options={[
              { value: "login", label: "로그인" },
              { value: "register", label: "회원가입" },
            ]}
            value={mode}
            onChange={setMode}
          />

          {mode === "register" && (
            <div className="space-y-2">
              <label className="form-label" htmlFor="ob-email">이메일</label>
              <input
                id="ob-email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="email"
                className="field"
                placeholder="you@example.com"
                required
              />
              {registrationChallenge && (
                <>
                  <input
                    value={registrationCode}
                    onChange={(e) => setRegistrationCode(e.target.value)}
                    inputMode="numeric"
                    maxLength={6}
                    className="field"
                    placeholder="이메일로 받은 6자리 코드"
                    required
                  />
                  <p className="text-[11px] text-accent-tx">인증 코드를 보냈습니다. 코드를 입력하면 가입이 완료됩니다.</p>
                </>
              )}
            </div>
          )}

          <div className="space-y-2">
            <label className="form-label" htmlFor="ob-username">아이디</label>
            <input
              id="ob-username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              pattern="^[a-z0-9_]{3,20}$"
              maxLength={20}
              autoComplete="username"
              className="field"
              placeholder="alice_92"
              title="영소문자·숫자·밑줄 3~20자"
              required
            />
            {mode === "register" && (
              <p className="text-[11px] text-tx-4">영소문자·숫자·밑줄 3~20자</p>
            )}
          </div>

          <div className="space-y-2">
            <div className="flex items-baseline justify-between gap-2">
              <label className="form-label" htmlFor="ob-password">비밀번호</label>
              {mode === "login" && (
                <button
                  type="button"
                  onClick={openReset}
                  aria-expanded={resetOpen}
                  className="text-[11px] text-accent-tx transition hover:opacity-80"
                >
                  재설정
                </button>
              )}
            </div>
            <input
              id="ob-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={mode === "register" ? "new-password" : "current-password"}
              className="field"
              minLength={mode === "register" ? 8 : 1}
              maxLength={1024}
              required
            />
            {mode === "register" && (
              <p className="text-[11px] text-tx-4">8자 이상</p>
            )}
          </div>

          {error && (
            <div className="rounded-xl bg-danger-tx/10 px-3.5 py-2.5 text-xs text-danger-tx ring-1 ring-danger-tx/25 animate-rise">
              {error}
            </div>
          )}

          <button type="submit" disabled={busy} className="btn-primary w-full !py-3.5">
            {busy ? "처리 중…" : mode === "login" ? "로그인" : registrationChallenge ? "이메일 인증 및 가입" : "인증 메일 보내기"}
          </button>

          {mode === "login" && resetOpen && (
            <div className="space-y-3 rounded-xl bg-fg/[0.04] p-4 animate-rise">
              <p className="text-[11px] leading-relaxed text-tx-3">가입 때 인증한 이메일로 6자리 코드를 보냅니다.</p>
              <input value={resetUsername} onChange={(e) => setResetUsername(e.target.value)} className="field" placeholder="아이디" />
              <input type="email" value={resetEmail} onChange={(e) => setResetEmail(e.target.value)} className="field" placeholder="인증 이메일" />
              {resetChallenge && <input value={resetCode} onChange={(e) => setResetCode(e.target.value)} inputMode="numeric" maxLength={6} className="field" placeholder="6자리 인증 코드" />}
              {resetChallenge && <input type="password" value={resetPassword} onChange={(e) => setResetPassword(e.target.value)} minLength={8} className="field" placeholder="새 비밀번호 (8자 이상)" />}
              {resetMessage && <p className="text-[11px] leading-relaxed text-tx-2">{resetMessage}</p>}
              <button type="button" disabled={resetBusy} onClick={() => void (resetChallenge ? confirmReset() : requestReset())} className="btn-ghost w-full !py-2.5 text-xs disabled:opacity-40">
                {resetBusy ? "처리 중…" : resetChallenge ? "비밀번호 변경" : "인증 코드 받기"}
              </button>
            </div>
          )}
        </form>

        <p className="flex items-center gap-1.5 text-[11px] text-tx-4">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden className="shrink-0">
            <rect x="4" y="10" width="16" height="10" rx="2" stroke="currentColor" strokeWidth="2" />
            <path d="M8 10V7a4 4 0 0 1 8 0v3" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
          메시지는 이 기기에서 암호화됩니다. 서버는 평문을 보지 못합니다.
        </p>

        {mode === "register" && <OnboardingPolicyNotice />}

        {rememberedUsername && (
          <button
            type="button"
            onClick={async () => {
              if (!confirm("이 브라우저의 개인키와 로컬 메시지를 삭제합니다. 서버의 기기 등록은 다른 기기에서 폐기해야 합니다. 계속할까요?")) return;
              await forgetLocalDevice();
              setUsername("");
              setPassword("");
            }}
            className="text-[11px] text-tx-4 underline underline-offset-2 transition hover:text-danger-tx"
          >
            이 브라우저의 로컬 기기 초기화
          </button>
        )}
      </div>
    </div>
  );
}
