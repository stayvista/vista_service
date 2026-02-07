import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { apiGet } from "../api/client";

type TicketProduct = { product_id: number; name: string; category: string };

export function TicketsPage() {
  const [items, setItems] = useState<TicketProduct[]>([]);
  const [category, setCategory] = useState("ALL");
  const [keyword, setKeyword] = useState("");

  useEffect(() => {
    apiGet<{ items: TicketProduct[] }>("/v1/tickets/products").then((res) => setItems(res.data.items));
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
    <section className="page">
      <h2>티켓/체험</h2>
      <div className="toolbar">
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
      <div className="toolbar">
        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          placeholder="상품명 검색"
        />
      </div>
      <ul className="card-list">
        {filtered.map((item) => (
          <li key={item.product_id} className="card">
            <h3>{item.name}</h3>
            <p>{item.category}</p>
            <Link to={`/tickets/${item.product_id}`}>상세</Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
