import { FormEvent, useState } from "react";
import { apiPut } from "../api/client";

type ApiError = { code?: string; message?: string; details?: Record<string, unknown> };

export function InventoryPage() {
  const [roomTypeId, setRoomTypeId] = useState(1);
  const [startDate, setStartDate] = useState("2026-02-10");
  const [endDate, setEndDate] = useState("2026-02-20");
  const [total, setTotal] = useState(20);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setMessage(null);
    setError(null);
    try {
      await apiPut(`/v1/admin/room-types/${roomTypeId}/inventory`, {
        start_date: startDate,
        end_date: endDate,
        total,
      });
      setMessage("재고가 반영되었습니다.");
    } catch (e) {
      const err = e as ApiError;
      if (err.code === "INVENTORY_TOTAL_BELOW_COMMITTED") {
        const conflictDate = typeof err.details?.conflict_date === "string" ? err.details.conflict_date : undefined;
        setError(`INVENTORY_TOTAL_BELOW_COMMITTED: ${conflictDate ?? "일부 날짜의 hold/sold가 현재 total보다 큽니다."}`);
        return;
      }
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "재고 반영 실패"}`);
    }
  }

  return (
    <div>
      <h2>재고 캘린더(범위 적용)</h2>
      <form className="row-form" onSubmit={onSubmit}>
        <input type="number" value={roomTypeId} onChange={(e) => setRoomTypeId(Number(e.target.value))} />
        <input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
        <input type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} />
        <input type="number" value={total} onChange={(e) => setTotal(Number(e.target.value))} />
        <button type="submit">적용</button>
      </form>
      {message && <p className="success">{message}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
