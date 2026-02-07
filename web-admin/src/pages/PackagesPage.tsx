import { FormEvent, useEffect, useState } from "react";
import { apiGet, apiPost } from "../api/client";

type ApiError = { code?: string; message?: string };
type PackageSummary = {
  package_id: number;
  name: string;
  status: string;
  price: { currency: string; amount_total: number };
};
type PackageOrder = {
  package_order_id: string;
  package_id: number;
  user_id: number;
  status: string;
  booking_id?: string | null;
  ticket_order_id?: string | null;
  expires_at?: string | null;
  created_at?: string | null;
};

export function PackagesPage() {
  const [name, setName] = useState("Seed Weekend Combo");
  const [amount, setAmount] = useState(189000);
  const [roomTypeId, setRoomTypeId] = useState(200001);
  const [eventId, setEventId] = useState(400001);
  const [items, setItems] = useState<PackageSummary[]>([]);
  const [orders, setOrders] = useState<PackageOrder[]>([]);
  const [orderStatus, setOrderStatus] = useState("ALL");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    const res = await apiGet<{ items: PackageSummary[] }>("/v1/packages");
    setItems(res.data.items ?? []);
  }

  async function loadOrders() {
    const query = new URLSearchParams({ limit: "50" });
    if (orderStatus !== "ALL") query.set("status", orderStatus);
    const res = await apiGet<{ items: PackageOrder[] }>(`/v1/admin/packages/orders?${query.toString()}`);
    setOrders(res.data.items ?? []);
  }

  useEffect(() => {
    void load();
    void loadOrders();
  }, []);

  useEffect(() => {
    void loadOrders();
  }, [orderStatus]);

  async function createPackage(e: FormEvent) {
    e.preventDefault();
    setMessage(null);
    setError(null);
    try {
      const res = await apiPost<{ package_id: number }>("/v1/admin/packages", {
        name,
        status: "ACTIVE",
        price: { currency: "KRW", amount_total: amount },
        components: [
          {
            type: "ACCOMMODATION",
            room_type_id: roomTypeId,
            nights: 2,
            rooms: 1,
          },
          {
            type: "TICKET",
            event_id: eventId,
            quantity: 1,
          },
        ],
      });
      setMessage(`패키지 생성 완료: #${res.data.package_id}`);
      await load();
      await loadOrders();
    } catch (e) {
      const apiError = e as ApiError;
      setError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "패키지 생성 실패"}`);
    }
  }

  return (
    <div>
      <h2>패키지 관리</h2>
      <form className="row-form" onSubmit={createPackage}>
        <input value={name} onChange={(e) => setName(e.target.value)} />
        <input type="number" value={amount} onChange={(e) => setAmount(Number(e.target.value))} />
        <input type="number" value={roomTypeId} onChange={(e) => setRoomTypeId(Number(e.target.value))} />
        <input type="number" value={eventId} onChange={(e) => setEventId(Number(e.target.value))} />
        <button type="submit">패키지 생성</button>
      </form>
      {message && <p className="success">{message}</p>}
      {error && <p className="error">{error}</p>}
      <ul className="table">
        {items.map((item) => (
          <li key={item.package_id}>
            <span>#{item.package_id} {item.name}</span>
            <span>{item.status}</span>
            <span>{item.price.currency} {item.price.amount_total}</span>
          </li>
        ))}
      </ul>
      <h3>패키지 주문 모니터링</h3>
      <div className="row-form">
        <select value={orderStatus} onChange={(e) => setOrderStatus(e.target.value)}>
          <option value="ALL">ALL</option>
          <option value="HOLD">HOLD</option>
          <option value="CONFIRMED">CONFIRMED</option>
          <option value="FAILED">FAILED</option>
          <option value="EXPIRED">EXPIRED</option>
        </select>
        <button type="button" onClick={() => { void loadOrders(); }}>새로고침</button>
      </div>
      <ul className="table">
        {orders.map((order) => (
          <li key={order.package_order_id}>
            <span>
              {order.package_order_id} / pkg#{order.package_id} / user#{order.user_id}
            </span>
            <span>{order.status}</span>
            <span>{order.booking_id ?? "-"} · {order.ticket_order_id ?? "-"}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
