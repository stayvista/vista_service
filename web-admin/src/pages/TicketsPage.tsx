import { FormEvent, useEffect, useMemo, useState } from "react";
import { apiGet, apiPost, apiPut } from "../api/client";

type ApiError = { code?: string; message?: string };
type Product = { product_id: number; name: string; category: string; city?: string; status: string };
type EventItem = {
  event_id: number;
  product_id: number;
  event_date: string;
  start_time: string;
  end_time?: string | null;
  status: string;
  total: number;
  hold: number;
  sold: number;
};

export function TicketsPage() {
  const [productName, setProductName] = useState("Wanderly City Pass");
  const [productCategory, setProductCategory] = useState("ATTRACTION");
  const [productCity, setProductCity] = useState("Seoul");
  const [products, setProducts] = useState<Product[]>([]);
  const [productId, setProductId] = useState<number | null>(null);
  const [eventDate, setEventDate] = useState("2026-03-01");
  const [startTime, setStartTime] = useState("10:00");
  const [endTime, setEndTime] = useState("18:00");
  const [events, setEvents] = useState<EventItem[]>([]);
  const [eventDateFilter, setEventDateFilter] = useState("");
  const [eventId, setEventId] = useState<number | null>(null);
  const [inventory, setInventory] = useState(100);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function loadProducts() {
    const res = await apiGet<{ items: Product[] }>("/v1/tickets/products");
    setProducts(res.data.items ?? []);
    if (!productId && res.data.items?.length) {
      setProductId(res.data.items[0].product_id);
    }
  }

  async function loadEvents(nextProductId: number | null) {
    if (!nextProductId) {
      setEvents([]);
      return;
    }
    const query = new URLSearchParams({ product_id: String(nextProductId) });
    if (eventDateFilter) query.set("date", eventDateFilter);
    const res = await apiGet<{ items: EventItem[] }>(`/v1/tickets/events?${query.toString()}`);
    setEvents(res.data.items ?? []);
  }

  useEffect(() => {
    void loadProducts();
  }, []);

  useEffect(() => {
    void loadEvents(productId);
  }, [productId, eventDateFilter]);

  async function createProduct(e: FormEvent) {
    e.preventDefault();
    setMessage(null);
    setError(null);
    try {
      const res = await apiPost<{ product_id: number }>("/v1/admin/tickets/products", {
        name: productName,
        category: productCategory,
        status: "ACTIVE",
        city: productCity,
      });
      setProductId(res.data.product_id);
      setMessage(`상품 생성 완료: ${res.data.product_id}`);
      await loadProducts();
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "상품 생성 실패"}`);
    }
  }

  async function createEvent() {
    if (!productId) return;
    setMessage(null);
    setError(null);
    try {
      const res = await apiPost<{ event_id: number }>(`/v1/admin/tickets/products/${productId}/events`, {
        event_date: eventDate,
        start_time: startTime,
        end_time: endTime || null,
        status: "ACTIVE",
      });
      setEventId(res.data.event_id);
      setMessage(`이벤트 생성 완료: ${res.data.event_id}`);
      await loadEvents(productId);
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "이벤트 생성 실패"}`);
    }
  }

  async function setEventInventory() {
    if (!eventId) return;
    setMessage(null);
    setError(null);
    try {
      await apiPut(`/v1/admin/tickets/events/${eventId}/inventory`, { total: inventory });
      setMessage("이벤트 재고 설정 완료");
      await loadEvents(productId);
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "재고 설정 실패"}`);
    }
  }

  const selectedProduct = useMemo(
    () => products.find((item) => item.product_id === productId) ?? null,
    [products, productId],
  );

  return (
    <div>
      <h2>티켓 관리</h2>
      <form className="row-form" onSubmit={createProduct}>
        <input value={productName} onChange={(e) => setProductName(e.target.value)} />
        <select value={productCategory} onChange={(e) => setProductCategory(e.target.value)}>
          <option value="ATTRACTION">ATTRACTION</option>
          <option value="TOUR">TOUR</option>
          <option value="EXHIBITION">EXHIBITION</option>
        </select>
        <input value={productCity} onChange={(e) => setProductCity(e.target.value)} />
        <button type="submit">상품 생성</button>
      </form>
      <div className="row-form">
        <label>
          상품 선택
          <select
            value={productId ?? ""}
            onChange={(e) => setProductId(e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">선택하세요</option>
            {products.map((item) => (
              <option key={item.product_id} value={item.product_id}>
                #{item.product_id} {item.name}
              </option>
            ))}
          </select>
        </label>
      </div>
      {selectedProduct && (
        <p>
          선택 상품: #{selectedProduct.product_id} {selectedProduct.name} ({selectedProduct.category}, {selectedProduct.city})
        </p>
      )}
      <div className="row-form">
        <input type="date" value={eventDate} onChange={(e) => setEventDate(e.target.value)} />
        <input type="time" value={startTime} onChange={(e) => setStartTime(e.target.value)} />
        <input type="time" value={endTime} onChange={(e) => setEndTime(e.target.value)} />
        <button onClick={createEvent} disabled={!productId}>이벤트 생성</button>
        <select value={eventId ?? ""} onChange={(e) => setEventId(e.target.value ? Number(e.target.value) : null)}>
          <option value="">이벤트 선택</option>
          {events.map((item) => (
            <option key={item.event_id} value={item.event_id}>
              #{item.event_id} {item.event_date} {item.start_time}
            </option>
          ))}
        </select>
        <input type="number" value={inventory} onChange={(e) => setInventory(Number(e.target.value))} />
        <button onClick={setEventInventory} disabled={!eventId}>재고 설정</button>
      </div>
      <div className="row-form">
        <input
          type="date"
          value={eventDateFilter}
          onChange={(e) => setEventDateFilter(e.target.value)}
          placeholder="이벤트 날짜 필터"
        />
        <button type="button" onClick={() => setEventDateFilter("")}>날짜 필터 초기화</button>
      </div>
      <ul className="table">
        {events.map((event) => (
          <li key={event.event_id}>
            <span>#{event.event_id} {event.event_date} {event.start_time} ({event.status})</span>
            <span>total {event.total}</span>
            <span>sold {event.sold}</span>
          </li>
        ))}
      </ul>
      {message && <p className="success">{message}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
