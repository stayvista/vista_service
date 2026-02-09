import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { apiGet } from "../api/client";

type TicketProduct = { product_id: number; name: string; category: string };
type ApiError = { code?: string; message?: string };

export function TicketsPage() {
  const [items, setItems] = useState<TicketProduct[]>([]);
  const [category, setCategory] = useState("ALL");
  const [keyword, setKeyword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    setError(null);
    apiGet<{ items: TicketProduct[] }>("/v1/tickets/products")
      .then((res) => {
        setItems(res.data.items);
      })
      .catch((e: unknown) => {
        const err = e as ApiError;
        setItems([]);
        setError(`${err.code ?? "ERROR"}: ${err.message ?? "티켓 조회 실패"}`);
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  const categories = useMemo(() => {
    const values = Array.from(new Set(items.map((item) => item.category)));
    return ["ALL", ...values];
  }, [items]);

  const filtered = useMemo(() => {
    return items.filter((item) => {
      const categoryOk = category === "ALL" || item.category === category;
      const keywordOk = !keyword || item.name.toLowerCase().includes(keyword.toLowerCase());
      return categoryOk && keywordOk;
    });
  }, [items, category, keyword]);

  return (
    <section className="page collection-page">
      <header className="page-head">
        <p className="page-kicker">LIVE INVENTORY · TICKETS</p>
        <div className="page-title-wrap">
          <div>
            <h2>티켓/체험</h2>
            <p className="page-summary">
              공연, 전시, 액티비티 상품의 회차별 재고를 실시간으로 확인하고 즉시 구매할 수 있습니다.
            </p>
          </div>
          <div className="page-metrics" aria-label="티켓 지표">
            <div>
              <strong>{items.length}</strong>
              <span>전체 상품</span>
            </div>
            <div>
              <strong>{filtered.length}</strong>
              <span>검색 결과</span>
            </div>
          </div>
        </div>
      </header>

      <div className="collection-filters">
        <div className="chips">
          {categories.map((value) => (
            <button
              key={value}
              type="button"
              className={value === category ? "chip-btn active" : "chip-btn"}
              onClick={() => setCategory(value)}
            >
              {value}
            </button>
          ))}
        </div>
        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          placeholder="상품명으로 검색"
          aria-label="상품명 검색"
        />
      </div>

      {loading && <p className="notice info">티켓 데이터를 불러오는 중입니다...</p>}
      {error && <p className="notice error">{error}</p>}
      {!loading && !error && filtered.length === 0 && (
        <p className="notice warning">조건에 맞는 티켓 상품이 없습니다.</p>
      )}

      <ul className="product-grid">
        {filtered.map((item) => (
          <li key={item.product_id} className="product-card">
            <img
              className="product-thumb"
              src={`https://picsum.photos/seed/ticket-${item.product_id}/640/380`}
              alt={item.name}
              loading="lazy"
            />
            <div className="product-body">
              <p className="product-type">{item.category}</p>
              <h3>{item.name}</h3>
              <p className="product-copy">회차별 잔여 수량과 판매 상태를 실시간으로 반영합니다.</p>
              <Link to={`/tickets/${item.product_id}`} className="inline-cta">
                시간대 확인
              </Link>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
