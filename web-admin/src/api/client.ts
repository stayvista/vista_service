export type ApiEnvelope<T> = { request_id: string; data: T };

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export async function apiGet<T>(path: string): Promise<ApiEnvelope<T>> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) throw await res.json();
  return res.json();
}

export async function apiPost<T>(path: string, body: unknown): Promise<ApiEnvelope<T>> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Admin-Id": "9001" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await res.json();
  return res.json();
}

export async function apiPatch<T>(path: string, body: unknown): Promise<ApiEnvelope<T>> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", "X-Admin-Id": "9001" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await res.json();
  return res.json();
}

export async function apiPut<T>(path: string, body: unknown): Promise<ApiEnvelope<T>> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", "X-Admin-Id": "9001" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await res.json();
  return res.json();
}
