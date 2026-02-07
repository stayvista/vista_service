import { FormEvent, useState } from "react";
import { apiPost } from "../api/client";

type ApiError = { code?: string; message?: string };
type VoucherLogItem = {
  voucherId: string;
  result: string;
  message: string;
  at: string;
};

export function VouchersPage() {
  const [voucherId, setVoucherId] = useState("vch_1");
  const [result, setResult] = useState<string | null>(null);
  const [logs, setLogs] = useState<VoucherLogItem[]>([]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    try {
      const res = await apiPost<{ result: string }>("/v1/admin/vouchers/validate", { voucher_id: voucherId });
      setResult(res.data.result);
      setLogs((prev) => [
        {
          voucherId,
          result: res.data.result,
          message: "검증 성공",
          at: new Date().toLocaleString(),
        },
        ...prev,
      ].slice(0, 20));
    } catch (e) {
      const err = e as ApiError;
      const code = err.code ?? "ERROR";
      setResult(code);
      setLogs((prev) => [
        {
          voucherId,
          result: code,
          message: err.message ?? "검증 실패",
          at: new Date().toLocaleString(),
        },
        ...prev,
      ].slice(0, 20));
    }
  }

  return (
    <div>
      <h2>바우처 검표</h2>
      <form className="row-form" onSubmit={onSubmit}>
        <input value={voucherId} onChange={(e) => setVoucherId(e.target.value)} />
        <button type="submit">검증</button>
        <button type="button" onClick={() => setVoucherId("vch_1")}>샘플 vch_1</button>
        <button type="button" onClick={() => setVoucherId("vch_999")}>샘플 vch_999</button>
      </form>
      {result && <p className={result === "VALID" ? "success" : "warning"}>결과: {result}</p>}
      <h3>검표 로그</h3>
      <ul className="table">
        {logs.map((log, index) => (
          <li key={`${log.voucherId}-${log.at}-${index}`}>
            <span>{log.at}</span>
            <span>{log.voucherId}</span>
            <span>{log.result}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
