import { FormEvent, useState } from "react";
import { useParams } from "react-router-dom";
import { apiPatch, apiPost } from "../api/client";

type ApiError = { code?: string; message?: string };

export function PropertyDetailPage() {
  const { id } = useParams();
  const [status, setStatus] = useState("ACTIVE");
  const [roomName, setRoomName] = useState("Standard Double");
  const [price, setPrice] = useState(120000);
  const [roomTypeId, setRoomTypeId] = useState<number>(1);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function updateProperty(e: FormEvent) {
    e.preventDefault();
    if (!id) return;
    setMessage(null);
    setError(null);
    try {
      await apiPatch(`/v1/admin/properties/${id}`, { status });
      setMessage("숙소 상태가 업데이트되었습니다.");
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "숙소 수정 실패"}`);
    }
  }

  async function createRoomType(e: FormEvent) {
    e.preventDefault();
    if (!id) return;
    setMessage(null);
    setError(null);
    try {
      await apiPost(`/v1/admin/properties/${id}/room-types`, {
        name: roomName,
        max_guests: 2,
        base_price: { currency: "KRW", amount: price },
        status,
      });
      setMessage("룸타입이 생성되었습니다.");
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "룸타입 생성 실패"}`);
    }
  }

  async function updateRoomType(e: FormEvent) {
    e.preventDefault();
    setMessage(null);
    setError(null);
    try {
      await apiPatch(`/v1/admin/room-types/${roomTypeId}`, {
        name: roomName,
        max_guests: 2,
        base_price: { currency: "KRW", amount: price },
        status,
      });
      setMessage("룸타입이 수정되었습니다.");
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "룸타입 수정 실패"}`);
    }
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
      <form className="row-form" onSubmit={updateRoomType}>
        <input
          type="number"
          value={roomTypeId}
          onChange={(e) => setRoomTypeId(Number(e.target.value))}
          placeholder="room_type_id"
        />
        <button type="submit">룸타입 수정</button>
      </form>
      {message && <p className="success">{message}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
