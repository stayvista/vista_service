import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiGet } from "../api/client";

type PackageItem = {
  package_id: number;
  name: string;
  status: string;
  price: { amount_total: number; currency?: string };
  image_url?: string | null;
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
        <p className="page-kicker">BUNDLED BOOKING · ONE-TAP ITINERARY</p>
        <div className="page-title-wrap">
          <div>
            <h2>패키지</h2>
            <p className="page-summary">
              숙소와 티켓을 한 번에 결제해 이동 동선을 줄이고, 실패 시 자동 보상으로 부분 구매가 남지 않도록 설계했습니다.
            </p>
            <div className="chips">
              <span className="status-pill active">숙소+티켓 동시 확정</span>
              <span className="status-pill">트랜잭션 보상 처리</span>
              <span className="status-pill">결제 단계 단축</span>
            </div>
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
            <div>
              <strong>{items.reduce((sum, item) => sum + (item.price?.amount_total ?? 0), 0).toLocaleString()}</strong>
              <span>전체 패키지 기준가</span>
            </div>
            <div>
              <strong>{items.length > 0 ? "ON" : "OFF"}</strong>
              <span>판매 상태</span>
            </div>
          </div>
        </div>
      </header>

      {loading && <p className="notice info">패키지 데이터를 불러오는 중입니다...</p>}
      {error && (
        <div className="notice error">
          <p>{error}</p>
          <p>잠시 후 다시 시도하거나 네트워크 연결 상태를 확인해 주세요.</p>
        </div>
      )}
      {!loading && !error && items.length === 0 && <p className="notice warning">조회된 패키지가 없습니다.</p>}

      <ul className="product-grid">
        {items.map((item) => (
          <li className="product-card package-card" key={item.package_id}>
            {item.image_url ? (
              <img
                className="product-thumb"
                src={item.image_url}
                alt={item.name}
                loading="lazy"
              />
            ) : (
              <div className="product-thumb product-thumb-empty">이미지 준비중</div>
            )}
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
                숙소와 티켓을 한 번에 확정하는 패키지 전용 결제 플로우를 제공합니다.
              </p>
              <div className="chips">
                <span className="status-pill active">체크아웃 1회</span>
                <span className="status-pill">부분 구매 방지</span>
              </div>
              <Link to={`/packages/${item.package_id}`} className="inline-cta">
                구성 확인 후 예약하기
              </Link>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
