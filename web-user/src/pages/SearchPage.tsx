import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { apiGet } from "../api/client";

type SearchItem = { property_id: number; name: string; city?: string; price_min?: number; rating?: number };
type ApiError = { code?: string; message?: string };

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

  return (
    <section className="page">
      <h2>검색 결과</h2>
      <div className="toolbar">
        <input
          value={city}
          placeholder="도시"
          onChange={(e) => {
            const next = new URLSearchParams(params);
            if (e.target.value) {
              next.set("city", e.target.value);
            } else {
              next.delete("city");
            }
            setParams(next);
          }}
        />
        <select
          value={sort}
          onChange={(e) => {
            const next = new URLSearchParams(params);
            if (e.target.value) {
              next.set("sort", e.target.value);
            } else {
              next.delete("sort");
            }
            setParams(next);
          }}
        >
          <option value="">기본 정렬</option>
          <option value="price_asc">가격 낮은 순</option>
          <option value="price_desc">가격 높은 순</option>
          <option value="rating_desc">평점 높은 순</option>
        </select>
      </div>
      {error && <p className="error">{error}</p>}
      {loading && <p>검색 중...</p>}
      <ul className="card-list">
        {items.map((item) => (
          <li key={item.property_id} className="card">
            <h3>{item.name}</h3>
            <p>{item.city ?? "도시 정보 없음"}</p>
            <p>최저가 {item.price_min ?? 0} KRW</p>
            <Link to={`/properties/${item.property_id}`}>상세 보기</Link>
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
