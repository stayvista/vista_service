const ANON_STORAGE_KEY = "stayvista_anon_id";

export function ensureAnonId(): string {
  if (typeof window === "undefined") {
    return "anon-server";
  }

  const existing = window.localStorage.getItem(ANON_STORAGE_KEY);
  if (existing && existing.trim()) {
    return existing;
  }

  const generated = `anon_${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
  window.localStorage.setItem(ANON_STORAGE_KEY, generated);
  return generated;
}
