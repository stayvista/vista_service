import { FormEvent, useState } from "react";
import { apiPost } from "../api/client";

export function VouchersPage() {
  const [voucherId, setVoucherId] = useState("vch_1");
  const [result, setResult] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    try {
      const res = await apiPost<{ result: string }>("/v1/admin/vouchers/validate", { voucher_id: voucherId });
      setResult(res.data.result);
    } catch (e) {
      const err = e as { error?: { code?: string } };
      setResult(err.error?.code ?? "ERROR");
    }
  }

  return (
    <div>
      <h2>바우처 검표</h2>
      <form className="row-form" onSubmit={onSubmit}>
        <input value={voucherId} onChange={(e) => setVoucherId(e.target.value)} />
        <button type="submit">검증</button>
      </form>
      {result && <p>결과: {result}</p>}
    </div>
  );
}
