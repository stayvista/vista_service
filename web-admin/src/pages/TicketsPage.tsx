import { FormEvent, useState } from "react";
import { apiPost, apiPut } from "../api/client";

export function TicketsPage() {
  const [productName, setProductName] = useState("Wanderly City Pass");
  const [productId, setProductId] = useState<number | null>(null);
  const [eventId, setEventId] = useState<number | null>(null);
  const [inventory, setInventory] = useState(100);

  async function createProduct(e: FormEvent) {
    e.preventDefault();
    const res = await apiPost<{ product_id: number }>("/v1/admin/tickets/products", {
      name: productName,
      category: "ATTRACTION",
      status: "ACTIVE",
      city: "Seoul",
    });
    setProductId(res.data.product_id);
  }

  async function createEvent() {
    if (!productId) return;
    const res = await apiPost<{ event_id: number }>(`/v1/admin/tickets/products/${productId}/events`, {
      event_date: "2026-03-01",
      start_time: "10:00:00",
      end_time: "18:00:00",
      status: "ACTIVE",
    });
    setEventId(res.data.event_id);
  }

  async function setEventInventory() {
    if (!eventId) return;
    await apiPut(`/v1/admin/tickets/events/${eventId}/inventory`, { total: inventory });
    alert("이벤트 재고 설정 완료");
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
    </div>
  );
}
