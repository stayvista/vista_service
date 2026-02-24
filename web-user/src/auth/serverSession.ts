import { clearAuthSession, getAuthBearerToken, getAuthUser } from "./session";

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:18765";

export type ServerSessionState = "authenticated" | "unauthorized" | "unknown";

/**
 * Verifies that local auth state and backend Redis session are both valid.
 * Returns "unauthorized" for missing/expired/invalid sessions.
 */
export async function verifyServerSession(): Promise<ServerSessionState> {
  const token = getAuthBearerToken();
  const user = getAuthUser();
  if (!token || !user) {
    return "unauthorized";
  }

  try {
    const response = await fetch(`${API_BASE}/v1/me/session`, {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    });
    if (response.ok) {
      return "authenticated";
    }
    if (response.status === 401) {
      clearAuthSession();
      return "unauthorized";
    }
    return "unknown";
  } catch {
    return "unknown";
  }
}
