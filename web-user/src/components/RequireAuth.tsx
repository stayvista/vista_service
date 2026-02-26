import { ReactNode, useEffect, useMemo, useState } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { getAuthUser } from "../auth/session";
import { verifyServerSession } from "../auth/serverSession";

type RequireAuthProps = {
  children: ReactNode;
};

export function RequireAuth({ children }: RequireAuthProps) {
  const location = useLocation();
  const [state, setState] = useState<"checking" | "allowed" | "denied">(() => (
    getAuthUser() ? "checking" : "denied"
  ));
  const next = useMemo(
    () => `${location.pathname}${location.search}`,
    [location.pathname, location.search],
  );

  useEffect(() => {
    const user = getAuthUser();
    if (!user) {
      setState("denied");
      return;
    }

    let cancelled = false;
    setState("checking");
    void verifyServerSession()
      .then((sessionState) => {
        if (cancelled) return;
        setState(sessionState === "authenticated" ? "allowed" : "denied");
      })
      .catch(() => {
        if (cancelled) return;
        setState("denied");
      });

    return () => {
      cancelled = true;
    };
  }, [next]);

  if (state === "checking") {
    return (
      <section className="page auth-page">
        <p className="notice info">로그인 상태를 확인하는 중입니다...</p>
      </section>
    );
  }

  if (state === "denied") {
    return <Navigate to={`/login?next=${encodeURIComponent(next)}`} replace />;
  }

  return children;
}
