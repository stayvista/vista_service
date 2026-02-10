import { clearAuthSession, getAuthBearerToken } from "../auth/session";

export type ApiEnvelope<T> = { request_id: string; data: T };
export type ApiErrorEnvelope = { request_id: string; error: { code: string; message: string; details?: Record<string, unknown> } };

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:18765";

function withAuthHeaders(extraHeaders: Record<string, string>): Record<string, string> {
  const headers = { ...extraHeaders };
  if (!headers.Authorization) {
    const accessToken = getAuthBearerToken();
    if (accessToken) {
      headers.Authorization = `Bearer ${accessToken}`;
    }
  }
  return headers;
}

export async function apiGet<T>(path: string, extraHeaders: Record<string, string> = {}): Promise<ApiEnvelope<T>> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: withAuthHeaders(extraHeaders),
  });
  if (!response.ok) {
    await handleError(response);
  }
  return response.json();
}

export async function apiPost<T>(path: string, body: unknown, extraHeaders: Record<string, string> = {}): Promise<ApiEnvelope<T>> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...withAuthHeaders(extraHeaders),
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    await handleError(response);
  }
  return response.json();
}

export async function apiPut<T>(path: string, body: unknown, extraHeaders: Record<string, string> = {}): Promise<ApiEnvelope<T>> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      ...withAuthHeaders(extraHeaders),
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    await handleError(response);
  }
  return response.json();
}

export async function apiDelete<T>(path: string, extraHeaders: Record<string, string> = {}): Promise<ApiEnvelope<T>> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "DELETE",
    headers: withAuthHeaders(extraHeaders),
  });
  if (!response.ok) {
    await handleError(response);
  }
  return response.json();
}

async function handleError(response: Response): Promise<never> {
  const parsed = await parseError(response);
  if (response.status === 401 && !parsed.code?.startsWith("QUEUE_")) {
    clearAuthSession();
  }
  throw parsed;
}

async function parseError(response: Response): Promise<ApiErrorEnvelope["error"]> {
  try {
    const payload = (await response.json()) as ApiErrorEnvelope;
    return payload.error;
  } catch {
    return { code: "INTERNAL", message: `HTTP ${response.status}` };
  }
}
