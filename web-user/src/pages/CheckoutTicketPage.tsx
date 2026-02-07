import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { apiPost } from "../api/client";

export function CheckoutTicketPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [orderId, setOrderId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function hold() {
    setError(null);
    try {
      const res = await apiPost<{ order_id: string }>(
        "/v1/tickets/orders/holds",
        {
          event_id: Number(params.get("event_id")),
          quantity: 1,
          price: { currency: "KRW", amount_total: 43000 },
        },
        { "Idempotency-Key": crypto.randomUUID(), "X-User-Id": "1001" }
      );
      setOrderId(res.data.order_id);
    } catch (e) {
      const err = e as { code?: string; message?: string };
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "hold 실패"}`);
    }
  }

  async function confirm() {
    if (!orderId) return;
    await apiPost(
      `/v1/tickets/orders/${orderId}/confirm`,
      { payment_method: "CARD", payment_token: "paytok_test" },
      { "Idempotency-Key": crypto.randomUUID(), "X-User-Id": "1001" }
    );
    navigate("/booking/complete");
  }

  return (
    <section className="page">
      <h2>티켓 결제</h2>
      {error && <p className="error">{error}</p>}
      <div className="actions">
        <button onClick={hold}>HOLD</button>
        <button disabled={!orderId} onClick={confirm}>CONFIRM</button>
      </div>
    </section>
  );
}
