import { FormEvent, useState } from "react";
import { useParams } from "react-router-dom";
import { apiPatch, apiPost } from "../api/client";

export function PropertyDetailPage() {
  const { id } = useParams();
  const [status, setStatus] = useState("ACTIVE");
  const [roomName, setRoomName] = useState("Standard Double");
  const [price, setPrice] = useState(120000);

  async function updateProperty(e: FormEvent) {
    e.preventDefault();
    if (!id) return;
    await apiPatch(`/v1/admin/properties/${id}`, { status });
    alert("숙소 상태가 업데이트되었습니다.");
  }

  async function createRoomType(e: FormEvent) {
    e.preventDefault();
    if (!id) return;
    await apiPost(`/v1/admin/properties/${id}/room-types`, {
      name: roomName,
      max_guests: 2,
      base_price: { currency: "KRW", amount: price },
      status,
    });
    alert("룸타입이 생성되었습니다.");
  }

  return (
    <div>
      <h2>숙소 상세 #{id}</h2>
      <form className="row-form" onSubmit={updateProperty}>
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="ACTIVE">ACTIVE</option>
          <option value="INACTIVE">INACTIVE</option>
        </select>
        <button type="submit">숙소 상태 저장</button>
      </form>
      <form className="row-form" onSubmit={createRoomType}>
        <input value={roomName} onChange={(e) => setRoomName(e.target.value)} />
        <input type="number" value={price} onChange={(e) => setPrice(Number(e.target.value))} />
        <button type="submit">룸타입 추가</button>
      </form>
    </div>
  );
}
