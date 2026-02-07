import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiGet } from "../api/client";

type PackageItem = {
  package_id: number;
  name: string;
  status: string;
  price: { amount_total: number; currency?: string };
};

export function PackagesPage() {
  const [items, setItems] = useState<PackageItem[]>([]);

  useEffect(() => {
    apiGet<{ items: PackageItem[] }>("/v1/packages").then((res) => setItems(res.data.items));
  }, []);

  return (
    <section className="page">
      <h2>패키지</h2>
      <ul className="card-list">
        {items.map((item) => (
          <li className="card" key={item.package_id}>
            <h3>{item.name}</h3>
            <p className={item.status === "ACTIVE" ? "success" : "warning"}>{item.status}</p>
            <p>
              {item.price?.amount_total ?? 0} {item.price?.currency ?? "KRW"}
            </p>
            <p>숙소 + 티켓을 한 번에 확정하는 사가형 구매 플로우</p>
            <Link to={`/packages/${item.package_id}`}>상세</Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
