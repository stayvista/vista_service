import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { apiPost } from "../api/client";

export function CheckoutPackagePage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [packageOrderId, setPackageOrderId] = useState<string | null>(null);

  async function hold() {
    const packageId = params.get("package_id");
    if (!packageId) return;
    const res = await apiPost<{ package_order_id: string }>(
      `/v1/packages/${packageId}/holds`,
      { check_in: "2026-02-10", check_out: "2026-02-12", rooms: 1, ticket_quantity: 1 },
      { "Idempotency-Key": crypto.randomUUID(), "X-User-Id": "1001" }
    );
    setPackageOrderId(res.data.package_order_id);
  }

  async function confirm() {
    const packageId = params.get("package_id");
    if (!packageId || !packageOrderId) return;
    await apiPost(
      `/v1/packages/${packageId}/confirm`,
      { package_order_id: packageOrderId, payment_token: "paytok_test" },
      { "Idempotency-Key": crypto.randomUUID(), "X-User-Id": "1001" }
    );
    navigate("/booking/complete");
  }

  return (
    <section className="page">
      <h2>패키지 결제</h2>
      <div className="actions">
        <button onClick={hold}>HOLD</button>
        <button disabled={!packageOrderId} onClick={confirm}>CONFIRM</button>
      </div>
    </section>
  );
}
