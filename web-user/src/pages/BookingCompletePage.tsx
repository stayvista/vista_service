import { Link, useSearchParams } from "react-router-dom";

export function BookingCompletePage() {
  const [params] = useSearchParams();
  const type = params.get("type") ?? "booking";
  const bookingId = params.get("booking_id");
  const orderId = params.get("order_id");
  const packageOrderId = params.get("package_order_id");

  return (
    <section className="page">
      <h2>구매 완료</h2>
      <p>결제가 확인되었고 최종 확정이 완료되었습니다.</p>
      <div className="queue-box">
        <p>구매 유형: {type.toUpperCase()}</p>
        {bookingId && <p>숙소 예약번호: {bookingId}</p>}
        {orderId && <p>티켓 주문번호: {orderId}</p>}
        {packageOrderId && <p>패키지 주문번호: {packageOrderId}</p>}
      </div>
      <div className="actions">
        <Link to="/" className="cta-link">홈으로</Link>
        <Link to="/search" className="cta-link">숙소 더보기</Link>
        <Link to="/tickets" className="cta-link">티켓 더보기</Link>
      </div>
    </section>
  );
}
