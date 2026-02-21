import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { apiGet, apiPost } from "../../api/client";
import { ensureAnonId } from "../../auth/anon";

type LocaleData = {
  country: string;
  currency: string;
  language: string;
  source: string;
};

type LocaleUpdate = {
  country?: string;
  currency?: string;
  language?: string;
};

type LocaleContextValue = {
  locale: LocaleData;
  loading: boolean;
  updateLocale: (update: LocaleUpdate) => Promise<void>;
  refreshLocale: () => Promise<void>;
  anonId: string;
};

const LOCALE_STORAGE_KEY = "stayvista.web_user.locale";
const DEFAULT_LOCALE: LocaleData = {
  country: "KR",
  currency: "KRW",
  language: "ko",
  source: "inferred",
};

const LocaleContext = createContext<LocaleContextValue | null>(null);

function loadStoredLocale(): LocaleData | null {
  if (typeof window === "undefined") {
    return null;
  }
  const raw = window.localStorage.getItem(LOCALE_STORAGE_KEY);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as Partial<LocaleData>;
    if (!parsed.country || !parsed.currency || !parsed.language) {
      return null;
    }
    return {
      country: parsed.country,
      currency: parsed.currency,
      language: parsed.language,
      source: parsed.source ?? "stored",
    };
  } catch {
    return null;
  }
}

function persistLocale(locale: LocaleData) {
  if (typeof window === "undefined") {
    return;
  }
  window.localStorage.setItem(LOCALE_STORAGE_KEY, JSON.stringify(locale));
}

export function LocaleProvider({ children }: { children: ReactNode }) {
  const [locale, setLocale] = useState<LocaleData>(() => loadStoredLocale() ?? DEFAULT_LOCALE);
  const [loading, setLoading] = useState(false);
  const anonId = useMemo(() => ensureAnonId(), []);

  const refreshLocale = useCallback(async () => {
    setLoading(true);
    try {
      const response = await apiGet<LocaleData>("/v1/locale", {
        "X-Anon-Id": anonId,
      });
      setLocale(response.data);
      persistLocale(response.data);
    } catch {
      // keep stored/default locale if lookup fails
    } finally {
      setLoading(false);
    }
  }, [anonId]);

  useEffect(() => {
    void refreshLocale();
  }, [refreshLocale]);

  const updateLocale = useCallback(
    async (update: LocaleUpdate) => {
      const payload = {
        country: (update.country ?? locale.country).toUpperCase(),
        currency: (update.currency ?? locale.currency).toUpperCase(),
        language: (update.language ?? locale.language).toLowerCase(),
      };
      setLoading(true);
      try {
        const response = await apiPost<LocaleData>(
          "/v1/locale",
          payload,
          {
            "X-Anon-Id": anonId,
          },
        );
        setLocale(response.data);
        persistLocale(response.data);
      } finally {
        setLoading(false);
      }
    },
    [anonId, locale.country, locale.currency, locale.language],
  );

  const value = useMemo<LocaleContextValue>(
    () => ({
      locale,
      loading,
      updateLocale,
      refreshLocale,
      anonId,
    }),
    [anonId, loading, locale, refreshLocale, updateLocale],
  );

  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useLocale(): LocaleContextValue {
  const context = useContext(LocaleContext);
  if (!context) {
    throw new Error("useLocale must be used within LocaleProvider");
  }
  return context;
}
