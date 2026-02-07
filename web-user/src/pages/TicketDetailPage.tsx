import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiGet } from "../api/client";

type EventItem = { event_id: number; event_date: string; start_time: string; total: number; sold: number };

export function TicketDetailPage() {
  const { id } = useParams();
  const [events, setEvents] = useState<EventItem[]>([]);

  useEffect(() => {
    if (!id) return;
    apiGet<{ items: EventItem[] }>(`/v1/tickets/events?product_id=${id}`).then((res) => setEvents(res.data.items));
  }, [id]);

  return (
    <section className="page">
      <h2>티켓 상세</h2>
      <ul className="card-list">
        {events.map((event) => (
          <li className="card" key={event.event_id}>
            <p>{event.event_date} {event.start_time}</p>
            <p>잔여 {event.total - event.sold}</p>
            <Link to={`/checkout/ticket?event_id=${event.event_id}`}>구매</Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
