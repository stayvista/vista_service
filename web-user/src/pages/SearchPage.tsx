import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { apiGet } from "../api/client";

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

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const [items, setItems] = useState<SearchItem[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const requestQuery = useMemo(() => {
    const next = new URLSearchParams(params);
    next.delete("cursor");
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
  const city = params.get("city") ?? "";
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

  function removeParam(name: string) {
    const next = new URLSearchParams(params);
    next.delete(name);
    setParams(next);
  }

  function clearSearchFilters() {
    const next = new URLSearchParams(params);
    ["city", "min_price", "max_price", "min_rating", "sort"].forEach((key) => next.delete(key));
    setParams(next);
  }

  function applyPreset(preset: SearchPreset) {
    const next = new URLSearchParams(params);
    if (preset.minPrice) next.set("min_price", preset.minPrice); else next.delete("min_price");
    if (preset.maxPrice) next.set("max_price", preset.maxPrice); else next.delete("max_price");
    if (preset.minRating) next.set("min_rating", preset.minRating); else next.delete("min_rating");
    if (preset.sort) next.set("sort", preset.sort); else next.delete("sort");
    setParams(next);
  }

  const activeFilters = useMemo(() => {
    const chips: Array<{ key: string; label: string }> = [];
    if (city) chips.push({ key: "city", label: `도시: ${city}` });
    if (minPrice) chips.push({ key: "min_price", label: `최소가: ${Number(minPrice).toLocaleString()} KRW` });
    if (maxPrice) chips.push({ key: "max_price", label: `최대가: ${Number(maxPrice).toLocaleString()} KRW` });
    if (minRating) chips.push({ key: "min_rating", label: `최소 평점: ${minRating}` });
    if (sortLabel) chips.push({ key: "sort", label: `정렬: ${sortLabel}` });
    return chips;
  }, [city, minPrice, maxPrice, minRating, sortLabel]);

  function thumbnailOf(item: SearchItem): string {
    return item.thumbnail_url || `https://picsum.photos/seed/search-${item.property_id}/420/260`;
  }

  return (
    <section className="page">
      <h2>검색 결과</h2>
      <div className="toolbar">
        {presets.map((preset) => (
          <button
            key={preset.id}
            type="button"
            className="chip-btn"
            onClick={() => applyPreset(preset)}
          >
            {preset.label}
          </button>
        ))}
      </div>
      <div className="toolbar">
        <input
          value={city}
          placeholder="도시"
          onChange={(e) => upsertParam("city", e.target.value)}
        />
        <input
          type="number"
          value={minPrice}
          placeholder="최소 가격"
          onChange={(e) => upsertParam("min_price", e.target.value)}
        />
        <input
          type="number"
          value={maxPrice}
          placeholder="최대 가격"
          onChange={(e) => upsertParam("max_price", e.target.value)}
        />
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
          <button
            key={chip.key}
            type="button"
            className="chip-btn"
            onClick={() => removeParam(chip.key)}
          >
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
