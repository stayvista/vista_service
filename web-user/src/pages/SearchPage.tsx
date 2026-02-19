import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";

type SearchItem = {
  property_id: number;
  name: string;
  city?: string;
  price_min?: number;
  rating?: number;
  thumbnail_url?: string | null;
};

type ApiError = { code?: string; message?: string };

type SearchPreset = {
  id: string;
  label: string;
  minPrice?: string;
  maxPrice?: string;
  minRating?: string;
  sort?: string;
};

type AutocompleteSuggestion = {
  type: string;
  id: string;
  display: string;
  subtitle?: string;
  highlight?: string;
  geo?: {
    lat: number;
    lng: number;
  };
  score: number;
  source: string;
  bucket?: string;
};

type AutocompleteData = {
  q: string;
  items: AutocompleteSuggestion[];
  meta: {
    types: string[];
    size: number;
    lang: string;
    took_ms: number;
    cache_hit: boolean;
  };
};

type SectionedSuggestions = {
  key: string;
  title: string;
  rows: Array<{
    item: AutocompleteSuggestion;
    index: number;
  }>;
};

const AUTOCOMPLETE_TYPES = "city,property,poi,station,airport";
const TYPE_LABELS: Record<string, string> = {
  CITY: "도시",
  PROPERTY: "숙소",
  POI: "POI",
  STATION: "역",
  AIRPORT: "공항",
};

function ensureAnonId(): string {
  if (typeof window === "undefined") {
    return "anon-server";
  }
  const storageKey = "stayvista_anon_id";
  const existing = window.localStorage.getItem(storageKey);
  if (existing && existing.trim()) {
    return existing;
  }
  const generated = `anon_${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
  window.localStorage.setItem(storageKey, generated);
  return generated;
}

function sessionId(): string {
  return `s_${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
}

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const [items, setItems] = useState<SearchItem[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const city = (params.get("city") ?? "").normalize("NFC");
  const placeId = params.get("place_id") ?? "";
  const placeLabel = (params.get("place_label") ?? "").normalize("NFC");
  const effectivePlaceLabel = placeLabel || city;

  const [cityInput, setCityInput] = useState(effectivePlaceLabel);
  const cityComposingRef = useRef(false);

  const [suggestions, setSuggestions] = useState<AutocompleteSuggestion[]>([]);
  const [suggestOpen, setSuggestOpen] = useState(false);
  const [suggestLoading, setSuggestLoading] = useState(false);
  const [suggestError, setSuggestError] = useState<string | null>(null);
  const [activeSuggestIndex, setActiveSuggestIndex] = useState(-1);

  const sessionIdRef = useRef(sessionId());
  const anonIdRef = useRef(ensureAnonId());
  const impressionKeyRef = useRef<string>("");
  const autocompleteWrapRef = useRef<HTMLDivElement | null>(null);

  const requestQuery = useMemo(() => {
    const next = new URLSearchParams(params);
    next.delete("cursor");
    next.delete("place_label");
    if (next.get("place_id")) {
      next.delete("city");
    }
    return next;
  }, [params]);

  const requestQueryString = useMemo(() => requestQuery.toString(), [requestQuery]);

  const loadFirstPage = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await apiGet<{ items: SearchItem[]; next_cursor?: string }>(
        `/v1/search/properties?${requestQueryString}`
      );
      setItems(response.data.items);
      setNextCursor(response.data.next_cursor ?? null);
    } catch (e) {
      const err = e as ApiError;
      if (err.code === "RATE_LIMITED") {
        setError("요청이 많습니다. 잠시 후 다시 시도해 주세요.");
      } else {
        setError(`${err.code ?? "ERROR"}: ${err.message ?? "검색 실패"}`);
      }
    } finally {
      setLoading(false);
    }
  }, [requestQueryString]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadFirstPage();
    }, 300);
    return () => window.clearTimeout(timer);
  }, [loadFirstPage]);

  useEffect(() => {
    if (!cityComposingRef.current) {
      setCityInput(effectivePlaceLabel);
    }
  }, [effectivePlaceLabel]);

  useEffect(() => {
    function handleOutsideClick(event: MouseEvent) {
      const target = event.target as Node | null;
      if (!target) return;
      if (autocompleteWrapRef.current?.contains(target)) {
        return;
      }
      setSuggestOpen(false);
      setActiveSuggestIndex(-1);
    }

    document.addEventListener("mousedown", handleOutsideClick);
    return () => document.removeEventListener("mousedown", handleOutsideClick);
  }, []);

  const sendImpression = useCallback(
    async (rows: AutocompleteSuggestion[], qValue: string) => {
      if (!rows.length) return;
      const key = `${qValue}:${rows.map((row) => row.id).join("|")}`;
      if (impressionKeyRef.current === key) {
        return;
      }
      impressionKeyRef.current = key;

      try {
        await apiPost(
          "/v1/autocomplete/feedback/impression",
          {
            session_id: sessionIdRef.current,
            anon_id: anonIdRef.current,
            q: qValue,
            lang: "ko",
            types: AUTOCOMPLETE_TYPES.split(","),
            size: rows.length,
            items: rows.map((row, index) => ({
              id: row.id,
              type: row.type,
              display: row.display,
              subtitle: row.subtitle,
              geo: row.geo,
              position: index,
              score: row.score,
            })),
          },
          {
            "X-Anon-Id": anonIdRef.current,
          }
        );
      } catch {
        // feedback failures should never block user search flow
      }
    },
    []
  );

  const sendSelect = useCallback(
    async (selected: AutocompleteSuggestion, rows: AutocompleteSuggestion[], position: number, qValue: string) => {
      try {
        await apiPost(
          "/v1/autocomplete/feedback/select",
          {
            session_id: sessionIdRef.current,
            anon_id: anonIdRef.current,
            q: qValue,
            lang: "ko",
            types: AUTOCOMPLETE_TYPES.split(","),
            size: rows.length,
            selected: {
              id: selected.id,
              type: selected.type,
              display: selected.display,
              subtitle: selected.subtitle,
              geo: selected.geo,
              position,
              score: selected.score,
            },
            items: rows.map((row, index) => ({
              id: row.id,
              type: row.type,
              display: row.display,
              subtitle: row.subtitle,
              geo: row.geo,
              position: index,
              score: row.score,
            })),
          },
          {
            "X-Anon-Id": anonIdRef.current,
          }
        );
      } catch {
        // feedback failures should never block user search flow
      }
    },
    []
  );

  useEffect(() => {
    if (!suggestOpen || cityComposingRef.current) {
      return;
    }

    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      setSuggestLoading(true);
      setSuggestError(null);

      const input = cityInput.normalize("NFC").trim();
      const query = new URLSearchParams();
      if (input) {
        query.set("q", input);
      }
      query.set("types", AUTOCOMPLETE_TYPES);
      query.set("size", "10");
      query.set("lang", "ko");

      try {
        const response = await apiGet<AutocompleteData>(
          `/v1/autocomplete?${query.toString()}`,
          {
            "X-Anon-Id": anonIdRef.current,
          },
          controller.signal
        );
        const nextRows = response.data.items;
        setSuggestions(nextRows);
        setActiveSuggestIndex(nextRows.length > 0 ? 0 : -1);
        void sendImpression(nextRows, input);
      } catch (e) {
        if ((e as Error).name === "AbortError") {
          return;
        }
        const err = e as ApiError;
        if (err.code === "RATE_LIMITED") {
          setSuggestError("자동완성 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        } else {
          setSuggestError("자동완성 결과를 불러오지 못했습니다.");
        }
        setSuggestions([]);
      } finally {
        setSuggestLoading(false);
      }
    }, 180);

    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [cityInput, sendImpression, suggestOpen]);

  const sectionedSuggestions = useMemo<SectionedSuggestions[]>(() => {
    if (!suggestions.length) {
      return [];
    }

    const input = cityInput.trim();
    const rowsWithIndex = suggestions.map((item, index) => ({ item, index }));

    if (!input) {
      const recentRows = rowsWithIndex.filter(({ item }) => item.bucket === "recent");
      const popularRows = rowsWithIndex.filter(({ item }) => item.bucket !== "recent");
      const sections: SectionedSuggestions[] = [];
      if (recentRows.length) {
        sections.push({ key: "recent", title: "최근 선택", rows: recentRows });
      }
      if (popularRows.length) {
        sections.push({ key: "popular", title: "인기 추천", rows: popularRows });
      }
      return sections;
    }

    const grouped = new Map<string, SectionedSuggestions>();
    rowsWithIndex.forEach((row) => {
      const key = row.item.type;
      const current = grouped.get(key) ?? {
        key,
        title: TYPE_LABELS[key] ?? key,
        rows: [],
      };
      current.rows.push(row);
      grouped.set(key, current);
    });

    return ["CITY", "PROPERTY", "POI", "STATION", "AIRPORT"]
      .map((key) => grouped.get(key))
      .filter((section): section is SectionedSuggestions => Boolean(section));
  }, [cityInput, suggestions]);

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    setError(null);
    try {
      const query = new URLSearchParams(requestQuery);
      query.set("cursor", nextCursor);
      const response = await apiGet<{ items: SearchItem[]; next_cursor?: string }>(
        `/v1/search/properties?${query.toString()}`
      );
      setItems((prev) => [...prev, ...response.data.items]);
      setNextCursor(response.data.next_cursor ?? null);
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "더보기 실패"}`);
    } finally {
      setLoadingMore(false);
    }
  }

  const sort = params.get("sort") ?? "";
  const minPrice = params.get("min_price") ?? "";
  const maxPrice = params.get("max_price") ?? "";
  const minRating = params.get("min_rating") ?? "";

  const presets: SearchPreset[] = [
    { id: "value", label: "가성비", maxPrice: "120000", minRating: "4.0", sort: "price_asc" },
    { id: "top-rated", label: "고평점", minRating: "4.5", sort: "rating_desc" },
    { id: "luxury", label: "럭셔리", minPrice: "220000", minRating: "4.2", sort: "rating_desc" },
  ];

  const sortLabel = useMemo(() => {
    if (sort === "price_asc") return "가격 낮은 순";
    if (sort === "price_desc") return "가격 높은 순";
    if (sort === "rating_desc") return "평점 높은 순";
    return "";
  }, [sort]);

  function upsertParam(name: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) {
      next.set(name, value);
    } else {
      next.delete(name);
    }
    setParams(next);
  }

  function applyPlaceSelection(selected: AutocompleteSuggestion, position: number) {
    const next = new URLSearchParams(params);
    next.delete("city");
    next.delete("cursor");
    next.set("place_id", selected.id);
    next.set("place_label", selected.display);
    setParams(next);

    setCityInput(selected.display);
    setSuggestOpen(false);
    setActiveSuggestIndex(-1);
    void sendSelect(selected, suggestions, position, cityInput.trim());
  }

  function applyFreeTextCity(value: string) {
    const normalized = value.normalize("NFC").trim();
    const next = new URLSearchParams(params);
    next.delete("place_id");
    next.delete("place_label");
    next.delete("cursor");
    if (normalized) {
      next.set("city", normalized);
    } else {
      next.delete("city");
    }
    setParams(next);
  }

  function removeParam(name: string) {
    const next = new URLSearchParams(params);
    if (name === "place_id") {
      next.delete("place_id");
      next.delete("place_label");
    } else {
      next.delete(name);
    }
    setParams(next);
  }

  function clearSearchFilters() {
    const next = new URLSearchParams(params);
    ["city", "place_id", "place_label", "min_price", "max_price", "min_rating", "sort"].forEach((key) =>
      next.delete(key)
    );
    setParams(next);
    setCityInput("");
  }

  function applyPreset(preset: SearchPreset) {
    const next = new URLSearchParams(params);
    if (preset.minPrice) next.set("min_price", preset.minPrice);
    else next.delete("min_price");
    if (preset.maxPrice) next.set("max_price", preset.maxPrice);
    else next.delete("max_price");
    if (preset.minRating) next.set("min_rating", preset.minRating);
    else next.delete("min_rating");
    if (preset.sort) next.set("sort", preset.sort);
    else next.delete("sort");
    setParams(next);
  }

  const activeFilters = useMemo(() => {
    const chips: Array<{ key: string; label: string }> = [];
    if (placeId) {
      chips.push({ key: "place_id", label: `위치: ${placeLabel || placeId}` });
    } else if (city) {
      chips.push({ key: "city", label: `도시: ${city}` });
    }
    if (minPrice) chips.push({ key: "min_price", label: `최소가: ${Number(minPrice).toLocaleString()} KRW` });
    if (maxPrice) chips.push({ key: "max_price", label: `최대가: ${Number(maxPrice).toLocaleString()} KRW` });
    if (minRating) chips.push({ key: "min_rating", label: `최소 평점: ${minRating}` });
    if (sortLabel) chips.push({ key: "sort", label: `정렬: ${sortLabel}` });
    return chips;
  }, [placeId, placeLabel, city, minPrice, maxPrice, minRating, sortLabel]);

  function thumbnailOf(item: SearchItem): string {
    return item.thumbnail_url || `https://picsum.photos/seed/search-${item.property_id}/420/260`;
  }

  return (
    <section className="page">
      <h2>검색 결과</h2>
      <div className="toolbar">
        {presets.map((preset) => (
          <button key={preset.id} type="button" className="chip-btn" onClick={() => applyPreset(preset)}>
            {preset.label}
          </button>
        ))}
      </div>
      <div className="toolbar search-toolbar">
        <div className="autocomplete-wrap" ref={autocompleteWrapRef}>
          <input
            value={cityInput}
            placeholder="도시/숙소/POI 검색"
            onFocus={() => {
              setSuggestOpen(true);
            }}
            onCompositionStart={() => {
              cityComposingRef.current = true;
            }}
            onCompositionEnd={(event) => {
              cityComposingRef.current = false;
              setCityInput(event.currentTarget.value.normalize("NFC"));
              setSuggestOpen(true);
            }}
            onChange={(event) => {
              const nextValue = event.target.value.normalize("NFC");
              setCityInput(nextValue);
              setSuggestOpen(true);
            }}
            onBlur={() => {
              if (!placeId || cityInput.trim() !== (placeLabel || "").trim()) {
                applyFreeTextCity(cityInput);
              }
              window.setTimeout(() => {
                setSuggestOpen(false);
                setActiveSuggestIndex(-1);
              }, 120);
            }}
            onKeyDown={(event) => {
              if (!suggestOpen && event.key === "ArrowDown") {
                setSuggestOpen(true);
                event.preventDefault();
                return;
              }

              if (!suggestOpen) {
                if (event.key === "Enter") {
                  event.preventDefault();
                  applyFreeTextCity(cityInput);
                }
                return;
              }

              if (event.key === "ArrowDown") {
                event.preventDefault();
                if (!suggestions.length) return;
                setActiveSuggestIndex((prev) => (prev + 1) % suggestions.length);
                return;
              }

              if (event.key === "ArrowUp") {
                event.preventDefault();
                if (!suggestions.length) return;
                setActiveSuggestIndex((prev) => (prev <= 0 ? suggestions.length - 1 : prev - 1));
                return;
              }

              if (event.key === "Enter") {
                event.preventDefault();
                if (activeSuggestIndex >= 0 && activeSuggestIndex < suggestions.length) {
                  applyPlaceSelection(suggestions[activeSuggestIndex], activeSuggestIndex);
                } else {
                  applyFreeTextCity(cityInput);
                  setSuggestOpen(false);
                }
                return;
              }

              if (event.key === "Escape") {
                event.preventDefault();
                setSuggestOpen(false);
                setActiveSuggestIndex(-1);
              }
            }}
          />
          {suggestOpen && (
            <div className="autocomplete-dropdown" role="listbox" aria-label="통합 자동완성 목록">
              {suggestLoading && <p className="autocomplete-status">추천어를 불러오는 중...</p>}
              {!suggestLoading && suggestError && <p className="autocomplete-status error">{suggestError}</p>}
              {!suggestLoading && !suggestError && !suggestions.length && (
                <p className="autocomplete-status">추천어가 없습니다.</p>
              )}
              {!suggestLoading && !suggestError && sectionedSuggestions.map((section) => (
                <div className="autocomplete-section" key={section.key}>
                  <p className="autocomplete-section-title">{section.title}</p>
                  <ul>
                    {section.rows.map(({ item, index }) => (
                      <li key={`${item.id}:${index}`}>
                        <button
                          type="button"
                          className={index === activeSuggestIndex ? "autocomplete-row active" : "autocomplete-row"}
                          onMouseEnter={() => setActiveSuggestIndex(index)}
                          onMouseDown={(event) => event.preventDefault()}
                          onClick={() => applyPlaceSelection(item, index)}
                        >
                          <span className="autocomplete-row-main">{item.display}</span>
                          {item.subtitle && <span className="autocomplete-row-sub">{item.subtitle}</span>}
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          )}
        </div>
        <input type="number" value={minPrice} placeholder="최소 가격" onChange={(e) => upsertParam("min_price", e.target.value)} />
        <input type="number" value={maxPrice} placeholder="최대 가격" onChange={(e) => upsertParam("max_price", e.target.value)} />
        <input
          type="number"
          step="0.1"
          min="0"
          max="5"
          value={minRating}
          placeholder="최소 평점"
          onChange={(e) => upsertParam("min_rating", e.target.value)}
        />
        <select
          value={sort}
          onChange={(e) => {
            upsertParam("sort", e.target.value);
          }}
        >
          <option value="">기본 정렬</option>
          <option value="price_asc">가격 낮은 순</option>
          <option value="price_desc">가격 높은 순</option>
          <option value="rating_desc">평점 높은 순</option>
        </select>
      </div>
      <div className="chips search-chips">
        {activeFilters.map((chip) => (
          <button key={chip.key} type="button" className="chip-btn" onClick={() => removeParam(chip.key)}>
            {chip.label} ×
          </button>
        ))}
        {activeFilters.length > 0 && (
          <button type="button" className="chip-btn danger" onClick={clearSearchFilters}>
            필터 전체 초기화
          </button>
        )}
      </div>
      {error && <p className="error">{error}</p>}
      {loading && <p>검색 중...</p>}
      {!loading && !error && <p>현재 {items.length}개 결과 표시 중</p>}
      <ul className="card-list">
        {items.map((item) => (
          <li key={item.property_id} className="card search-card">
            <img className="search-thumb" src={thumbnailOf(item)} alt={`${item.name} 썸네일`} loading="lazy" />
            <div className="search-body">
              <h3>{item.name}</h3>
              <p>{item.city ?? "도시 정보 없음"}</p>
              <div className="badge-row">
                <span className="price-badge">최저가 {(item.price_min ?? 0).toLocaleString()} KRW</span>
                <span className="rating-badge">★ {item.rating?.toFixed(1) ?? "N/A"}</span>
              </div>
              <Link to={`/properties/${item.property_id}`}>상세 보기</Link>
            </div>
          </li>
        ))}
      </ul>
      <div className="actions">
        <button
          disabled={!nextCursor || loading || loadingMore}
          onClick={() => {
            void loadMore();
          }}
        >
          {loadingMore ? "로딩 중..." : "더보기"}
        </button>
      </div>
    </section>
  );
}
