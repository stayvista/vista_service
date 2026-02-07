export type ApiEnvelope<T> = { request_id: string; data: T };
export type ApiErrorEnvelope = { request_id: string; error: { code: string; message: string; details?: Record<string, unknown> } };

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export async function apiGet<T>(path: string): Promise<ApiEnvelope<T>> {
  const response = await fetch(`${API_BASE}${path}`);
  if (!response.ok) {
    throw await parseError(response);
  }
  return response.json();
}

export async function apiPost<T>(path: string, body: unknown, extraHeaders: Record<string, string> = {}): Promise<ApiEnvelope<T>> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...extraHeaders,
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw await parseError(response);
  }
  return response.json();
}

export async function apiPut<T>(path: string, body: unknown, extraHeaders: Record<string, string> = {}): Promise<ApiEnvelope<T>> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      ...extraHeaders,
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw await parseError(response);
  }
  return response.json();
}

async function parseError(response: Response): Promise<ApiErrorEnvelope["error"]> {
  try {
    const payload = (await response.json()) as ApiErrorEnvelope;
    return payload.error;
  } catch {
    return { code: "INTERNAL", message: `HTTP ${response.status}` };
  }
}
