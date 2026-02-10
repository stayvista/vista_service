export type AuthUser = {
  userId: number;
  name: string;
  email: string;
};

export type AuthSession = {
  tokenType: string;
  accessToken: string;
  expiresAtMs: number;
  user: AuthUser;
};

export type AuthLoginData = {
  token_type: string;
  access_token: string;
  expires_in_seconds: number;
  user: {
    user_id: number;
    name: string;
    email: string;
  };
};

const AUTH_STORAGE_KEY = "stayvista.web_user.auth_session";
const AUTH_CHANGE_EVENT = "stayvista:auth-changed";

function parseSession(raw: string | null): AuthSession | null {
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as Partial<AuthSession>;
    if (!value || typeof value !== "object") return null;
    if (typeof value.accessToken !== "string" || !value.accessToken) return null;
    if (typeof value.expiresAtMs !== "number" || !Number.isFinite(value.expiresAtMs)) return null;
    if (!value.user || typeof value.user.userId !== "number" || !Number.isFinite(value.user.userId)) return null;
    if (typeof value.user.name !== "string" || typeof value.user.email !== "string") return null;

    return {
      tokenType: typeof value.tokenType === "string" && value.tokenType ? value.tokenType : "Bearer",
      accessToken: value.accessToken,
      expiresAtMs: value.expiresAtMs,
      user: {
        userId: value.user.userId,
        name: value.user.name,
        email: value.user.email,
      },
    };
  } catch {
    return null;
  }
}

function emitAuthChanged() {
  window.dispatchEvent(new Event(AUTH_CHANGE_EVENT));
}

function isExpired(session: AuthSession): boolean {
  return Date.now() >= session.expiresAtMs - 5000;
}

export function getAuthSession(): AuthSession | null {
  if (typeof window === "undefined") return null;
  const parsed = parseSession(window.localStorage.getItem(AUTH_STORAGE_KEY));
  if (!parsed) return null;
  if (isExpired(parsed)) {
    window.localStorage.removeItem(AUTH_STORAGE_KEY);
    return null;
  }
  return parsed;
}

export function getAuthUser(): AuthUser | null {
  return getAuthSession()?.user ?? null;
}

export function getAuthBearerToken(): string | null {
  const session = getAuthSession();
  if (!session) return null;
  return session.accessToken;
}

export function setAuthSession(data: AuthLoginData) {
  if (typeof window === "undefined") return;
  const expiresAtMs = Date.now() + Math.max(1, data.expires_in_seconds) * 1000;
  const session: AuthSession = {
    tokenType: data.token_type || "Bearer",
    accessToken: data.access_token,
    expiresAtMs,
    user: {
      userId: data.user.user_id,
      name: data.user.name,
      email: data.user.email,
    },
  };
  window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(session));
  emitAuthChanged();
}

export function clearAuthSession() {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(AUTH_STORAGE_KEY);
  emitAuthChanged();
}

export function subscribeAuthChange(listener: () => void): () => void {
  const onStorage = (event: StorageEvent) => {
    if (event.key === AUTH_STORAGE_KEY) listener();
  };
  window.addEventListener(AUTH_CHANGE_EVENT, listener);
  window.addEventListener("storage", onStorage);
  return () => {
    window.removeEventListener(AUTH_CHANGE_EVENT, listener);
    window.removeEventListener("storage", onStorage);
  };
}

