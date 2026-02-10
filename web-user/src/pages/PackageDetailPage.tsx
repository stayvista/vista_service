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
        <p className="page-kicker">ATOMIC PURCHASE · SAFE TRANSACTION</p>
        <div className="page-title-wrap">
          <div>
            <h2>{item?.name ?? "패키지 상세"}</h2>
            <p className="page-summary">
              패키지 결제는 숙소와 티켓을 묶어 동시에 처리하며, 중간 실패 시 자동 보상으로 주문 정합성을 유지합니다.
            </p>
            <div className="chips">
              <span className="status-pill active">동시 확정</span>
              <span className="status-pill">자동 롤백</span>
              <span className="status-pill">예약/티켓 통합 관리</span>
            </div>
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
            <div>
              <strong>{item?.status ?? "-"}</strong>
              <span>판매 상태</span>
            </div>
            <div>
              <strong>{item ? "준비 완료" : "-"}</strong>
              <span>즉시 결제 가능</span>
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
                {component.room_type_id && <p>객실 타입 ID: {component.room_type_id}</p>}
                {component.event_id && <p>이벤트 ID: {component.event_id}</p>}
                {component.nights != null && <p>숙박 일수: {component.nights}박</p>}
                {component.rooms != null && <p>객실 수: {component.rooms}개</p>}
                {component.quantity != null && <p>티켓 수량: {component.quantity}매</p>}
              </li>
            ))}
          </ul>
          <div className="queue-box">
            <p>구매 안내</p>
            <p>1. 결제 단계에서 숙소/티켓 재고를 동시에 임시 확보합니다.</p>
            <p>2. 확정 중 실패가 발생하면 자동으로 보상 처리되어 부분 결제가 남지 않습니다.</p>
          </div>
          <div className="detail-actions">
            <Link to={`/checkout/package?package_id=${id}`} className="inline-cta">
              이 구성으로 예약 진행
            </Link>
          </div>
        </>
      )}
    </section>
  );
}
