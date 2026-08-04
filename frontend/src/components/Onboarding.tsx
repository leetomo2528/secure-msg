import { useEffect, useState } from "react";
import { useStore } from "../store/useStore";

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
    <div className="h-full flex items-center justify-center px-6">
      <form onSubmit={submit} className="w-full max-w-sm space-y-4">
        <div className="text-center space-y-1">
          <h1 className="text-2xl font-bold text-cyan-400">Secure Msg</h1>
          <p className="text-xs text-slate-400 leading-relaxed">
            자가호스팅 E2E 메신저. 전화번호·이메일 없이 임의 아이디만 사용합니다.
            서버 관리자도 메시지를 읽을 수 없습니다.
          </p>
        </div>

        <div className="flex rounded-lg border border-slate-700 overflow-hidden">
          <button
            type="button"
            onClick={() => setMode("login")}
            className={`flex-1 py-2 text-sm ${mode === "login" ? "bg-cyan-500/20 text-cyan-300" : "text-slate-400"}`}
          >
            로그인
          </button>
          <button
            type="button"
            onClick={() => setMode("register")}
            className={`flex-1 py-2 text-sm ${mode === "register" ? "bg-cyan-500/20 text-cyan-300" : "text-slate-400"}`}
          >
            회원가입
          </button>
        </div>

        <div className="space-y-2">
          <label className="block text-xs text-slate-400">아이디 (3-20자, 영소문자/숫자/_)</label>
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            pattern="^[a-z0-9_]{3,20}$"
            maxLength={20}
            autoComplete="username"
            className="w-full rounded-lg border border-slate-700 px-3 py-2 text-sm focus:outline-none focus:border-cyan-500"
            placeholder="alice_92"
            required
          />
        </div>

        <div className="space-y-2">
          <label className="block text-xs text-slate-400">
            비밀번호 (8자 이상, 영문·숫자·특수문자 자유롭게 조합 가능)
          </label>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === "register" ? "new-password" : "current-password"}
            className="w-full rounded-lg border border-slate-700 px-3 py-2 text-sm focus:outline-none focus:border-cyan-500"
            minLength={mode === "register" ? 8 : 1}
            maxLength={1024}
            required
          />
          <p className="text-[10px] text-slate-500">
            비밀번호는 브라우저에서 Argon2id로 해시한 후 서버로 전송됩니다. 서버는 원본 비밀번호를 절대 받지 않습니다.
          </p>
        </div>

        {error && (
          <div className="text-xs text-red-400 bg-red-500/10 border border-red-500/30 rounded px-3 py-2">
            {error}
          </div>
        )}

        <button
          type="submit"
          disabled={busy}
          className="w-full rounded-lg bg-cyan-500 hover:bg-cyan-400 disabled:opacity-50 text-slate-900 font-medium py-2.5 text-sm"
        >
          {busy ? "처리 중…" : mode === "login" ? "로그인" : "가입 및 첫 기기 등록"}
        </button>

        <p className="text-[10px] text-slate-500 text-center leading-relaxed">
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
            className="w-full text-[10px] text-red-400/80 hover:text-red-300"
          >
            이 브라우저의 로컬 기기 초기화
          </button>
        )}
      </form>
    </div>
  );
}
