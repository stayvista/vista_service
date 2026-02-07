import { FormEvent, useState } from "react";
import { apiPost } from "../api/client";

type ApiError = { code?: string; message?: string };
type VoucherLogItem = {
  inputMode: "voucher_id" | "qr_payload";
  inputValue: string;
  result: string;
  message: string;
  at: string;
};

export function VouchersPage() {
  const [inputMode, setInputMode] = useState<"voucher_id" | "qr_payload">("voucher_id");
  const [voucherId, setVoucherId] = useState("vch_1");
  const [qrPayload, setQrPayload] = useState("qr-one");
  const [result, setResult] = useState<string | null>(null);
  const [logs, setLogs] = useState<VoucherLogItem[]>([]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    const payload = inputMode === "voucher_id"
      ? { voucher_id: voucherId }
      : { qr_payload: qrPayload };
    const inputValue = inputMode === "voucher_id" ? voucherId : qrPayload;
    try {
      const res = await apiPost<{ result: string }>("/v1/admin/vouchers/validate", payload);
      setResult(res.data.result);
      setLogs((prev) => [
        {
          inputMode,
          inputValue,
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
          inputMode,
          inputValue,
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
        <select
          value={inputMode}
          onChange={(e) => setInputMode(e.target.value as "voucher_id" | "qr_payload")}
        >
          <option value="voucher_id">코드(voucher_id)</option>
          <option value="qr_payload">QR Payload</option>
        </select>
        {inputMode === "voucher_id" ? (
          <input value={voucherId} onChange={(e) => setVoucherId(e.target.value)} />
        ) : (
          <input value={qrPayload} onChange={(e) => setQrPayload(e.target.value)} />
        )}
        <button type="submit">검증</button>
        <button type="button" onClick={() => { setInputMode("voucher_id"); setVoucherId("vch_1"); }}>
          샘플 vch_1
        </button>
        <button type="button" onClick={() => { setInputMode("voucher_id"); setVoucherId("vch_999"); }}>
          샘플 vch_999
        </button>
        <button type="button" onClick={() => { setInputMode("qr_payload"); setQrPayload("qr-one"); }}>
          샘플 qr-one
        </button>
      </form>
      {result && <p className={result === "VALID" ? "success" : "warning"}>결과: {result}</p>}
      <h3>검표 로그</h3>
      <ul className="table">
        {logs.map((log, index) => (
          <li key={`${log.inputMode}-${log.inputValue}-${log.at}-${index}`}>
            <span>{log.at}</span>
            <span>{log.inputMode}: {log.inputValue}</span>
            <span>{log.result}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
