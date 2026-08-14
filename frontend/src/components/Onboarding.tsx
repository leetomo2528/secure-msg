import { useEffect, useState } from "react";
import { useStore } from "../store/useStore";
import { api } from "../net/api";
import { hashPassword, saltForUser } from "../crypto/keys";
import BrandMark from "./BrandMark";
import OnboardingPolicyNotice from "./OnboardingPolicyNotice";
import { Segmented } from "./ui";

type Mode = "login" | "register";

export default function Onboarding() {
  const { login, requestEmailRegistration, verifyEmailRegistration, forgetLocalDevice, error, username: rememberedUsername } = useStore();
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
      if (!ok) { /* error already in store */ }
    } finally {
      setBusy(false);
    }
  };

  const requestReset = async () => {
    setResetMessage(null);
    const result = await api.passwordResetRequest(resetUsername.trim().toLowerCase(), resetEmail.trim().toLowerCase());
    if (result.ok) {
      setResetMessage("계정이 존재하면 이메일로 인증 코드를 보냈습니다.");
      setResetChallenge(result.challenge_id ?? null);
    } else setResetMessage(result.error ?? "재설정 메일을 보내지 못했습니다.");
  };

  const confirmReset = async () => {
    if (!resetChallenge || resetChallenge === "pending") {
      setResetMessage("먼저 인증 메일을 요청하세요.");
      return;
    }
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
  };

  return (
    <div className="onboarding-shell relative min-h-full overflow-y-auto px-5 py-6 sm:px-8 lg:px-12">
      <div
        aria-hidden
        className="onboarding-orb onboarding-orb-a pointer-events-none absolute"
      />
      <div aria-hidden className="onboarding-orb onboarding-orb-b pointer-events-none absolute" />

      <header className="relative mx-auto flex max-w-6xl items-center justify-between">
        <div className="flex items-center gap-3">
          <BrandMark className="h-10 w-10 rounded-[14px]" />
          <div>
            <p className="text-sm font-bold tracking-tight text-white">Secure Msg</p>
            <p className="mt-0.5 text-[10px] font-medium uppercase tracking-[0.18em] text-white/45">Private by default</p>
          </div>
        </div>
        <div className="hidden items-center gap-2 rounded-full border border-white/10 bg-white/[0.04] px-3 py-1.5 text-[10px] font-medium text-white/55 sm:flex">
          <span className="h-1.5 w-1.5 rounded-full bg-brand-teal shadow-[0_0_10px_rgba(45,212,191,.9)]" />
          E2E 보호 활성화
        </div>
      </header>

      <main className="relative mx-auto grid max-w-6xl items-center gap-12 py-12 lg:min-h-[calc(100vh-100px)] lg:grid-cols-[1.05fr_.95fr] lg:gap-20 lg:py-8">
        <section className="max-w-xl animate-rise">
          <p className="mb-5 inline-flex items-center gap-2 text-[11px] font-semibold uppercase tracking-[0.2em] text-brand-teal">
            <span className="h-px w-7 bg-brand-teal/70" />
            Your messages. Your keys.
          </p>
          <h1 className="max-w-lg text-4xl font-semibold leading-[1.08] tracking-[-0.04em] text-white sm:text-5xl lg:text-[4.2rem]">
            대화의 열쇠를
            <span className="block text-brand-teal">당신이 보관하세요.</span>
          </h1>
          <p className="mt-6 max-w-md text-sm leading-7 text-white/60 sm:text-base">
            전화번호와 이메일 없이 시작하는 자가호스팅 메신저입니다. 메시지는 기기에서 암호화되고, 서버는 평문을 볼 수 없습니다.
          </p>
          <div className="mt-9 grid max-w-md gap-3 sm:grid-cols-3">
            {["기기 내 암호화", "개인키 미전송", "자가호스팅"].map((label, index) => (
              <div key={label} className="rounded-2xl border border-white/10 bg-white/[0.045] px-3 py-3.5">
                <span className="text-xs font-semibold text-brand-teal">0{index + 1}</span>
                <p className="mt-2 text-[11px] font-medium leading-4 text-white/70">{label}</p>
              </div>
            ))}
          </div>
        </section>

        <form onSubmit={submit} className="onboarding-card w-full max-w-md justify-self-center space-y-5 animate-rise lg:justify-self-end">
          <div>
            <p className="text-lg font-semibold tracking-tight text-white">안전하게 시작하기</p>
            <p className="mt-1 text-xs text-white/45">계정과 첫 암호화 기기를 이 브라우저에 연결합니다.</p>
          </div>

          <Segmented
            options={[
              { value: "login", label: "로그인" },
              { value: "register", label: "회원가입" },
            ]}
            value={mode}
            onChange={setMode}
          />

          <OnboardingPolicyNotice />

          <div className="space-y-2">
            <label className="form-label" htmlFor="ob-username">아이디 (3-20자, 영소문자/숫자/_)</label>
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
              required
            />
          </div>

          {mode === "register" && (
            <div className="space-y-2">
              <label className="form-label" htmlFor="ob-email">인증 이메일</label>
              <input id="ob-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" className="field" placeholder="you@example.com" required />
              {registrationChallenge && <input value={registrationCode} onChange={(e) => setRegistrationCode(e.target.value)} inputMode="numeric" maxLength={6} className="field" placeholder="이메일로 받은 6자리 코드" required />}
              {registrationChallenge && <p className="text-[11px] text-brand-teal">인증 코드가 전송되었습니다. 코드를 입력하고 가입을 완료하세요.</p>}
            </div>
          )}

          <div className="space-y-2">
            <label className="form-label" htmlFor="ob-password">
              비밀번호 (8자 이상)
            </label>
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
            <p className="text-[11px] leading-relaxed text-white/40">
              비밀번호는 브라우저에서 Argon2id로 해시됩니다. 원본은 서버로 전송되지 않습니다.
            </p>
          </div>

          {error && (
            <div className="rounded-xl bg-red-500/10 px-3.5 py-2.5 text-xs text-red-200 ring-1 ring-red-400/30 animate-rise">
              {error}
            </div>
          )}

          <button type="submit" disabled={busy} className="btn-primary w-full !py-3.5">
            {busy ? "처리 중…" : mode === "login" ? "로그인" : registrationChallenge ? "이메일 인증 및 가입" : "인증 메일 보내기"}
          </button>

          {mode === "login" && <button type="button" onClick={() => setResetOpen((open) => !open)} className="mx-auto block text-[11px] text-white/60 transition hover:text-white">비밀번호를 잊으셨나요?</button>}

          {resetOpen && (
            <div className="space-y-3 rounded-2xl border border-white/10 bg-white/[0.04] p-4 animate-rise">
              <p className="text-sm font-semibold text-white">비밀번호 재설정</p>
              <p className="text-[11px] leading-relaxed text-white/50">가입 때 인증한 이메일로 6자리 코드를 보냅니다.</p>
              <input value={resetUsername} onChange={(e) => setResetUsername(e.target.value)} className="field" placeholder="아이디" />
              <input type="email" value={resetEmail} onChange={(e) => setResetEmail(e.target.value)} className="field" placeholder="인증 이메일" />
              {resetChallenge && <input value={resetCode} onChange={(e) => setResetCode(e.target.value)} inputMode="numeric" maxLength={6} className="field" placeholder="6자리 인증 코드" />}
              {resetChallenge && <input type="password" value={resetPassword} onChange={(e) => setResetPassword(e.target.value)} minLength={8} className="field" placeholder="새 비밀번호 (8자 이상)" />}
              {resetMessage && <p className="text-[11px] leading-relaxed text-white/65">{resetMessage}</p>}
              <button type="button" onClick={() => void (resetChallenge ? confirmReset() : requestReset())} className="btn-ghost w-full !py-2.5 text-xs">{resetChallenge ? "비밀번호 변경" : "인증 코드 받기"}</button>
            </div>
          )}

          <p className="text-center text-[11px] leading-relaxed text-white/40">
            가입하면 암호화 키쌍이 이 브라우저의 IndexedDB에 생성됩니다.
          </p>
          {rememberedUsername && (
            <button
              type="button"
              onClick={async () => {
                if (!confirm("이 브라우저의 개인키와 로컬 메시지를 삭제합니다. 서버의 기기 등록은 다른 기기에서 폐기해야 합니다. 계속할까요?")) return;
                await forgetLocalDevice();
                setUsername("");
                setPassword("");
              }}
              className="mx-auto block text-[11px] text-red-300/70 transition hover:text-red-200"
            >
              이 브라우저의 로컬 기기 초기화
            </button>
          )}
        </form>
      </main>
    </div>
  );
}
