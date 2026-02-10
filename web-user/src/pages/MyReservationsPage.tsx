import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { apiGet } from "../api/client";
import { getAuthUser } from "../auth/session";

type ApiError = { code?: string; message?: string };

type MyReservationResponse = {
  user: {
    user_id: number;
    name: string;
    email: string;
  };
  counts: {
    total: number;
    booking: number;
    ticket: number;
    package_count: number;
  };
  items: Array<{
    type: "BOOKING" | "TICKET" | "PACKAGE";
    reservation_id: string;
    status: string;
    title: string;
    subtitle: string;
    amount: { currency: string; amount_total: number };
    created_at: string;
    expires_at?: string | null;
    confirmed_at?: string | null;
    booking_id?: string | null;
    order_id?: string | null;
    package_order_id?: string | null;
  }>;
};

function formatDateTime(value?: string | null): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("ko-KR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function statusTone(status: string): "active" | "sold" | "" {
  if (status === "CONFIRMED" || status === "BOOKED") return "active";
  if (status === "FAILED" || status === "EXPIRED" || status === "CANCELED") return "sold";
  return "";
}

export function MyReservationsPage() {
  const authUser = getAuthUser();
  const authUserId = authUser?.userId ?? null;
  const [data, setData] = useState<MyReservationResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!authUserId) return;
    setLoading(true);
    setError(null);
    apiGet<MyReservationResponse>("/v1/me/reservations?limit=100")
      .then((response) => {
        setData(response.data);
      })
      .catch((e: unknown) => {
        const apiError = (typeof e === "object" && e !== null ? e : {}) as ApiError;
        setData(null);
        setError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "예약 목록 조회 실패"}`);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [authUserId]);

  const displayUserName = useMemo(() => {
    if (data?.user.name) return data.user.name;
    return authUser?.name ?? "-";
  }, [authUser?.name, data?.user.name]);

  if (!authUser) {
    return (
      <section className="page">
        <p className="notice warning">로그인 후 내 예약을 확인할 수 있습니다.</p>
        <Link to="/login" className="inline-cta">로그인 하러 가기</Link>
      </section>
    );
  }

  return (
    <section className="page collection-page">
      <header className="page-head">
        <p className="page-kicker">MY RESERVATIONS · ACCOUNT HISTORY</p>
        <div className="page-title-wrap">
          <div>
            <h2>내 예약</h2>
            <p className="page-summary">
              예약자: {displayUserName} (#{data?.user.user_id ?? authUser.userId}) · {data?.user.email ?? authUser.email ?? "-"}
            </p>
          </div>
          <div className="page-metrics">
            <div>
              <strong>{data?.counts.total ?? 0}</strong>
              <span>전체 예약</span>
            </div>
            <div>
              <strong>{data?.counts.booking ?? 0}</strong>
              <span>숙소 예약</span>
            </div>
            <div>
              <strong>{data?.counts.ticket ?? 0}</strong>
              <span>티켓 예약</span>
            </div>
            <div>
              <strong>{data?.counts.package_count ?? 0}</strong>
              <span>패키지 예약</span>
            </div>
          </div>
        </div>
      </header>

      {loading && <p className="notice info">예약 목록을 불러오는 중입니다...</p>}
      {error && <p className="notice error">{error}</p>}
      {!loading && !error && (data?.items.length ?? 0) === 0 && (
        <p className="notice warning">아직 예약 내역이 없습니다.</p>
      )}

      <ul className="product-grid reservation-grid">
        {(data?.items ?? []).map((item) => (
          <li key={`${item.type}-${item.reservation_id}`} className="product-card reservation-card">
            <div className="product-body">
              <div className="product-row">
                <p className="product-type">{item.type}</p>
                <span className={statusTone(item.status) ? `status-pill ${statusTone(item.status)}` : "status-pill"}>
                  {item.status}
                </span>
              </div>
              <h3>{item.title}</h3>
              <p className="product-copy">{item.subtitle}</p>
              <dl className="mini-meta">
                <div>
                  <dt>예약번호</dt>
                  <dd>{item.reservation_id}</dd>
                </div>
                <div>
                  <dt>결제금액</dt>
                  <dd>{item.amount.amount_total.toLocaleString()} {item.amount.currency}</dd>
                </div>
                <div>
                  <dt>생성시각</dt>
                  <dd>{formatDateTime(item.created_at)}</dd>
                </div>
                <div>
                  <dt>확정시각</dt>
                  <dd>{formatDateTime(item.confirmed_at)}</dd>
                </div>
              </dl>
              {item.expires_at && <p className="panel-note">임시확보 만료: {formatDateTime(item.expires_at)}</p>}
              <div className="actions">
                {item.type === "BOOKING" && item.booking_id && (
                  <Link to={`/booking/complete?type=booking&booking_id=${item.booking_id}`} className="inline-cta">
                    상세 보기
                  </Link>
                )}
                {item.type === "TICKET" && item.order_id && (
                  <Link to={`/booking/complete?type=ticket&order_id=${item.order_id}`} className="inline-cta">
                    상세 보기
                  </Link>
                )}
                {item.type === "PACKAGE" && item.package_order_id && (
                  <Link to={`/booking/complete?type=package&package_order_id=${item.package_order_id}`} className="inline-cta">
                    상세 보기
                  </Link>
                )}
              </div>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
