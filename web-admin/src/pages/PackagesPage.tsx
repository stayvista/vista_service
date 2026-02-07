import { FormEvent, useEffect, useState } from "react";
import { apiGet, apiPost } from "../api/client";

type ApiError = { code?: string; message?: string };
type PackageSummary = {
  package_id: number;
  name: string;
  status: string;
  price: { currency: string; amount_total: number };
};

export function PackagesPage() {
  const [name, setName] = useState("Seed Weekend Combo");
  const [amount, setAmount] = useState(189000);
  const [roomTypeId, setRoomTypeId] = useState(200001);
  const [eventId, setEventId] = useState(400001);
  const [items, setItems] = useState<PackageSummary[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    const res = await apiGet<{ items: PackageSummary[] }>("/v1/packages");
    setItems(res.data.items ?? []);
  }

  useEffect(() => {
    void load();
  }, []);

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
    </div>
  );
}
