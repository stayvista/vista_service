import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { apiGet } from "../api/client";

type VoucherItem = {
  voucher_id: string;
  sequence_no: number;
  status: string;
  qr_payload: string;
  issued_at?: string | null;
  redeemed_at?: string | null;
};
type VoucherListData = {
  order_id: string;
  items: VoucherItem[];
};
type ApiError = {
  code?: string;
  message?: string;
};

export function BookingCompletePage() {
  const [params] = useSearchParams();
  const type = params.get("type") ?? "booking";
  const bookingId = params.get("booking_id");
  const orderId = params.get("order_id");
  const packageOrderId = params.get("package_order_id");
  const [vouchers, setVouchers] = useState<VoucherItem[]>([]);
  const [voucherState, setVoucherState] = useState<"idle" | "loading" | "done" | "error">("idle");
  const [voucherError, setVoucherError] = useState<string | null>(null);

  useEffect(() => {
    if (!orderId) return;
    setVoucherState("loading");
    setVoucherError(null);
    apiGet<VoucherListData>(
      `/v1/tickets/orders/${orderId}/vouchers`
    )
      .then((res) => {
        setVouchers(res.data.items);
        setVoucherState("done");
      })
      .catch((error: unknown) => {
        const apiError = (typeof error === "object" && error !== null ? error : {}) as ApiError;
        setVoucherError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "바우처 조회 실패"}`);
        setVoucherState("error");
      });
  }, [orderId]);

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
      {orderId && (
        <section className="voucher-section">
          <h3>발급 바우처</h3>
          {voucherState === "loading" && <p>바우처를 불러오는 중입니다.</p>}
          {voucherState === "error" && <p className="error">{voucherError}</p>}
          {voucherState === "done" && vouchers.length === 0 && (
            <p className="warning">발급된 바우처가 없습니다.</p>
          )}
          {voucherState === "done" && vouchers.length > 0 && (
            <ul className="voucher-list">
              {vouchers.map((voucher) => (
                <li className="card" key={voucher.voucher_id}>
                  <p><strong>{voucher.voucher_id}</strong> · #{voucher.sequence_no}</p>
                  <p>상태: {voucher.status}</p>
                  <p className="mono">QR: {voucher.qr_payload}</p>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
      <div className="actions">
        <Link to="/" className="cta-link">홈으로</Link>
        <Link to="/search" className="cta-link">숙소 더보기</Link>
        <Link to="/tickets" className="cta-link">티켓 더보기</Link>
      </div>
    </section>
  );
}
