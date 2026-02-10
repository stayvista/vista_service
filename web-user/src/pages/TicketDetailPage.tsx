import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiGet } from "../api/client";

type EventItem = {
  event_id: number;
  event_date: string;
  start_time: string;
  end_time?: string | null;
  total: number;
  hold: number;
  sold: number;
};
type ApiError = { code?: string; message?: string };

export function TicketDetailPage() {
  const { id } = useParams();
  const [events, setEvents] = useState<EventItem[]>([]);
  const [quantity, setQuantity] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    setError(null);
    apiGet<{ items: EventItem[] }>(`/v1/tickets/events?product_id=${id}`)
      .then((res) => setEvents(res.data.items))
      .catch((e: unknown) => {
        const err = e as ApiError;
        setEvents([]);
        setError(`${err.code ?? "ERROR"}: ${err.message ?? "시간대 조회 실패"}`);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [id]);

  const hasAvailableEvent = useMemo(
    () => events.some((event) => (event.total - event.hold - event.sold) > 0),
    [events],
  );
  const availableCount = useMemo(
    () => events.filter((event) => (event.total - event.hold - event.sold) > 0).length,
    [events],
  );
  const soldOutCount = Math.max(0, events.length - availableCount);

  function toEventLabel(eventDate: string, startTime: string): string {
    const date = new Date(`${eventDate}T${startTime}`);
    if (Number.isNaN(date.getTime())) return `${eventDate} ${startTime.slice(0, 5)}`;
    return date.toLocaleString("ko-KR", {
      month: "long",
      day: "numeric",
      weekday: "short",
      hour: "2-digit",
      minute: "2-digit",
    });
  }

  return (
    <section className="page detail-page">
      <header className="page-head">
        <p className="page-kicker">SLOT AVAILABILITY · REAL-TIME HOLD</p>
        <div className="page-title-wrap">
          <div>
            <h2>티켓 상세</h2>
            <p className="page-summary">
              원하는 회차를 선택하고 수량을 확정하면 결제 단계에서 좌석을 임시 확보한 뒤 안전하게 결제할 수 있습니다.
            </p>
            <div className="chips">
              <span className="status-pill active">실시간 잔여 수량</span>
              <span className="status-pill">임시 확보 후 결제</span>
              <span className="status-pill">대기열 자동 재시도</span>
            </div>
          </div>
          <div className="page-metrics" aria-label="시간대 지표">
            <div>
              <strong>{events.length}</strong>
              <span>전체 회차</span>
            </div>
            <div>
              <strong>{availableCount}</strong>
              <span>구매 가능</span>
            </div>
            <div>
              <strong>{soldOutCount}</strong>
              <span>마감 회차</span>
            </div>
            <div>
              <strong>{quantity}</strong>
              <span>선택 수량</span>
            </div>
          </div>
        </div>
      </header>

      <div className="ticket-detail-layout">
        <aside className="side-panel">
          <h3>구매 옵션</h3>
          <label className="field-group">
            수량 선택
            <div className="quantity-inline">
              <button
                type="button"
                className="chip-btn"
                onClick={() => setQuantity((prev) => Math.max(1, prev - 1))}
              >
                -
              </button>
              <input
                type="number"
                min={1}
                max={10}
                value={quantity}
                onChange={(e) => {
                  const parsed = Number(e.target.value);
                  setQuantity(Number.isFinite(parsed) ? Math.max(1, Math.min(10, parsed)) : 1);
                }}
              />
              <button
                type="button"
                className="chip-btn"
                onClick={() => setQuantity((prev) => Math.min(10, prev + 1))}
              >
                +
              </button>
            </div>
          </label>
          <p className="panel-note">1회 결제 시 최대 10매까지 구매할 수 있습니다.</p>
          <p className="panel-note">결제 단계에서 임시 확보 시간이 표시되며 만료 전까지 확정할 수 있습니다.</p>
        </aside>

        <div className="ticket-slot-wrap">
          {loading && <p className="notice info">시간대 데이터를 불러오는 중입니다...</p>}
          {error && (
            <div className="notice error">
              <p>{error}</p>
              <p>서버 응답이 지연되는 경우 새로고침 후 다시 확인해 주세요.</p>
            </div>
          )}
          {!loading && !error && events.length === 0 && (
            <p className="notice warning">등록된 시간대가 없습니다.</p>
          )}
          <ul className="ticket-slot-grid">
            {events.map((event) => {
              const remaining = Math.max(0, event.total - event.hold - event.sold);
              return (
                <li className={remaining > 0 ? "ticket-slot-card" : "ticket-slot-card soldout"} key={event.event_id}>
                  <p className="slot-datetime">{toEventLabel(event.event_date, event.start_time)}</p>
                  <div className="slot-meta">
                    <span>남은 좌석 {remaining}</span>
                    <span>총 {event.total}</span>
                  </div>
                  {remaining > 0 ? (
                    <Link
                      className="inline-cta"
                      to={`/checkout/ticket?product_id=${id}&event_id=${event.event_id}&quantity=${quantity}`}
                    >
                      이 시간대로 예약
                    </Link>
                  ) : (
                    <span className="status-pill sold">매진</span>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      </div>

      {!loading && !error && !hasAvailableEvent && (
        <p className="notice warning">현재 회차가 모두 마감되었습니다. 다른 날짜 또는 다른 상품을 선택해 주세요.</p>
      )}
    </section>
  );
}
