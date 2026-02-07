import { FormEvent, useState } from "react";
import { apiPost } from "../api/client";

type ApiError = { code?: string; message?: string };
type ReindexData = { scanned: number; upserted: number; failed: number };

export function OpsPage() {
  const [limit, setLimit] = useState(10000);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<ReindexData | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function runReindex(e: FormEvent) {
    e.preventDefault();
    setRunning(true);
    setResult(null);
    setError(null);
    try {
      const query = new URLSearchParams();
      if (limit > 0) query.set("limit", String(limit));
      const res = await apiPost<ReindexData>(
        `/v1/admin/search/reindex?${query.toString()}`,
        {},
      );
      setResult(res.data);
    } catch (e) {
      const apiError = e as ApiError;
      setError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "재색인 요청 실패"}`);
    } finally {
      setRunning(false);
    }
  }

  return (
    <div>
      <h2>운영 도구</h2>
      <form className="row-form" onSubmit={runReindex}>
        <label>
          재색인 limit
          <input
            type="number"
            min={1}
            max={20000}
            value={limit}
            onChange={(e) => setLimit(Math.max(1, Number(e.target.value)))}
          />
        </label>
        <button type="submit" disabled={running}>
          {running ? "요청 중..." : "검색 재색인 실행"}
        </button>
      </form>
      {result && (
        <div>
          <p className="success">재색인 요청 완료</p>
          <p>scanned: {result.scanned}</p>
          <p>upserted: {result.upserted}</p>
          <p>failed: {result.failed}</p>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      <p>
        시드 데이터 반영 후 실행 권장:
        <code> ./scripts/seed_local.sh </code>
      </p>
    </div>
  );
}
