import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiGet } from "../api/client";

type PackageItem = {
  package_id: number;
  name: string;
  status: string;
  price: { amount_total: number; currency?: string };
};
type ApiError = { code?: string; message?: string };

export function PackagesPage() {
  const [items, setItems] = useState<PackageItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    setError(null);
    apiGet<{ items: PackageItem[] }>("/v1/packages")
      .then((res) => {
        setItems(res.data.items);
      })
      .catch((e: unknown) => {
        const err = e as ApiError;
        setItems([]);
        setError(`${err.code ?? "ERROR"}: ${err.message ?? "패키지 조회 실패"}`);
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  return (
    <section className="page collection-page">
      <header className="page-head">
        <p className="page-kicker">BUNDLED BOOKING · SAGA FLOW</p>
        <div className="page-title-wrap">
          <div>
            <h2>패키지</h2>
            <p className="page-summary">
              숙소와 티켓을 한 번에 묶어 결제합니다. 일부 단계 실패 시 자동 보상으로 부분 구매가 남지 않습니다.
            </p>
          </div>
          <div className="page-metrics" aria-label="패키지 지표">
            <div>
              <strong>{items.length}</strong>
              <span>운영 중 상품</span>
            </div>
            <div>
              <strong>{items.filter((item) => item.status === "ACTIVE").length}</strong>
              <span>즉시 구매 가능</span>
            </div>
          </div>
        </div>
      </header>

      {loading && <p className="notice info">패키지 데이터를 불러오는 중입니다...</p>}
      {error && <p className="notice error">{error}</p>}
      {!loading && !error && items.length === 0 && <p className="notice warning">조회된 패키지가 없습니다.</p>}

      <ul className="product-grid">
        {items.map((item) => (
          <li className="product-card package-card" key={item.package_id}>
            <img
              className="product-thumb"
              src={`https://picsum.photos/seed/package-${item.package_id}/640/380`}
              alt={item.name}
              loading="lazy"
            />
            <div className="product-body">
              <div className="product-row">
                <span className={item.status === "ACTIVE" ? "status-pill active" : "status-pill"}>
                  {item.status}
                </span>
                <strong className="product-price">
                  {(item.price?.amount_total ?? 0).toLocaleString()} {item.price?.currency ?? "KRW"}
                </strong>
              </div>
              <h3>{item.name}</h3>
              <p className="product-copy">
                숙소 + 티켓을 트랜잭션으로 묶어 확정하는 패키지 전용 구매 플로우를 제공합니다.
              </p>
              <Link to={`/packages/${item.package_id}`} className="inline-cta">
                구성 상세 보기
              </Link>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
