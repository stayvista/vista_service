import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiGet } from "../api/client";

type EventItem = { event_id: number; event_date: string; start_time: string; total: number; hold: number; sold: number };

export function TicketDetailPage() {
  const { id } = useParams();
  const [events, setEvents] = useState<EventItem[]>([]);
  const [quantity, setQuantity] = useState(1);

  useEffect(() => {
    if (!id) return;
    apiGet<{ items: EventItem[] }>(`/v1/tickets/events?product_id=${id}`).then((res) => setEvents(res.data.items));
  }, [id]);

  const hasAvailableEvent = useMemo(
    () => events.some((event) => (event.total - event.hold - event.sold) > 0),
    [events],
  );

  return (
    <section className="page">
      <h2>티켓 상세</h2>
      <div className="toolbar">
        <label>
          수량
          <input
            type="number"
            min={1}
            max={10}
            value={quantity}
            onChange={(e) => setQuantity(Math.max(1, Number(e.target.value)))}
          />
        </label>
      </div>
      <ul className="card-list">
        {events.map((event) => (
          <li className="card" key={event.event_id}>
            <p>{event.event_date} {event.start_time}</p>
            <p>잔여 {Math.max(0, event.total - event.hold - event.sold)}</p>
            {(event.total - event.hold - event.sold) > 0 ? (
              <Link to={`/checkout/ticket?product_id=${id}&event_id=${event.event_id}&quantity=${quantity}`}>구매</Link>
            ) : (
              <p className="warning">매진</p>
            )}
          </li>
        ))}
      </ul>
      {!hasAvailableEvent && (
        <p className="warning">현재 시간대가 모두 매진되었습니다. 다른 날짜를 선택해 주세요.</p>
      )}
    </section>
  );
}
