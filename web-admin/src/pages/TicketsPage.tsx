import { FormEvent, useState } from "react";
import { apiPost, apiPut } from "../api/client";

type ApiError = { code?: string; message?: string };

export function TicketsPage() {
  const [productName, setProductName] = useState("Wanderly City Pass");
  const [productId, setProductId] = useState<number | null>(null);
  const [eventId, setEventId] = useState<number | null>(null);
  const [inventory, setInventory] = useState(100);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function createProduct(e: FormEvent) {
    e.preventDefault();
    setMessage(null);
    setError(null);
    try {
      const res = await apiPost<{ product_id: number }>("/v1/admin/tickets/products", {
        name: productName,
        category: "ATTRACTION",
        status: "ACTIVE",
        city: "Seoul",
      });
      setProductId(res.data.product_id);
      setMessage(`상품 생성 완료: ${res.data.product_id}`);
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
        event_date: "2026-03-01",
        start_time: "10:00:00",
        end_time: "18:00:00",
        status: "ACTIVE",
      });
      setEventId(res.data.event_id);
      setMessage(`이벤트 생성 완료: ${res.data.event_id}`);
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
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "재고 설정 실패"}`);
    }
  }

  return (
    <div>
      <h2>티켓 관리</h2>
      <form className="row-form" onSubmit={createProduct}>
        <input value={productName} onChange={(e) => setProductName(e.target.value)} />
        <button type="submit">상품 생성</button>
      </form>
      <div className="row-form">
        <button onClick={createEvent} disabled={!productId}>이벤트 생성</button>
        <input type="number" value={inventory} onChange={(e) => setInventory(Number(e.target.value))} />
        <button onClick={setEventInventory} disabled={!eventId}>재고 설정</button>
      </div>
      {message && <p className="success">{message}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
