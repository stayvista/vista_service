import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiGet } from "../api/client";

type PackageDetail = {
  package_id: number;
  name: string;
  status: string;
  components: Array<{
    type: string;
    room_type_id?: number;
    event_id?: number;
    nights?: number;
    rooms?: number;
    quantity?: number;
  }>;
  price: {
    currency: string;
    amount_total: number;
  };
};

type ApiError = {
  code?: string;
  message?: string;
};

function componentTitle(type: string): string {
  if (type === "ACCOMMODATION") return "숙소";
  if (type === "TICKET") return "티켓";
  return type;
}

export function PackageDetailPage() {
  const { id } = useParams();
  const [item, setItem] = useState<PackageDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    setError(null);
    apiGet<PackageDetail>(`/v1/packages/${id}`)
      .then((res) => setItem(res.data))
      .catch((e: unknown) => {
        const err = e as ApiError;
        setItem(null);
        setError(`${err.code ?? "ERROR"}: ${err.message ?? "패키지 상세 조회 실패"}`);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [id]);

  return (
    <section className="page detail-page">
      <header className="page-head">
        <p className="page-kicker">ATOMIC PURCHASE · COMPENSATION READY</p>
        <div className="page-title-wrap">
          <div>
            <h2>{item?.name ?? "패키지 상세"}</h2>
            <p className="page-summary">
              패키지 확정 중 일부 단계 실패 시 서버에서 자동 보상되어 부분 구매가 남지 않도록 설계되어 있습니다.
            </p>
          </div>
          <div className="page-metrics" aria-label="패키지 가격 정보">
            <div>
              <strong>{(item?.price.amount_total ?? 0).toLocaleString()}</strong>
              <span>{item?.price.currency ?? "KRW"}</span>
            </div>
            <div>
              <strong>{item?.components.length ?? 0}</strong>
              <span>구성 항목</span>
            </div>
          </div>
        </div>
      </header>

      {loading && <p className="notice info">패키지 상세를 불러오는 중입니다...</p>}
      {error && <p className="notice error">{error}</p>}

      {!loading && !error && item && (
        <>
          <p className={item.status === "ACTIVE" ? "status-pill active" : "status-pill"}>{item.status}</p>
          <ul className="component-grid">
            {item.components.map((component, idx) => (
              <li key={`${component.type}-${idx}`} className="component-card">
                <p className="component-type">{component.type}</p>
                <h3>{componentTitle(component.type)}</h3>
                {component.room_type_id && <p>room_type_id: {component.room_type_id}</p>}
                {component.event_id && <p>event_id: {component.event_id}</p>}
                {component.nights != null && <p>nights: {component.nights}</p>}
                {component.rooms != null && <p>rooms: {component.rooms}</p>}
                {component.quantity != null && <p>quantity: {component.quantity}</p>}
              </li>
            ))}
          </ul>
          <div className="detail-actions">
            <Link to={`/checkout/package?package_id=${id}`} className="inline-cta">
              패키지 구매하기
            </Link>
          </div>
        </>
      )}
    </section>
  );
}
