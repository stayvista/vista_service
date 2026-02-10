import { FormEvent, useMemo, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { apiPost } from "../api/client";
import { AuthLoginData, getAuthUser, setAuthSession } from "../auth/session";

type ApiError = { code?: string; message?: string };

type AuthMode = "login" | "register";

export function LoginPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const requestedMode = params.get("mode");
  const [mode, setMode] = useState<AuthMode>(requestedMode === "register" ? "register" : "login");
  const [email, setEmail] = useState("demo.user@stayvista.local");
  const [password, setPassword] = useState("demo1234!");
  const [name, setName] = useState("Demo User");
  const [phone, setPhone] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const currentUser = getAuthUser();
  const next = useMemo(() => {
    const value = params.get("next");
    return value && value.startsWith("/") ? value : "/my/reservations";
  }, [params]);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (!email.trim() || !password.trim()) {
      setError("VALIDATION_ERROR: 이메일과 비밀번호를 입력해 주세요.");
      return;
    }

    setLoading(true);
    try {
      const endpoint = mode === "login" ? "/v1/auth/login" : "/v1/auth/register";
      const payload = mode === "login"
        ? { email, password }
        : { email, password, name: name.trim() || undefined, phone: phone.trim() || undefined };
      const response = await apiPost<AuthLoginData>(endpoint, payload, { Authorization: "" });
      setAuthSession(response.data);
      navigate(next, { replace: true });
    } catch (e: unknown) {
      const apiError = (typeof e === "object" && e !== null ? e : {}) as ApiError;
      setError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "인증에 실패했습니다."}`);
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="page auth-page">
      <header className="page-head">
        <p className="page-kicker">ACCOUNT ACCESS · REDIS SESSION</p>
        <h2>{mode === "login" ? "로그인" : "회원가입"}</h2>
        <p className="page-summary">
          이메일/비밀번호 인증 후 Redis 세션 기반으로 예약과 결제를 진행합니다.
        </p>
      </header>

      <div className="auth-card">
        {currentUser && (
          <p className="notice info">
            현재 로그인: {currentUser.name} (#{currentUser.userId})
          </p>
        )}
        <div className="chips">
          <button
            type="button"
            className={mode === "login" ? "chip-btn active" : "chip-btn"}
            onClick={() => setMode("login")}
          >
            로그인
          </button>
          <button
            type="button"
            className={mode === "register" ? "chip-btn active" : "chip-btn"}
            onClick={() => setMode("register")}
          >
            회원가입
          </button>
        </div>
        <form className="auth-form" onSubmit={onSubmit}>
          <label className="field-group">
            이메일
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="name@example.com"
              autoComplete="email"
            />
          </label>
          <label className="field-group">
            비밀번호
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="8자 이상"
              autoComplete={mode === "login" ? "current-password" : "new-password"}
            />
          </label>
          {mode === "register" && (
            <>
              <label className="field-group">
                이름
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="홍길동"
                  autoComplete="name"
                />
              </label>
              <label className="field-group">
                연락처 (선택)
                <input
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  placeholder="010-0000-0000"
                  autoComplete="tel"
                />
              </label>
            </>
          )}
          <p className="panel-note">
            데모 계정: <strong>demo.user@stayvista.local / demo1234!</strong>
          </p>
          {error && <p className="notice error">{error}</p>}
          <div className="actions">
            <button type="submit" disabled={loading}>
              {loading ? "처리 중..." : mode === "login" ? "로그인" : "회원가입 후 로그인"}
            </button>
            <Link to="/" className="outline-btn">취소</Link>
          </div>
        </form>
      </div>
    </section>
  );
}
