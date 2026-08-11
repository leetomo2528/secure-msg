import { useEffect, useState } from "react";
import { useStore } from "../store/useStore";
import BrandMark from "./BrandMark";
import OnboardingPolicyNotice from "./OnboardingPolicyNotice";
import { Segmented } from "./ui";

type Mode = "login" | "register";

export default function Onboarding() {
  const { login, register, forgetLocalDevice, error, username: rememberedUsername } = useStore();
  const [mode, setMode] = useState<Mode>("login");
  const [username, setUsername] = useState(rememberedUsername ?? "");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (rememberedUsername) setUsername(rememberedUsername);
  }, [rememberedUsername]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    try {
      const ok = mode === "login"
        ? await login(username.trim().toLowerCase(), password)
        : await register(username.trim().toLowerCase(), password);
      if (!ok) { /* error already in store */ }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="relative grid h-full place-items-center overflow-y-auto px-6 py-10">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0"
        style={{ background: "radial-gradient(640px 400px at 50% 14%, rgba(45, 212, 191, 0.13), transparent 70%)" }}
      />
      <form onSubmit={submit} className="relative w-full max-w-sm space-y-5 animate-rise">
        <div className="space-y-3 text-center">
          <div className="flex justify-center"><BrandMark className="h-14 w-14 rounded-2xl" /></div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-tx-1">Secure Msg</h1>
            <p className="mt-2 text-xs leading-relaxed text-tx-3">
              자가호스팅 E2E 메신저. 전화번호·이메일 없이 임의 아이디만 사용합니다.
              서버 관리자도 메시지를 읽을 수 없습니다.
            </p>
          </div>
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

        <div className="space-y-2">
          <label className="form-label" htmlFor="ob-password">
            비밀번호 (8자 이상, 영문·숫자·특수문자 자유롭게 조합 가능)
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
          <p className="text-[10px] leading-relaxed text-tx-4">
            비밀번호는 브라우저에서 Argon2id로 해시한 후 서버로 전송됩니다. 서버는 원본 비밀번호를 절대 받지 않습니다.
          </p>
        </div>

        {error && (
          <div className="rounded-xl bg-red-500/10 px-3.5 py-2.5 text-xs text-danger-tx ring-1 ring-red-400/30 animate-rise">
            {error}
          </div>
        )}

        <button type="submit" disabled={busy} className="btn-primary w-full !py-3">
          {busy ? "처리 중…" : mode === "login" ? "로그인" : "가입 및 첫 기기 등록"}
        </button>

        <p className="text-center text-[10px] leading-relaxed text-tx-4">
          가입 시 이 기기의 암호화 키쌍이 브라우저(IndexedDB)에 생성됩니다.
          개인키는 서버로 절대 전송되지 않습니다.
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
            className="mx-auto block text-[10px] text-danger-tx/70 transition hover:text-danger-tx"
          >
            이 브라우저의 로컬 기기 초기화
          </button>
        )}
      </form>
    </div>
  );
}
