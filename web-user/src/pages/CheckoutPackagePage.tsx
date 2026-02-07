import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { apiPost } from "../api/client";

type HoldResponse = {
  package_order_id: string;
  status: string;
  expires_at: string;
  components: {
    booking_id: string;
    ticket_order_id: string;
  };
};

type ApiError = {
  code?: string;
  message?: string;
};

export function CheckoutPackagePage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [packageOrderId, setPackageOrderId] = useState<string | null>(null);
  const [bookingId, setBookingId] = useState<string | null>(null);
  const [ticketOrderId, setTicketOrderId] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [remainingSeconds, setRemainingSeconds] = useState<number | null>(null);
  const [status, setStatus] = useState("대기");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!expiresAt) {
      setRemainingSeconds(null);
      return;
    }
    const tick = () => {
      const seconds = Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));
      setRemainingSeconds(seconds);
      if (seconds === 0) {
        setStatus("HOLD 만료");
      }
    };
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [expiresAt]);

  async function hold() {
    const packageId = params.get("package_id");
    if (!packageId) return;
    setError(null);
    setStatus("HOLD 생성 중");
    try {
      const res = await apiPost<HoldResponse>(
        `/v1/packages/${packageId}/holds`,
        { check_in: "2026-02-10", check_out: "2026-02-12", rooms: 1, ticket_quantity: 1 },
        { "Idempotency-Key": crypto.randomUUID(), "X-User-Id": "1001" }
      );
      setPackageOrderId(res.data.package_order_id);
      setBookingId(res.data.components.booking_id);
      setTicketOrderId(res.data.components.ticket_order_id);
      setExpiresAt(res.data.expires_at);
      setStatus("HOLD 완료");
    } catch (e) {
      const err = e as ApiError;
      setStatus("HOLD 실패");
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "패키지 hold 실패"}`);
    }
  }

  async function confirm() {
    const packageId = params.get("package_id");
    if (!packageId || !packageOrderId || remainingSeconds === 0) return;
    setError(null);
    setStatus("CONFIRM 진행 중");
    try {
      await apiPost(
        `/v1/packages/${packageId}/confirm`,
        { package_order_id: packageOrderId, payment_token: "paytok_test" },
        { "Idempotency-Key": crypto.randomUUID(), "X-User-Id": "1001" }
      );
      navigate("/booking/complete");
    } catch (e) {
      const err = e as ApiError;
      setStatus("CONFIRM 실패");
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "패키지 confirm 실패"}`);
    }
  }

  const countdownText = remainingSeconds === null
    ? "HOLD 생성 전"
    : `${Math.floor(remainingSeconds / 60)}:${String(remainingSeconds % 60).padStart(2, "0")}`;
  const isExpired = remainingSeconds === 0;

  return (
    <section className="page">
      <h2>패키지 결제</h2>
      <p>상태: {status}</p>
      <p>남은 시간: {countdownText}</p>
      {expiresAt && <p>만료시각: {expiresAt}</p>}
      {packageOrderId && (
        <div className="queue-box">
          <p>패키지 주문: {packageOrderId}</p>
          <p>숙소 HOLD: {bookingId}</p>
          <p>티켓 HOLD: {ticketOrderId}</p>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      <div className="actions">
        <button onClick={hold}>HOLD</button>
        <button disabled={!packageOrderId || isExpired} onClick={confirm}>CONFIRM</button>
      </div>
      <p className="warning">확정 실패 시 부분 구매가 남지 않도록 서버에서 보상 처리됩니다.</p>
    </section>
  );
}
