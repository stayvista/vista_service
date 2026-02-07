import { useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { apiPost } from "../api/client";

export function CheckoutBookingPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [status, setStatus] = useState("대기");
  const [bookingId, setBookingId] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const holdBody = useMemo(() => ({
    room_type_id: Number(params.get("room_type_id")),
    check_in: params.get("check_in") ?? "2026-02-10",
    check_out: params.get("check_out") ?? "2026-02-12",
    rooms: 1,
    guests: { adults: 2, children: 0 },
    price: { currency: "KRW", amount_total: 120000 },
  }), [params]);

  async function createHold() {
    setError(null);
    try {
      const response = await apiPost<{ booking_id: string; expires_at: string }>(
        "/v1/bookings/holds",
        holdBody,
        { "Idempotency-Key": crypto.randomUUID(), "X-User-Id": "1001" }
      );
      setBookingId(response.data.booking_id);
      setExpiresAt(response.data.expires_at);
      setStatus("HOLD 완료");
    } catch (e) {
      const err = e as { code?: string; message?: string };
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "hold 실패"}`);
      if (err.code === "QUEUE_REQUIRED") {
        setStatus("대기열 필요");
      }
    }
  }

  async function confirm() {
    if (!bookingId) return;
    setError(null);
    try {
      await apiPost(
        `/v1/bookings/${bookingId}/confirm`,
        { payment_method: "CARD", payment_token: "paytok_test", agree_terms: true },
        { "Idempotency-Key": crypto.randomUUID(), "X-User-Id": "1001" }
      );
      navigate("/booking/complete");
    } catch (e) {
      const err = e as { code?: string; message?: string };
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "confirm 실패"}`);
    }
  }

  return (
    <section className="page">
      <h2>예약 체크아웃</h2>
      <p>상태: {status}</p>
      {expiresAt && <p>만료시각: {expiresAt}</p>}
      {error && <p className="error">{error}</p>}
      <div className="actions">
        <button onClick={createHold}>HOLD 생성</button>
        <button disabled={!bookingId} onClick={confirm}>CONFIRM</button>
      </div>
    </section>
  );
}
