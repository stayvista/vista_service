export type ApiEnvelope<T> = { request_id: string; data: T };
export type ApiError = { code: string; message: string; details?: Record<string, unknown>; status?: number };

const ADMIN_KEY = "stayvista_admin_id";

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export function getAdminId(): string {
  return localStorage.getItem(ADMIN_KEY) ?? "";
}

export function setAdminId(adminId: string) {
  localStorage.setItem(ADMIN_KEY, adminId);
}

export function clearAdminId() {
  localStorage.removeItem(ADMIN_KEY);
}

function defaultHeaders(): Record<string, string> {
  const adminId = getAdminId();
  return adminId ? { "X-Admin-Id": adminId } : {};
}

export async function apiGet<T>(path: string): Promise<ApiEnvelope<T>> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) throw await parseError(res);
  return res.json();
}

export async function apiPost<T>(path: string, body: unknown): Promise<ApiEnvelope<T>> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...defaultHeaders() },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await parseError(res);
  return res.json();
}

export async function apiPatch<T>(path: string, body: unknown): Promise<ApiEnvelope<T>> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", ...defaultHeaders() },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await parseError(res);
  return res.json();
}

export async function apiPut<T>(path: string, body: unknown): Promise<ApiEnvelope<T>> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...defaultHeaders() },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await parseError(res);
  return res.json();
}

async function parseError(response: Response): Promise<ApiError> {
  try {
    const payload = (await response.json()) as { error?: ApiError };
    if (payload.error) {
      return { ...payload.error, status: response.status };
    }
  } catch {
    // fall through
  }
  return { code: "INTERNAL", message: `HTTP ${response.status}`, status: response.status };
}
