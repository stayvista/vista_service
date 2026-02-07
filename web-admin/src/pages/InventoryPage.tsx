import { FormEvent, useState } from "react";
import { apiPut } from "../api/client";

export function InventoryPage() {
  const [roomTypeId, setRoomTypeId] = useState(1);
  const [startDate, setStartDate] = useState("2026-02-10");
  const [endDate, setEndDate] = useState("2026-02-20");
  const [total, setTotal] = useState(20);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    await apiPut(`/v1/admin/room-types/${roomTypeId}/inventory`, {
      start_date: startDate,
      end_date: endDate,
      total,
    });
    alert("재고가 반영되었습니다.");
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
    </div>
  );
}
