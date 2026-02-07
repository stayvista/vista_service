import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiGet } from "../api/client";

type TicketProduct = { product_id: number; name: string; category: string };

export function TicketsPage() {
  const [items, setItems] = useState<TicketProduct[]>([]);

  useEffect(() => {
    apiGet<{ items: TicketProduct[] }>("/v1/tickets/products").then((res) => setItems(res.data.items));
  }, []);

  return (
    <section className="page">
      <h2>티켓/체험</h2>
      <ul className="card-list">
        {items.map((item) => (
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
