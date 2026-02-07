import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { apiGet } from "../api/client";

type SearchItem = { property_id: number; name: string; city?: string; price_min?: number; rating?: number };

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const [items, setItems] = useState<SearchItem[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(params.get("cursor"));
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function load(append = false) {
    setLoading(true);
    setError(null);
    try {
      const query = new URLSearchParams(params);
      const response = await apiGet<{ items: SearchItem[]; next_cursor?: string }>(`/v1/search/properties?${query.toString()}`);
      setItems((prev) => (append ? [...prev, ...response.data.items] : response.data.items));
      setNextCursor(response.data.next_cursor ?? null);
    } catch (e) {
      setError((e as { message?: string }).message ?? "검색 실패");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load(false);
  }, [params.toString()]);

  const sort = params.get("sort") ?? "";

  return (
    <section className="page">
      <h2>검색 결과</h2>
      <div className="toolbar">
        <select
          value={sort}
          onChange={(e) => {
            const next = new URLSearchParams(params);
            if (e.target.value) {
              next.set("sort", e.target.value);
            } else {
              next.delete("sort");
            }
            next.delete("cursor");
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
          disabled={!nextCursor || loading}
          onClick={() => {
            if (!nextCursor) return;
            const next = new URLSearchParams(params);
            next.set("cursor", nextCursor);
            setParams(next);
            load(true);
          }}
        >
          {loading ? "로딩 중..." : "더보기"}
        </button>
      </div>
    </section>
  );
}
